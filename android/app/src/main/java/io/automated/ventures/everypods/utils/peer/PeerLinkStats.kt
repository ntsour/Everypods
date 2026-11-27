package io.automated.ventures.everypods.utils.peer

/**
 * Per-peer link counters, logged once an hour as one STATS line per peer (phase 0 of the quiet peer links plan,
 * Everypods-private docs/peer-link-quiet-redesign.md). The keys match the Windows and Mac lines, so the traces
 * measure idle traffic, dials and link churn directly:
 *
 *     STATS peer=98597A1041E9 window_s=3600 legacy_in=180 legacy_in_bytes=720 ... v2_pings=360 ... le_pings=0
 *     STATS window_s=3600
 *
 * Times are `SystemClock.elapsedRealtime()` milliseconds passed in by the caller, so this stays a plain class.
 */
class PeerLinkStats(startedAtMs: Long) {
    /** LEGACY and V2: the RFCOMM links. LE: V2 frames over the Bluetooth LE links (Mac, phone to phone). */
    enum class Channel(val key: String) { LEGACY("legacy"), V2("v2"), LE("le") }

    class Counters {
        var framesIn = 0L; var bytesIn = 0L; var framesOut = 0L; var bytesOut = 0L
        var dials = 0L; var dialFailures = 0L; var up = 0L; var down = 0L; var pings = 0L
        var lifeMinMs = -1L; var lifeMaxMs = -1L; var lifeTotalMs = 0L
        val empty: Boolean get() = framesIn == 0L && framesOut == 0L && dials == 0L && up == 0L && down == 0L && pings == 0L
    }

    private val peers = sortedMapOf<String, MutableMap<Channel, Counters>>()
    private var sinceMs = startedAtMs

    private fun counters(peer: String, channel: Channel): Counters =
        peers.getOrPut(peerKey(peer)) { mutableMapOf() }.getOrPut(channel) { Counters() }

    @Synchronized fun frameIn(peer: String, channel: Channel, bytes: Int) =
        counters(peer, channel).run { framesIn++; bytesIn += bytes }
    @Synchronized fun frameOut(peer: String, channel: Channel, bytes: Int) =
        counters(peer, channel).run { framesOut++; bytesOut += bytes }
    @Synchronized fun dial(peer: String, channel: Channel, ok: Boolean) =
        counters(peer, channel).run { dials++; if (!ok) dialFailures++ }
    @Synchronized fun linkUp(peer: String, channel: Channel) { counters(peer, channel).up++ }
    @Synchronized fun linkDown(peer: String, channel: Channel, lifetimeMs: Long) = counters(peer, channel).run {
        val ms = lifetimeMs.coerceAtLeast(0L)
        down++
        lifeTotalMs += ms
        if (lifeMinMs < 0 || ms < lifeMinMs) lifeMinMs = ms
        if (ms > lifeMaxMs) lifeMaxMs = ms
    }
    @Synchronized fun ping(peer: String, channel: Channel) { counters(peer, channel).pings++ }

    /** The lines for the window since the last report once [REPORT_INTERVAL_MS] has passed, else null. */
    @Synchronized fun takeIfDue(nowMs: Long): List<String>? =
        if (nowMs - sinceMs >= REPORT_INTERVAL_MS) take(nowMs) else null

    /** The lines for the window since the last report, then a fresh window. Idle peers are left out. */
    @Synchronized fun take(nowMs: Long): List<String> {
        val windowSeconds = ((nowMs - sinceMs) / 1000).coerceAtLeast(0L)
        val lines = peers.filterValues { channels -> channels.values.any { !it.empty } }
            .map { (peer, channels) -> format(peer, windowSeconds, channels) } + "STATS window_s=$windowSeconds"
        peers.clear()
        sinceMs = nowMs
        return lines
    }

    companion object {
        const val REPORT_INTERVAL_MS = 3_600_000L

        /** "98:59:7a:10:41:e9" -> "98597A1041E9", as the Windows lines print addresses. */
        fun peerKey(address: String): String = address.replace(":", "").uppercase()

        /** One line for one peer. Pure, so it is unit-tested. */
        fun format(peer: String, windowSeconds: Long, channels: Map<Channel, Counters>): String = buildString {
            append("STATS peer=").append(peerKey(peer)).append(" window_s=").append(windowSeconds)
            for (channel in Channel.values()) {
                val c = channels[channel] ?: Counters()
                val n = channel.key
                append(" ${n}_in=${c.framesIn} ${n}_in_bytes=${c.bytesIn} ${n}_out=${c.framesOut} ${n}_out_bytes=${c.bytesOut}")
                append(" ${n}_dials=${c.dials} ${n}_dial_failures=${c.dialFailures} ${n}_up=${c.up} ${n}_down=${c.down}")
                append(" ${n}_pings=${c.pings}")
                if (c.down > 0) {
                    append(" ${n}_life_min_s=${c.lifeMinMs / 1000} ${n}_life_avg_s=${c.lifeTotalMs / 1000 / c.down}")
                    append(" ${n}_life_max_s=${c.lifeMaxMs / 1000}")
                }
            }
        }
    }
}
