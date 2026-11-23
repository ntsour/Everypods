/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.utils.peer

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Owns the [PeerLeV2Session] of each phone to phone LE link: ticks them once a second, and when one ends
 * (handshake or heartbeat failure) clears its state and starts over while the LE link stays up. The central
 * waits [retryDelayMs] before a new HELLO; the peripheral is ready for one at once.
 */
class PeerLeV2Links(
    private val scheduler: ScheduledExecutorService,
    private val create: (mac: String, central: Boolean, onEnded: (String) -> Unit) -> PeerLeV2Session,
    /** The V2 state of [mac] (readiness, snapshot) must be forgotten: its session ended or the link went down. */
    private val onSessionGone: (mac: String) -> Unit,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
    private val tickMs: Long = 1_000L,
) {
    private class Entry(val central: Boolean, val epoch: Long) { @Volatile var session: PeerLeV2Session? = null }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val epochs = ConcurrentHashMap<String, Entry>()
    private var counter = 0L
    private val ticker by lazy { scheduler.scheduleWithFixedDelay({ tickAll() }, tickMs, tickMs, TimeUnit.MILLISECONDS) }

    /** The LE link to [mac] is ready and kept: start (or restart) its V2 session. */
    @Synchronized fun linkUp(mac: String, central: Boolean) {
        val key = mac.uppercase()
        epochs[key]?.session?.end()
        val entry = Entry(central, ++counter)
        epochs[key] = entry
        entries.remove(key)
        ticker
        begin(key, entry)
    }

    /** The LE link to [mac] ended (only if it is the [central] side that was registered); true if one was. */
    @Synchronized fun linkDown(mac: String, central: Boolean? = null): Boolean {
        val key = mac.uppercase()
        val entry = epochs[key] ?: return false
        if (central != null && entry.central != central) return false
        epochs.remove(key)
        entries.remove(key)?.session?.end()
        entry.session?.end()
        onSessionGone(key)
        return true
    }

    fun onRecord(mac: String, payload: ByteArray) { entries[mac.uppercase()]?.session?.onRecord(payload) }

    fun isReady(mac: String): Boolean = entries[mac.uppercase()]?.session?.isReady == true

    fun sendFrame(mac: String, frame: PeerV2Frame.Frame): Boolean = entries[mac.uppercase()]?.session?.sendFrame(frame) == true

    @Synchronized fun stopAll() {
        val macs = epochs.keys.toList()
        macs.forEach { linkDown(it) }
    }

    @Synchronized private fun begin(key: String, entry: Entry) {
        if (epochs[key] !== entry) return
        lateinit var session: PeerLeV2Session
        session = create(key, entry.central) { reason -> scheduler.execute { ended(key, entry, session, reason) } }
        entry.session = session
        entries[key] = entry
        session.start()
    }

    @Synchronized private fun ended(key: String, entry: Entry, session: PeerLeV2Session, reason: String) {
        if (epochs[key] !== entry || entry.session !== session) return
        entries.remove(key, entry)
        onSessionGone(key)
        if (entry.central) {
            scheduler.schedule({ synchronized(this) { begin(key, entry) } }, retryDelayMs, TimeUnit.MILLISECONDS)
        } else {
            begin(key, entry)
        }
    }

    private fun tickAll() {
        entries.values.toList().forEach { runCatching { it.session?.tick() } }
    }

    companion object { const val RETRY_DELAY_MS = 60_000L }
}
