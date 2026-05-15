package io.nikos.propods.data

interface XposedRemotePref {
    fun isAvailable(): Boolean

    fun getBoolean(key: String, def: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}
