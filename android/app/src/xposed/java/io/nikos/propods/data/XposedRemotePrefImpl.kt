package io.nikos.propods.data

import androidx.core.content.edit
import io.nikos.propods.utils.XposedServiceHolder

class XposedRemotePrefImpl: XposedRemotePref {
    override fun isAvailable(): Boolean {
        return XposedServiceHolder.service != null
    }

    override fun getBoolean(key: String, def: Boolean): Boolean {
        val s = XposedServiceHolder.service ?: return def
        return s.getRemotePreferences("io.nikos.propods").getBoolean(key, def)
    }

    override fun putBoolean(key: String, value: Boolean) {
        val s = XposedServiceHolder.service ?: return
        s.getRemotePreferences("io.nikos.propods")
            .edit { putBoolean(key, value) }
    }
}
