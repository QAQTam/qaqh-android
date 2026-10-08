package com.qaqh.harness.data.ringing

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 断流（切后台被 OEM 掐连接 / daemon 重启 / 代理回收）不得杀死进程。
 *
 * 真机 dropbox 里成串的崩溃，栈顶一律是
 * `java.net.SocketException: Software caused connection abort`
 * `at okhttp3.sse.internal.RealEventSource.processResponse(RealEventSource.kt:80)`
 * `Suppressed: DiagnosticCoroutineContextException: [StandaloneCoroutine{Cancelling}, Dispatchers.IO]`
 * ——`{Cancelling}` 是本案钥匙：异常最终是从 **Dispatchers.IO 上的 StandaloneCoroutine**
 * 里逃出来的（待办跟踪循环只 catch 了 ApiException，裸 IOException 一路冒到
 * SupervisorJob 之外 → 线程 uncaught handler → 进程死）。
 *
 * 两条测试分别锁住这条链的两环：
 * 1. 传输层必须把断流归位成 collect 侧的异常（不得从 okhttp 回调里往外抛）；
 * 2. 服务作用域必须有兜底 CoroutineExceptionHandler（任何循环漏网的异常都不该带崩进程）。
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
class SseSocketDeathTest {

    private val frame =
        "event: timeline.entry\n" +
            "data: {\"schema\":\"x\",\"entry\":{\"timeline_seq\":1,\"turn_id\":\"t1\",\"event\":{\"type\":\"noop\"}}}\n\n"

    /**
     * 裸 ServerSocket 复刻现场：持续吐帧，直到测试取消订阅（[stopped]）后再把连接掐死
     * （半截 chunk + RST）。
     */
    private fun startStreamStoppedAfter(stopped: CountDownLatch): Int {
        val server = ServerSocket(0)
        Thread {
            runCatching {
                server.accept().use { s: Socket ->
                    val out = s.getOutputStream()
                    out.write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/event-stream\r\n" +
                                "Transfer-Encoding: chunked\r\n\r\n"
                            ).toByteArray(),
                    )
                    val chunk = (Integer.toHexString(frame.length) + "\r\n" + frame + "\r\n").toByteArray()
                    // 边吐帧边等取消信号：确保"订阅先死、流后断"的真实时序
                    repeat(200) {
                        if (stopped.await(50, TimeUnit.MILLISECONDS)) return@use
                        out.write(chunk)
                        out.flush()
                    }
                }
            }
            runCatching { server.close() }
        }.start()
        return server.localPort
    }

    @Test
    fun `unsubscribing then losing the socket must not reach the thread uncaught handler`() = runBlocking {
        val stopped = CountDownLatch(1)
        val port = startStreamStoppedAfter(stopped)
        val api = RingingApi("http://127.0.0.1:$port", "t", OkHttpClient())

        val saved = Thread.getDefaultUncaughtExceptionHandler()
        var uncaught: Throwable? = null
        val reported = CountDownLatch(1)
        Thread.setDefaultUncaughtExceptionHandler { _, t ->
            uncaught = t
            reported.countDown()
        }
        var collected = 0
        val job = Job()
        try {
            val collecting = launch(Dispatchers.IO + job) {
                runCatching { api.timelineEvents("lease-1", "sess-1").collect { collected++ } }
            }
            withTimeout(8_000) { while (collected == 0) delay(20) }
            collecting.cancel() // = 退后台时 SessionTimeline.stop()
            stopped.countDown() // 订阅已撤，此刻才掐流
            delay(1_500)
            reported.await(1_500, TimeUnit.MILLISECONDS)
        } finally {
            job.cancel()
            Thread.setDefaultUncaughtExceptionHandler(saved)
        }
        assertTrue("SSE 一帧都没收到，mock 没生效", collected > 0)
        assertNull("断流异常逃到线程级 uncaught handler：进程会被直接杀掉 ($uncaught)", uncaught)
    }

    /**
     * 崩溃的直接机制（与真机堆栈逐字对得上）：待办跟踪循环跑在
     * `StandaloneCoroutine + Dispatchers.IO + SupervisorJob` 上，子协程失败不冒泡给父，
     * 于是裸 SocketException 被交给默认 CoroutineExceptionHandler →
     * 线程 uncaught handler → 进程死。堆栈里那句
     * `Suppressed: DiagnosticCoroutineContextException: [StandaloneCoroutine{Cancelling}, Dispatchers.IO]`
     * 就是这条路径的签名。服务作用域必须自带兜底 handler。
     */
    @Test
    fun `escaped stream failure does not kill the process`() = runBlocking {
        val saved = Thread.getDefaultUncaughtExceptionHandler()
        var uncaught: Throwable? = null
        val reported = CountDownLatch(1)
        Thread.setDefaultUncaughtExceptionHandler { _, t ->
            uncaught = t
            reported.countDown()
        }
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + com.qaqh.harness.connection.serviceExceptionGuard,
        )
        try {
            val failed = java.util.concurrent.atomic.AtomicBoolean()
            scope.launch {
                failed.set(true)
                throw java.net.SocketException("Software caused connection abort")
            }
            withTimeout(5_000) { while (!failed.get()) delay(20) }
            reported.await(1_000, TimeUnit.MILLISECONDS)
            delay(300)
        } finally {
            scope.cancel()
            Thread.setDefaultUncaughtExceptionHandler(saved)
        }
        assertNull("逃逸的断流异常把进程带上了 ($uncaught)", uncaught)
    }
}
