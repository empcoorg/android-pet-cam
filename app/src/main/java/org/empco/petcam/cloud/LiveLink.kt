package org.empco.petcam.cloud

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import kotlin.math.min

/**
 * Persistent WebSocket from the phone to the Cloudflare Worker (`/api/device/ws`).
 * Sends JPEG frames (binary) and status (JSON text); receives viewer counts and commands.
 * Reconnects with exponential backoff while started.
 */
class LiveLink(private val listener: Listener) {
    interface Listener {
        fun onLiveConnected(connected: Boolean)
        fun onViewers(count: Int)
        fun onCommand(cmd: JSONObject)
        /** The Worker rejected the device token (HTTP 401). */
        fun onAuthRejected() {}
    }

    private val handler = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var url = ""
    private var token = ""
    private var stopped = true
    private var backoffMs = 2_000L

    @Volatile var connected = false
        private set

    fun start(httpBase: String, deviceToken: String) {
        stop()
        stopped = false
        url = Http.toWebSocketBase(httpBase) + "/api/device/ws"
        token = deviceToken
        connect()
    }

    fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        ws?.close(1000, "bye")
        ws = null
        connected = false
    }

    private fun connect() {
        if (stopped) return
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        Log.i(TAG, "connecting $url")
        ws = Http.client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== ws) return
                connected = true
                backoffMs = 2_000L
                listener.onLiveConnected(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== ws) return
                try {
                    val obj = JSONObject(text)
                    when (obj.optString("type")) {
                        "viewers" -> listener.onViewers(obj.optInt("count", 0))
                        "cmd" -> listener.onCommand(obj)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "bad message: $text")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== ws) return
                dropped("closed $code $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== ws) return
                if (response?.code == 401) listener.onAuthRejected()
                dropped("failure ${response?.code ?: ""} ${t.message}")
            }
        })
    }

    private fun dropped(why: String) {
        Log.w(TAG, "live link dropped: $why")
        connected = false
        ws = null
        listener.onLiveConnected(false)
        listener.onViewers(0)
        if (stopped) return
        handler.postDelayed({ connect() }, backoffMs)
        backoffMs = min(backoffMs * 2, 60_000L)
    }

    /** Drops frames instead of queueing them when the uplink is slow. */
    fun sendFrame(jpeg: ByteArray): Boolean {
        val s = ws ?: return false
        if (!connected || s.queueSize() > 400_000L) return false
        return s.send(jpeg.toByteString())
    }

    fun sendText(json: String): Boolean {
        val s = ws ?: return false
        if (!connected) return false
        return s.send(json)
    }

    companion object { private const val TAG = "LiveLink" }
}
