/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay

import org.json.JSONObject
import java.util.UUID

/** A per-peer Lamport revision. Concurrent removal wins over creation. */
data class HandoverSyncState(val revision: Long, val origin: String, val enabled: Boolean) : Comparable<HandoverSyncState> {
    init {
        require(revision in 1 until Long.MAX_VALUE) { "Invalid handover revision" }
        require(origin.length == 36 && UUID.fromString(origin).toString() == origin) { "Invalid handover origin" }
    }

    override fun compareTo(other: HandoverSyncState): Int = compareValuesBy(
        this, other, { it.revision }, { !it.enabled }, { it.origin },
    )

    fun encode(): String = JSONObject().put("version", 1).put("revision", revision)
        .put("origin", origin).put("enabled", enabled).toString()

    companion object {
        fun decode(encoded: String): HandoverSyncState {
            val json = JSONObject(encoded)
            require(json.length() == 4 && json.get("version") == 1) { "Unsupported handover setup message" }
            val revision = json.get("revision")
            require(revision is Long || revision is Int) { "Invalid handover revision" }
            val enabled = json.get("enabled")
            require(enabled is Boolean) { "Invalid handover setting" }
            return HandoverSyncState((revision as Number).toLong(), json.getString("origin"), enabled)
        }
    }
}
