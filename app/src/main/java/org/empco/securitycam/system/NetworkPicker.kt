package org.empco.securitycam.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Log
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Wi-Fi-first networking for the cloud link: uploads/live view use Wi-Fi whenever it's up and
 * validated (the system default route, so this picker simply doesn't override it), saving
 * prepaid/metered SIM data. Mobile data is kept alive in the background purely as backup — it
 * takes over only when Wi-Fi is missing, unvalidated, or just failed (for 5 minutes).
 */
object NetworkPicker {
    private const val TAG = "NetworkPicker"
    @Volatile private var cellular: Network? = null
    @Volatile private var cellularValidated = false
    @Volatile private var cellularBadUntil = 0L
    @Volatile private var wifi: Network? = null
    @Volatile private var wifiValidated = false
    @Volatile private var wifiBadUntil = 0L
    private var cm: ConnectivityManager? = null

    fun init(context: Context) {
        val c = context.getSystemService(ConnectivityManager::class.java) ?: return
        cm = c
        val cellReq = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            c.requestNetwork(cellReq, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { cellular = network; Log.i(TAG, "mobile data available (backup)") }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (network == cellular) cellularValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }
                override fun onLost(network: Network) {
                    if (network == cellular) { cellular = null; cellularValidated = false; Log.i(TAG, "mobile data lost") }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "cannot request mobile data: ${e.message}")
        }
        // Tracked only to know whether Wi-Fi is currently good — traffic itself isn't explicitly
        // bound to it (unlike cellular above); when it's good, preferred() just defers to the
        // system default, which Android routes over Wi-Fi whenever both are validated.
        val wifiReq = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            c.requestNetwork(wifiReq, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { wifi = network; Log.i(TAG, "wifi available") }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (network == wifi) wifiValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }
                override fun onLost(network: Network) {
                    if (network == wifi) { wifi = null; wifiValidated = false; Log.i(TAG, "wifi lost") }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "cannot request wifi: ${e.message}")
        }
    }

    private fun wifiOk(): Boolean = wifiValidated && SystemClock.elapsedRealtime() >= wifiBadUntil
    private fun cellOk(): Boolean = cellular != null && cellularValidated && SystemClock.elapsedRealtime() >= cellularBadUntil

    /** The mobile network to force traffic onto right now (Wi-Fi is down/bad), or null to use the
     *  system default (Wi-Fi, when it's good). */
    private fun preferred(): Network? {
        if (wifiOk()) return null
        return cellular.takeIf { cellOk() }
    }

    /** One entry per transport that's actually connected right now (null when it isn't connected
     *  at all), plus which single one traffic is really going over. */
    data class Status(val wifi: String?, val wifiActive: Boolean, val cellular: String?, val cellularActive: Boolean)

    fun status(context: Context): Status {
        val wifiActive = wifiOk()
        return Status(
            wifi = if (wifi != null) wifiLabel(context) else null,
            wifiActive = wifiActive,
            cellular = if (cellular != null) carrierLabel(context) else null,
            cellularActive = !wifiActive && cellOk(),
        )
    }

    /** "mobile data (Carrier Name)" — the operator name needs no runtime permission. */
    private fun carrierLabel(context: Context): String = try {
        val name = context.getSystemService(TelephonyManager::class.java)?.networkOperatorName?.trim()
        if (name.isNullOrBlank()) "mobile data" else "mobile data ($name)"
    } catch (e: Exception) {
        "mobile data"
    }

    /** "WiFi (SSID)" when resolvable. Android only reveals the real SSID with a location
     *  permission/service grant this app deliberately doesn't request — falls back to "WiFi". */
    private fun wifiLabel(context: Context): String = try {
        val ssid = context.applicationContext.getSystemService(WifiManager::class.java)
            ?.connectionInfo?.ssid?.trim('"')
        if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") "WiFi" else "WiFi ($ssid)"
    } catch (e: Exception) {
        "WiFi"
    }

    val socketFactory: SocketFactory = object : SocketFactory() {
        private fun f(): SocketFactory = preferred()?.socketFactory ?: getDefault()
        override fun createSocket(): Socket = f().createSocket()
        override fun createSocket(host: String?, port: Int): Socket = f().createSocket(host, port)
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            f().createSocket(host, port, localHost, localPort)
        override fun createSocket(host: InetAddress?, port: Int): Socket = f().createSocket(host, port)
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            f().createSocket(address, port, localAddress, localPort)
    }

    val dns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val n = preferred()
            if (n != null) {
                try { return n.getAllByName(hostname).toList() } catch (e: Exception) { markCellularBad("dns ${e.message}") }
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    /** A failure on whichever side is currently active marks that side bad for 5 minutes, so the
     *  next attempt uses the other one — symmetric in both directions. */
    val fallbackInterceptor = Interceptor { chain ->
        val usingCell = preferred() != null
        try {
            chain.proceed(chain.request())
        } catch (e: IOException) {
            if (usingCell) markCellularBad(e.message ?: "io") else markWifiBad(e.message ?: "io")
            throw e
        }
    }

    private fun markCellularBad(why: String) {
        cellularBadUntil = SystemClock.elapsedRealtime() + 5 * 60_000L
        Log.w(TAG, "mobile data failed ($why); using Wi-Fi/default for 5 min")
    }

    private fun markWifiBad(why: String) {
        wifiBadUntil = SystemClock.elapsedRealtime() + 5 * 60_000L
        Log.w(TAG, "wifi failed ($why); using mobile data for 5 min")
    }

    @Suppress("unused")
    private fun unusedResponse(r: Response) = r
}
