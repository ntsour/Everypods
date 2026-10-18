package io.automated.ventures.everypods.relaydev

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.Inet4Address
import java.net.InetAddress

/**
 * DEV ONLY (P2). Temporary manual peer setup: typed receiver IPv4 + pre-shared test key.
 * Replaced by paired-peer discovery and pinned identity keys in P3 — delete this file then.
 */
object RelayDevPrefs {
    private const val NAME = "relaydev"
    const val TCP_PORT = 47480
    const val UDP_PORT = 47481
    const val DEFAULT_TEST_KEY = "everypods-relay-dev"

    private fun p(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    fun host(c: Context) = p(c).getString("host", "") ?: ""
    fun setHost(c: Context, v: String) = p(c).edit().putString("host", v.trim()).apply()
    fun testKey(c: Context) = p(c).getString("test_key", DEFAULT_TEST_KEY) ?: DEFAULT_TEST_KEY
    fun setTestKey(c: Context, v: String) = p(c).edit().putString("test_key", v).apply()
    /** PM note 2: AirPods address picked in the dev picker (relay-only has no managed address). */
    fun outputAddress(c: Context) = p(c).getString("relaydev_output_address", null)
    fun setOutputAddress(c: Context, v: String?) = p(c).edit().putString("relaydev_output_address", v).apply()
    fun muteSource(c: Context) = p(c).getBoolean("mute_source", true)
    fun setMuteSource(c: Context, v: Boolean) = p(c).edit().putBoolean("mute_source", v).apply()
}

/** Status lines shown in RelayDevActivity. */
object RelayDevStatus {
    val source = MutableStateFlow("Source idle")
    val receiver = MutableStateFlow("Receiver idle")
}

internal fun nowMicros(): Long = SystemClock.elapsedRealtimeNanos() / 1000

internal fun localIpv4(): List<String> = try {
    java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        .flatMap { i -> i.inetAddresses.toList().filterIsInstance<Inet4Address>().map { "${it.hostAddress} (${i.name})" } }
} catch (_: Exception) { emptyList() }

/** Wi-Fi network whose subnet contains [address] (hotspots without internet aren't default). */
internal fun wifiNetworkFor(context: Context, address: InetAddress): Network? {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    @Suppress("DEPRECATION")
    for (n in cm.allNetworks) {
        val caps = cm.getNetworkCapabilities(n) ?: continue
        val links = cm.getLinkProperties(n) ?: continue
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
        if (links.routes.any { !it.isDefaultRoute && it.destination.contains(address) }) return n
    }
    return null
}

internal fun requirePrivateIpv4(host: String): InetAddress {
    val a = InetAddress.getByName(host)
    require(a is Inet4Address && a.isSiteLocalAddress) { "Enter the receiver's private Wi-Fi IPv4 address" }
    return a
}

/** Battery baseline for P5 (PM note 4): logs drain in mAh and % over the session. */
internal class BatteryMeter(private val context: Context, private val tag: String) {
    private val bm = context.getSystemService(BatteryManager::class.java)
    private val startUah = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
    private val startPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    private val startMs = SystemClock.elapsedRealtime()

    fun line(event: String): String {
        val uah = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val min = (SystemClock.elapsedRealtime() - startMs) / 60000.0
        val drainedMah = (startUah - uah) / 1000.0
        val charging = bm.isCharging
        return "RelayBattery $event minutes=${"%.1f".format(min)} startPct=$startPct nowPct=$pct " +
            "drainedMah=${"%.1f".format(drainedMah)} mahPer10min=${if (min > 0.5) "%.1f".format(drainedMah / min * 10) else "n/a"} charging=$charging"
    }

    fun log(event: String) = Log.i(tag, line(event))
}
