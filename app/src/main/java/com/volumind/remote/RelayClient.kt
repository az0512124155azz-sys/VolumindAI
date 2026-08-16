package com.volumind.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

data class ChatMessage(val id: String, val sender: String, val text: String, val timestamp: Long = System.currentTimeMillis())
data class BuildStep(val index: Int, val title: String, val state: String)
data class RemoteQuestion(val id: String, val text: String, val options: List<String>)
data class RemoteState(
    val connection: String = "מנותק",
    val paired: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val steps: List<BuildStep> = emptyList(),
    val screenshotUrl: String? = null,
    val screenshotCaption: String = "עדיין אין צילום מ־Fusion",
    val questions: List<RemoteQuestion> = emptyList()
)

class RelayClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private val mutable = MutableStateFlow(RemoteState())
    val state: StateFlow<RemoteState> = mutable

    fun connect(url: String, pairingCode: String) {
        if (!url.startsWith("wss://")) {
            mutable.value = mutable.value.copy(connection = "נדרש חיבור wss מאובטח")
            return
        }
        mutable.value = mutable.value.copy(connection = "מתחבר…")
        socket?.cancel()
        socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(buildJsonObject {
                    put("type", "authenticate"); put("role", "mobile"); put("pairingCode", pairingCode)
                }.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = receive(text)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                mutable.value = mutable.value.copy(connection = "מנותק")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                mutable.value = mutable.value.copy(connection = "שגיאת חיבור: ${t.message ?: "לא ידועה"}")
            }
        })
    }

    fun sendChat(text: String) {
        val clean = text.trim().take(4000)
        if (clean.isEmpty() || !mutable.value.paired) return
        socket?.send(buildJsonObject { put("type", "chat.command"); put("text", clean) }.toString())
        mutable.value = mutable.value.copy(messages = mutable.value.messages + ChatMessage("local-${System.nanoTime()}", "user", clean))
    }

    fun stopBuild() { socket?.send("{\"type\":\"build.stop\"}") }

    fun submitAnswers(answers: Map<String, String>) {
        if (answers.size != mutable.value.questions.size) return
        socket?.send(buildJsonObject {
            put("type", "questionnaire.answer")
            put("answers", buildJsonObject { answers.forEach { (key, value) -> put(key, value) } })
        }.toString())
        mutable.value = mutable.value.copy(questions = emptyList())
    }

    private fun receive(raw: String) {
        runCatching {
            val o = json.parseToJsonElement(raw).jsonObject
            when (o["type"]?.jsonPrimitive?.content) {
                "authenticated" -> mutable.value = mutable.value.copy(connection = "מחובר ל־Fusion", paired = true)
                "chat.message" -> mutable.value = mutable.value.copy(messages = mutable.value.messages + ChatMessage(
                    o["id"]?.jsonPrimitive?.content ?: "remote-${System.nanoTime()}", "assistant", o["text"]?.jsonPrimitive?.content.orEmpty()
                ))
                "build.plan" -> {
                    val titles = o["steps"]?.jsonPrimitive?.content.orEmpty().split("|").filter(String::isNotBlank)
                    mutable.value = mutable.value.copy(steps = titles.mapIndexed { i, title -> BuildStep(i, title, "waiting") })
                }
                "build.step" -> {
                    val index = o["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    val status = o["status"]?.jsonPrimitive?.content ?: "running"
                    mutable.value = mutable.value.copy(steps = mutable.value.steps.map { if (it.index == index) it.copy(state = status) else it })
                }
                "fusion.screenshot" -> mutable.value = mutable.value.copy(
                    screenshotUrl = o["url"]?.jsonPrimitive?.content,
                    screenshotCaption = o["caption"]?.jsonPrimitive?.content ?: "צילום חדש מ־Fusion"
                )
                "questionnaire" -> {
                    val questions = o["questions"]?.jsonArray?.map { element ->
                        val q = element.jsonObject
                        RemoteQuestion(
                            q["id"]?.jsonPrimitive?.content.orEmpty(),
                            q["text"]?.jsonPrimitive?.content.orEmpty(),
                            q["options"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                        )
                    }.orEmpty()
                    mutable.value = mutable.value.copy(questions = questions)
                }
                "error" -> mutable.value = mutable.value.copy(connection = o["message"]?.jsonPrimitive?.content ?: "שגיאה")
            }
        }.onFailure { mutable.value = mutable.value.copy(connection = "התקבל מידע לא תקין") }
    }
}
