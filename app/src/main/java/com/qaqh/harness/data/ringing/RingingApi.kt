package com.qaqh.harness.data.ringing

import com.qaqh.harness.data.ringing.model.Approvals
import com.qaqh.harness.data.ringing.model.Bootstrap
import com.qaqh.harness.data.ringing.model.CommandAck
import com.qaqh.harness.data.ringing.model.CommandEnvelope
import com.qaqh.harness.data.ringing.model.EventEnvelope
import com.qaqh.harness.data.ringing.model.OpenRequest
import com.qaqh.harness.data.ringing.model.OpenResponse
import com.qaqh.harness.data.ringing.model.RenewResponse
import com.qaqh.harness.data.ringing.model.ResetRequired
import com.qaqh.harness.data.ringing.model.RingingProtocol
import com.qaqh.harness.data.ringing.model.SessionListEntry
import com.qaqh.harness.data.ringing.model.WorkspaceEntry
import com.qaqh.harness.data.ringing.model.TimelineEntryEnvelope
import com.qaqh.harness.data.ringing.model.TimelineEntryFrame
import com.qaqh.harness.data.ringing.model.TimelineResponse
import com.qaqh.harness.data.ringing.model.DriverClaimResponse
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * Ringing v2 HTTP 面：open / renew / bootstrap / SSE events / commands / approvals。
 * 纯传输层，不做重连与租约管理（见 [RingingConnection]）。
 */
class RingingApi(
    baseUrl: String,
    private val token: String,
    client: OkHttpClient? = null,
) {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val http = client ?: OkHttpClient.Builder().build()

    /** SSE 专用：readTimeout=0，空闲长连接不被 10s 默认读超时掐断。 */
    private val sseHttp = (client ?: OkHttpClient()).newBuilder()
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()
    private val baseUrl = baseUrl.trimEnd('/')
    private val jsonMedia = "application/json".toMediaType()

    class ApiException(
        val httpStatus: Int,
        val code: String?,
        message: String,
    ) : Exception("HTTP $httpStatus ${code ?: "-"}: $message") {
        val isUnauthorized: Boolean get() = httpStatus == 401

        /**
         * 会话尚未物化（session_not_found / snapshot_missing，HTTP 404/409）：
         * 新建会话在首个 canonical 事实落盘前的正常瞬态（对齐 Rust error.rs
         * is_session_not_ready）。调用方应短退避重试且**保持租约**，不得重开。
         */
        val isSessionNotReady: Boolean
            get() = httpStatus == 404 || httpStatus == 409 ||
                code == "session_not_found" || code == "snapshot_missing"
    }

    private fun requestBuilder(path: String, clientSessionId: String? = null): Request.Builder {
        val b = Request.Builder()
            .url(baseUrl + RingingProtocol.BASE_PATH + path)
            .header("Authorization", "Bearer $token")
        if (clientSessionId != null) b.header("X-QAQH-Client-Session-Id", clientSessionId)
        return b
    }

    private inline fun <reified T> execute(request: Request): T {
        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throwApiException(resp, body)
            return json.decodeFromString(body)
        }
    }

    private fun throwApiException(resp: Response, body: String): Nothing {
        val code = runCatching { json.decodeFromString(ApiError.serializer(), body).code }.getOrNull()
        throw ApiException(resp.code, code, body.take(200))
    }

    @kotlinx.serialization.Serializable
    private data class ApiError(val code: String? = null, val message: String? = null)

    /** POST /clients/open，建立租约。 */
    fun open(clientInstanceId: String): OpenResponse = execute(
        requestBuilder("/clients/open")
            .post(json.encodeToString(OpenRequest.serializer(), OpenRequest(clientInstanceId = clientInstanceId)).toRequestBody(jsonMedia))
            .build()
    )

    /** POST /leases/renew，续租当前会话。 */
    fun renew(clientSessionId: String): RenewResponse = execute(
        requestBuilder("/leases/renew", clientSessionId)
            .post(ByteArray(0).toRequestBody(null))
            .build()
    )

    /** GET /sessions/{session_id}/bootstrap，拿快照与起始 cursor。 */
    fun bootstrap(clientSessionId: String, sessionId: String): Bootstrap =
        execute(requestBuilder("/sessions/$sessionId/bootstrap", clientSessionId).get().build())

    /** GET /sessions/{session_id}/approvals，当前待审批快照（admin 得 pending_*，device 得 challenges）。 */
    fun approvals(clientSessionId: String, sessionId: String): Approvals =
        execute(requestBuilder("/sessions/$sessionId/approvals", clientSessionId).get().build())

    /**
     * POST /sessions/{session_id}/approvals/respond — device 身份的审批应答（S5）。
     * [decision] 取 [com.qaqh.harness.data.ringing.model.ApprovalDecisions]；
     * ask.submit 需 payload={"answers":[...]}，plan.reject 可带 {"message","autonomous"}。
     * 一次性消费 challenge：错 session/过期/重复消费得 403（challenge rejected）。
     */
    fun approvalRespond(
        clientSessionId: String,
        sessionId: String,
        challengeId: String,
        decision: String,
        payload: JsonObject? = null,
    ): CommandAck = execute(
        requestBuilder("/sessions/$sessionId/approvals/respond", clientSessionId)
            .post(
                buildJsonObject {
                    put("challenge_id", challengeId)
                    put("decision", decision)
                    if (payload != null) put("payload", payload)
                }.toString().toRequestBody(jsonMedia)
            )
            .build()
    )

    /** POST /commands/{channel}，提交命令信封，返回回执（回执 status 也可能是 rejected）。 */
    fun postCommand(clientSessionId: String, envelope: CommandEnvelope): CommandAck = execute(
        requestBuilder("/commands/${envelope.channel}", clientSessionId)
            .post(json.encodeToString(CommandEnvelope.serializer(), envelope).toRequestBody(jsonMedia))
            .build()
    )

    /** GET /sessions/{session_id}/timeline?before_index=&limit= — 聊天时间线分页（要求 attach）。 */
    fun timeline(clientSessionId: String, sessionId: String, beforeIndex: Long? = null, limit: Int = 30): TimelineResponse {
        val url = (baseUrl + RingingProtocol.BASE_PATH + "/sessions/$sessionId/timeline").toHttpUrl()
            .newBuilder()
            .apply {
                if (beforeIndex != null) addQueryParameter("before_index", beforeIndex.toString())
                addQueryParameter("limit", limit.toString())
            }
            .build()
        return execute(Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("X-QAQH-Client-Session-Id", clientSessionId)
            .get().build())
    }

    /** POST /sessions/{session_id}/driver/claim — 认领 driver 席位（发会话命令前需要）。 */
    fun driverClaim(clientSessionId: String, sessionId: String): DriverClaimResponse =
        execute(
            requestBuilder("/sessions/$sessionId/driver/claim", clientSessionId)
                .post(EMPTY_JSON.toRequestBody(jsonMedia))
                .build()
        )

    /** GET /sessions/{session_id}/timeline/events — 聊天实时增量（event: timeline.entry / ringing.stream_terminated）。
     *  [lastEventId] 传 `{epoch}:timeline:{seq}`（Last-Event-ID）：服务端只回放该 seq 之后的尾巴；
     *  不传则全量回放整个会话历史（900+ 帧/次，重连风暴时的卡顿根源）。 */
    fun timelineEvents(clientSessionId: String, sessionId: String, lastEventId: String? = null): Flow<TimelineSseEvent> = callbackFlow {
        val url = (baseUrl + RingingProtocol.BASE_PATH + "/sessions/$sessionId/timeline/events").toHttpUrl()
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("X-QAQH-Client-Session-Id", clientSessionId)
            .header("Accept", "text/event-stream")
            .apply { if (!lastEventId.isNullOrBlank()) header("Last-Event-ID", lastEventId) }
            .build()
        lateinit var source: EventSource
        source = EventSources.createFactory(sseHttp).newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                if (!response.isSuccessful) {
                    val body = runCatching { response.body?.string() }.getOrNull().orEmpty()
                    close(throwApiException(response, body))
                }
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                when (type) {
                    EVENT_STREAM_TERMINATED -> trySendBlocking(TimelineSseEvent.Terminated)
                    else -> runCatching {
                        TimelineSseEvent.Entry(json.decodeFromString<TimelineEntryEnvelope>(data).entry)
                    }.onSuccess { trySendBlocking(it) }
                        .onFailure {
                            // 静默吞掉解码失败会让上层表现为"零帧"且无线索，必须留痕
                            android.util.Log.w("RingingApi", "timeline frame decode failed: $it; data=${data.take(300)}")
                        }
                }
            }

            override fun onClosed(eventSource: EventSource) { close() }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                close(t ?: ApiException(response?.code ?: 0, null, "timeline SSE failure"))
            }
        })
        awaitClose { source.cancel() }
    }

    sealed interface TimelineSseEvent {
        data class Entry(val frame: TimelineEntryFrame) : TimelineSseEvent
        data object Terminated : TimelineSseEvent
    }

    /** GET /content/{content_id} — 按 id 取内容原始字节（要求 lease 已 attach 该会话）。 */
    fun content(clientSessionId: String, contentId: String): String =
        http.newCall(requestBuilder("/content/$contentId", clientSessionId).get().build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException(resp.code, null, body.take(120))
            body
        }

    /** POST /service/session.list — 会话列表（单活跃流模式的发现来源）。 */
    fun sessionList(clientSessionId: String): List<SessionListEntry> = execute(
        requestBuilder("/service/session.list", clientSessionId)
            .post(EMPTY_JSON.toRequestBody(jsonMedia))
            .build()
    )

    /** POST /service/workspace.list — UI 工作区注册表（Home 分组；读操作，view+scope 可用）。 */
    fun workspaceList(clientSessionId: String): List<WorkspaceEntry> = execute(
        requestBuilder("/service/workspace.list", clientSessionId)
            .post(EMPTY_JSON.toRequestBody(jsonMedia))
            .build()
    )

    /** GET /sessions/{session_id}/events 的 SSE 流。emit 事件信封与重置信号；从不完成（除非上游关闭）。 */
    fun events(
        clientSessionId: String,
        sessionId: String,
        sinceCursor: String?,
    ): Flow<SseEvent> = callbackFlow {
        val url = (baseUrl + RingingProtocol.BASE_PATH + "/sessions/$sessionId/events").toHttpUrl()
            .newBuilder()
            .apply { if (sinceCursor != null) addQueryParameter("since_cursor", sinceCursor) }
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("X-QAQH-Client-Session-Id", clientSessionId)
            .header("Accept", "text/event-stream")
            .build()

        // 必须用 sseHttp（readTimeout=0）：默认 client 10s 读超时会掐断空闲 SSE，
        // 造成 ~11s 周期的重连风暴（每次重连都换 client_session_id）。
        lateinit var source: EventSource
        source = EventSources.createFactory(sseHttp).newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                if (!response.isSuccessful) {
                    val body = runCatching { response.body?.string() }.getOrNull().orEmpty()
                    close(throwApiException(response, body))
                }
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                when (type) {
                    EVENT_RESET -> trySendBlocking(SseEvent.Reset(json.decodeFromString<ResetRequired>(data)))
                    // daemon 在 broadcast Lagged（消费端跟不上被丢弃）时下发：必须据此
                    // 立即重订阅，静默丢弃会丢增量直到空闲看门狗兜底（最长 45s）
                    EVENT_STREAM_TERMINATED -> trySendBlocking(SseEvent.StreamTerminated)
                    null, EVENT_RINGING -> {
                        val envelope = json.decodeFromString<EventEnvelope>(data)
                        trySendBlocking(SseEvent.Ringing(envelope, id))
                    }
                }
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                close(t ?: ApiException(response?.code ?: 0, null, "SSE failure"))
            }
        })
        awaitClose { source.cancel() }
    }

    sealed interface SseEvent {
        data class Ringing(val envelope: EventEnvelope, val sseId: String?) : SseEvent
        data class Reset(val reset: ResetRequired) : SseEvent

        /** 服务端主动终止本条流（broadcast Lagged 等），消费端应立即重订阅。 */
        data object StreamTerminated : SseEvent
    }

    companion object {
        const val EVENT_RINGING = "ringing.event"
        const val EVENT_RESET = "ringing.reset_required"
        const val EVENT_STREAM_TERMINATED = "ringing.stream_terminated"
        private const val EMPTY_JSON = "{}"
    }
}
