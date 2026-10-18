package io.automated.ventures.everypods.relay

/**
 * Picks the AirPods output for relay playback; never falls back to the phone speaker, wired
 * output or a different Bluetooth device. Pure so it is unit-testable; the app maps
 * AudioDeviceInfo to [Candidate].
 */
object OutputMatcher {
    enum class Kind { BLUETOOTH_A2DP, BLE_AUDIO, SPEAKER, WIRED, OTHER }

    data class Candidate(val id: Int, val address: String, val name: String, val kind: Kind) {
        val isBluetoothOutput get() = kind == Kind.BLUETOOTH_A2DP || kind == Kind.BLE_AUDIO
    }

    sealed class Result {
        data class Match(val candidate: Candidate, val reason: String) : Result()
        data class Refused(val reason: String) : Result()
    }

    private val APPLE_NAME = Regex("airpods|beats|powerbeats", RegexOption.IGNORE_CASE)

    fun isAppleAudio(name: String) = APPLE_NAME.containsMatchIn(name)

    fun choose(candidates: List<Candidate>, managedAddress: String?, pickedAddress: String?): Result {
        val bt = candidates.filter { it.isBluetoothOutput && it.address.isNotBlank() }
            .distinctBy { it.address.uppercase() } // A2DP and LE may both list one device; keep first
        fun byAddr(a: String?) = a?.takeIf { it.isNotBlank() }?.let { want -> bt.firstOrNull { it.address.equals(want, true) } }
        if (!managedAddress.isNullOrBlank()) {
            return byAddr(managedAddress)?.let { Result.Match(it, "managed address") }
                ?: Result.Refused("managed AirPods $managedAddress not connected as audio output")
        }
        if (!pickedAddress.isNullOrBlank()) {
            return byAddr(pickedAddress)?.let { Result.Match(it, "dev picker") }
                ?: Result.Refused("picked output $pickedAddress not connected")
        }
        val apple = bt.filter { isAppleAudio(it.name) }
        return when {
            apple.size == 1 -> Result.Match(apple[0], "only Apple audio output")
            apple.size > 1 -> Result.Refused("several Apple outputs connected; pick one in the dev picker")
            bt.isEmpty() -> Result.Refused("no Bluetooth audio output connected (speaker fallback refused)")
            else -> Result.Refused("no AirPods connected; pick an output in the dev picker")
        }
    }

    /** True only when the actual routed device is the chosen one. */
    fun routeOk(routed: Candidate?, chosen: Candidate): Boolean =
        routed != null && routed.isBluetoothOutput && routed.address.equals(chosen.address, true)
}
