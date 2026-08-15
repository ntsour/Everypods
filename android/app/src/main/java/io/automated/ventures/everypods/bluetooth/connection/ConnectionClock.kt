package io.automated.ventures.everypods.bluetooth.connection

interface ConnectionClock {
    suspend fun delay(ms: Long)
    fun nowMs(): Long
}

object RealConnectionClock : ConnectionClock {
    override suspend fun delay(ms: Long) = kotlinx.coroutines.delay(ms)
    override fun nowMs(): Long = System.currentTimeMillis()
}
