package com.qaqh.harness.data.ringing

import com.qaqh.harness.data.ringing.model.TimelineEntryFrame
import com.qaqh.harness.data.ringing.model.TimelineTool
import com.qaqh.harness.data.ringing.model.TodoState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * 会话 timeline 流的**唯一**订阅者：一条 `timeline/events` SSE 喂两个消费方——
 * 聊天屏的 [SessionTimeline] reducer，和待办实况通知（[todos]）。
 *
 * 合并前是两处各订一遍同一条流（`SessionTimeline.start` 与旧的
 * `ConnectionService.todoTrackerLoop`）：多一条长连接（移动端每条独立 TCP 都是一次
 * 无线电唤醒）、多一份服务端广播扇出、todo 状态算两遍且可能互相矛盾。
 *
 * replay 缓存是这次合并的前提：聊天屏是**后加入**的消费方，它先拉 REST 页拿到
 * watermark，再从缓存里接上 watermark 之后的尾巴，中间不留缝（SharedFlow 保证
 * "回放缓存 + 后续实时"原子且有序）。缓存满时 emit 挂住读流而不是丢帧——反压到
 * 服务端，由空闲看门狗兜底重订阅，与合并前 `trySendBlocking` 的行为一致。
 */
class TimelineStream(
    private val connection: RingingConnection,
    private val scope: CoroutineScope,
) {
    private val _frames = MutableSharedFlow<TimelineEntryFrame>(
        replay = REPLAY_CAPACITY,
        extraBufferCapacity = REPLAY_CAPACITY,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /** 目标会话的全部 timeline 帧，按 timeline_seq 递增。 */
    val frames: SharedFlow<TimelineEntryFrame> = _frames

    /** 当前待办清单（来自 todo_write/todo_update 工具块的 args_json；null = 本会话无待办）。 */
    private val _todos = MutableStateFlow<TodoState?>(null)
    val todos: StateFlow<TodoState?> = _todos.asStateFlow()

    private var job: Job? = null
    private var lastSeq: Long? = null
    private var trackedEpoch: String? = null
    private var trackedSession: String? = null

    /**
     * replay 缓存里最老的一帧的 seq。聊天屏的 REST 水位比它还小时，说明水位到缓存
     * 起点之间的帧已被挤掉，必须重新拉一页补缝——见 [SessionTimeline.start]。
     */
    fun oldestBufferedSeq(): Long? = _frames.replayCache.firstOrNull()?.timelineSeq

    /** 开始订阅（幂等）。停止由 [stop] 或 [scope] 取消负责。 */
    fun start() {
        if (job != null) return
        job = scope.launch {
            var resubDelayMs = 1_000L
            while (isActive) {
                // 每次尝试都重新解析当前租约：切会话/重连会轮换 lease，旧租约必 401。
                // 不能只在 Connected 状态变化时订阅——续租失败路径的 connectOnce 不经过
                // Connecting，状态不变但租约已换，旧租约的 live 帧会被服务端逐帧静默
                // 丢弃（sse.rs should_deliver_timeline_live）。
                val sid = connection.leases.first()
                val sessionId = connection.sessionSeed.value
                if (sessionId == null) {
                    delay(500)
                    continue
                }
                if (sessionId != trackedSession) {
                    trackedSession = sessionId
                    lastSeq = null
                    trackedEpoch = null
                    _todos.value = null
                    // 上一个会话的尾巴不能回放给新会话的消费方
                    _frames.resetReplayCache()
                }
                val epoch = (connection.state.value as? RingingConnection.State.Connected)?.serverEpoch
                // 游标 = 当前 epoch + 已消费水位：epoch 变了（daemon 重启）退回全量回放
                //（reducer 幂等），正常重连只补尾巴
                val cursor = if (epoch != null && epoch == trackedEpoch && lastSeq != null) {
                    "$epoch:timeline:$lastSeq"
                } else {
                    null
                }
                trackedEpoch = epoch
                android.util.Log.i(
                    TAG,
                    "timeline SSE subscribe lease=${sid.take(8)} session=${sessionId.take(12)} " +
                        "cursor=${if (cursor != null) "seq=$lastSeq" else "none(full replay)"}",
                )
                try {
                    var frames = 0
                    coroutineScope {
                        // 空闲看门狗：服务端每 15s 发 keepalive 注释（读流看得到字节但
                        // 不成帧），正常流式期间帧间隔远小于此。长时间零帧说明流已半开/
                        // 黑洞（网络切换、代理回收），带游标重订阅快速恢复。对齐 Rust 45s。
                        var lastFrameAt = System.nanoTime()
                        val watchdog = launch {
                            while (true) {
                                delay(5_000)
                                if (System.nanoTime() - lastFrameAt > IDLE_TIMEOUT_MS * 1_000_000) {
                                    throw StreamIdle
                                }
                            }
                        }
                        val leaseWaiter = launch {
                            connection.leases.drop(1).first()
                            throw LeaseRotated
                        }
                        // 切会话必须立刻改订阅：等旧流自然断掉的话，聊天屏会拿到上一个
                        // 会话的帧（合并前 SessionTimeline 自带 sessionId，没有这个问题）
                        val sessionWaiter = launch {
                            connection.sessionSeed.drop(1).first()
                            throw SessionChanged
                        }
                        try {
                            connection.api.timelineEvents(sid, sessionId, cursor).collect { ev ->
                                lastFrameAt = System.nanoTime()
                                frames++
                                if (frames == 1) resubDelayMs = 1_000L // 流恢复正常，退避归位
                                if (frames == 1 || frames % 100 == 0) {
                                    android.util.Log.i(TAG, "timeline SSE frames=$frames")
                                }
                                if (ev is RingingApi.TimelineSseEvent.Entry) onFrame(ev.frame)
                            }
                        } finally {
                            sessionWaiter.cancel()
                            leaseWaiter.cancel()
                            watchdog.cancel()
                        }
                    }
                    android.util.Log.i(TAG, "timeline SSE flow completed after $frames frames")
                } catch (e: LeaseRotated) {
                    continue // 拿新租约立即重订阅
                } catch (e: SessionChanged) {
                    continue // 换会话：循环顶部会重置游标与缓存
                } catch (e: StreamIdle) {
                    android.util.Log.w(TAG, "timeline SSE idle ${IDLE_TIMEOUT_MS}ms — resubscribe with cursor")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: RingingApi.ApiException) {
                    android.util.Log.w(TAG, "timeline SSE ApiException ${e.httpStatus} ${e.code}")
                } catch (e: Exception) {
                    // 裸 IOException/SocketException（切后台被掐网、代理回收连接）：与空闲
                    // 断流同类，退避后带游标重订阅。冒出去会让整条流判死且不再重连。
                    android.util.Log.w(TAG, "timeline SSE dropped: ${e.javaClass.simpleName} ${e.message}")
                }
                // 断流后重订阅退避：首帧到达时归位 1s，持续连不上/连上即断时指数增长
                //（1s→30s），避免对抖动的 daemon 形成重连风暴
                delay(resubDelayMs)
                resubDelayMs = minOf(resubDelayMs * 2, RESUB_DELAY_MAX_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun onFrame(frame: TimelineEntryFrame) {
        lastSeq = maxOf(lastSeq ?: 0L, frame.timelineSeq)
        ingestTodos(frame)
        // 挂起 = 反压：宁可让服务端把慢消费方判成 Lagged 重订阅，也不静默丢帧
        _frames.emit(frame)
    }

    /** todo 工具块 → 待办清单（write 整表替换 / update 单条 delta；畸形忽略）。 */
    private fun ingestTodos(frame: TimelineEntryFrame) {
        val type = frame.type ?: return
        val data = frame.event as? JsonObject ?: return
        val toolElement = when (type) {
            "block_opened" -> (data["block"] as? JsonObject)?.get("tool")
            "tool_updated" -> data["tool"]
            else -> return
        } ?: return
        val tool = decodeTimelineTool(connection.api, toolElement) ?: return
        TodoState.ingestTool(_todos.value, tool.name, tool.argsJson)?.let { _todos.value = it }
    }

    private object LeaseRotated : Exception("lease rotated")
    private object SessionChanged : Exception("session changed")
    private object StreamIdle : Exception("stream idle")

    private companion object {
        const val TAG = "TimelineStream"

        /** 回放缓存深度：够聊天屏从 REST 水位无缝续上（一屏流式增量远小于此）。 */
        const val REPLAY_CAPACITY = 256

        /** 无帧判定为死流的阈值；服务端 15s keepalive 只保管道。对齐 Rust v2_stream 45s。 */
        const val IDLE_TIMEOUT_MS = 45_000L

        /** 重订阅退避上限（对齐 Rust v2_stream 1s→30s）。 */
        const val RESUB_DELAY_MAX_MS = 30_000L
    }
}

/** 工具块载荷解码（SSE 帧里 block_opened 内嵌 / tool_updated 顶层）；畸形返回 null 不炸流。 */
internal fun decodeTimelineTool(api: RingingApi, element: JsonElement): TimelineTool? =
    runCatching { api.json.decodeFromJsonElement(TimelineTool.serializer(), element) }.getOrNull()
