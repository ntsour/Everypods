/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.automation

import android.content.Context
import org.json.JSONObject

/**
 * Who started the current heart-rate session. Persisted (small, no BPM, no transport
 * credentials) so ownership survives process death; it is never used to restart HR.
 */
data class HrOwnerRecord(val source: HeartRateSource, val bootId: String?, val startedAt: Long) {
    companion object {
        fun encode(r: HrOwnerRecord?): String? = r?.let {
            JSONObject().put("source", it.source.name).put("boot", it.bootId ?: "").put("at", it.startedAt).toString()
        }

        fun decode(raw: String?): HrOwnerRecord? = runCatching {
            val o = JSONObject(raw!!)
            HrOwnerRecord(HeartRateSource.valueOf(o.getString("source")), o.optString("boot").ifEmpty { null }, o.optLong("at"))
        }.getOrNull()

        /**
         * The owner of a session that is live right now. A record from another boot, or one
         * left over when nothing is running, owns nothing.
         */
        fun liveOwner(r: HrOwnerRecord?, active: Boolean, bootId: String): HeartRateSource? =
            r?.takeIf { active && it.bootId == bootId }?.source
    }
}

object HeartRateOwnerStore {
    private const val KEY = "hr_owner"

    fun read(ctx: Context): HrOwnerRecord? =
        HrOwnerRecord.decode(GymAutomationRepository.prefs(ctx).getString(KEY, null))

    fun write(ctx: Context, r: HrOwnerRecord?) {
        val e = GymAutomationRepository.prefs(ctx).edit()
        if (r == null) e.remove(KEY) else e.putString(KEY, HrOwnerRecord.encode(r))
        e.commit()
    }
}
