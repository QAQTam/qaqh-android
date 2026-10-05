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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    private val baseUrl = baseUrl.trimEnd('/')
    private val jsonMedia = "application/json".toMediaType()

    class ApiException(
        val httpStatus: Int,
        val code: String?,
        message: String,
    ) : Exception("HTTP $httpStatus ${code ?: "-"}: $message") {
        val isUnauthorized: Boolean get() = httpStatus == 401
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

    /** GET /sessions/{seed}/bootstrap，拿快照与起始 cursor。 */
    fun bootstrap(clientSessionId: String, seed: String): Bootstrap =
        execute(requestBuilder("/sessions/$seed/bootstrap", clientSessionId).get().build())

    /** GET /sessions/{seed}/approvals，当前待审批快照。 */
    fun approvals(clientSessionId: String, seed: String): Approvals =
        execute(requestBuilder("/sessions/$seed/approvals", clientSessionId).get().build())

    /** POST /commands/{channel}，提交命令信封，返回回执（回执 status 也可能是 rejected）。 */
    fun postCommand(clientSessionId: String, envelope: CommandEnvelope): CommandAck = execute(
        requestBuilder("/commands/${envelope.channel}", clientSessionId)
            .post(json.encodeToString(CommandEnvelope.serializer(), envelope).toRequestBody(jsonMedia))
            .build()
    )

    /** POST /service/session.list — 会话列表（单活跃流模式的发现来源）。 */
    fun sessionList(clientSessionId: String): List<SessionListEntry> = execute(
        requestBuilder("/service/session.list", clientSessionId)
            .post(EMPTY_JSON.toRequestBody(jsonMedia))
            .build()
    )

    /** GET /sessions/{seed}/events 的 SSE 流。emit 事件信封与重置信号；从不完成（除非上游关闭）。 */
    fun events(
        clientSessionId: String,
        seed: String,
        sinceCursor: String?,
    ): Flow<SseEvent> = callbackFlow {
        val url = (baseUrl + RingingProtocol.BASE_PATH + "/sessions/$seed/events").toHttpUrl()
            .newBuilder()
            .apply { if (sinceCursor != null) addQueryParameter("since_cursor", sinceCursor) }
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("X-QAQH-Client-Session-Id", clientSessionId)
            .header("Accept", "text/event-stream")
            .build()

        lateinit var source: EventSource
        source = EventSources.createFactory(http).newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                if (!response.isSuccessful) {
                    val body = runCatching { response.body?.string() }.getOrNull().orEmpty()
                    close(throwApiException(response, body))
                }
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                when (type) {
                    EVENT_RESET -> trySendBlocking(SseEvent.Reset(json.decodeFromString<ResetRequired>(data)))
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
    }

    companion object {
        const val EVENT_RINGING = "ringing.event"
        const val EVENT_RESET = "ringing.reset_required"
        private const val EMPTY_JSON = "{}"
    }
}
