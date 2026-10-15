package org.empco.securitycam.cloud

import okhttp3.OkHttpClient
import org.empco.securitycam.system.NetworkPicker
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.MINUTES)
            .pingInterval(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // SIM first, Wi-Fi as fallback.
            .socketFactory(NetworkPicker.socketFactory)
            .dns(NetworkPicker.dns)
            .addInterceptor(NetworkPicker.fallbackInterceptor)
            .build()
    }

    /** https://host -> wss://host */
    fun toWebSocketBase(httpBase: String): String {
        val b = httpBase.trim().trimEnd('/')
        return when {
            b.startsWith("https://") -> "wss://" + b.removePrefix("https://")
            b.startsWith("http://") -> "ws://" + b.removePrefix("http://")
            else -> "wss://$b"
        }
    }

    fun normalizeBase(httpBase: String): String {
        val b = httpBase.trim().trimEnd('/')
        return if (b.startsWith("http://") || b.startsWith("https://")) b else "https://$b"
    }
}
