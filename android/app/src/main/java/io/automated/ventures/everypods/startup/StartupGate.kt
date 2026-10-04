package io.automated.ventures.everypods.startup

/**
 * Pure (Android-free) decisions for the first-launch / cold-install startup path so the
 * gating rules are unit-testable. All runtime logging for these gates goes to the
 * [TAG] log tag ("Startup") so a single `adb logcat -s Startup` shows the whole flow.
 *
 * Background (Pixel, Play 1.0.0/vc107, 2026-10-04): AirPodsService is declared with
 * android:permission="android.permission.BLUETOOTH_CONNECT". For the app's own
 * start/bind calls the uid check passes, but ActivityManager then checks the
 * permission's app-op; before the runtime grant that op is not MODE_ALLOWED, so
 * retrieveServiceLocked returns null => startForegroundService() returns null and
 * bindService() returns false WITHOUT throwing. A start/bind attempted before the
 * grant is therefore silently dropped, and unless something retries after the grant
 * the UI stays on "Starting…" until the activity is recreated.
 */
object StartupGate {
    const val TAG = "Startup"

    /** Max start+bind attempts (with BT perms present) before surfacing the error UI. */
    const val MAX_BIND_ATTEMPTS = 8
    const val BIND_RETRY_INTERVAL_MS = 1_500L

    enum class BindDecision { SKIP_ALREADY_BOUND, SKIP_MISSING_BT_PERMS, START_AND_BIND }

    fun decideBind(alreadyBound: Boolean, hasBtPerms: Boolean): BindDecision = when {
        alreadyBound -> BindDecision.SKIP_ALREADY_BOUND
        !hasBtPerms -> BindDecision.SKIP_MISSING_BT_PERMS
        else -> BindDecision.START_AND_BIND
    }

    /**
     * Interpret the outcome of Context.startForegroundService. A null ComponentName
     * (no exception) means the platform silently refused the start — e.g. the
     * BLUETOOTH_CONNECT app-op denial described above. Returns an error string or null.
     */
    fun startServiceError(returnedComponent: Boolean, error: Throwable?): String? = when {
        error != null -> error.message ?: error.javaClass.simpleName
        !returnedComponent -> "startForegroundService returned null (service start refused)"
        else -> null
    }

    fun bindServiceError(bound: Boolean, error: Throwable?): String? = when {
        error != null -> error.message ?: error.javaClass.simpleName
        !bound -> "bindService returned false"
        else -> null
    }

    /**
     * Whether the periodic retry loop should attempt start+bind now. Attempts are only
     * consumed while BT perms are granted, so a user who sits on the permission screen
     * for longer than the retry window still gets retries after granting (the #34 loop
     * burned its 8 attempts in the first 12 s regardless of perms).
     */
    fun shouldAttemptRetry(bound: Boolean, hasBtPerms: Boolean, attemptsUsed: Int): Boolean =
        !bound && hasBtPerms && attemptsUsed < MAX_BIND_ATTEMPTS

    fun retriesExhausted(bound: Boolean, hasBtPerms: Boolean, attemptsUsed: Int): Boolean =
        !bound && hasBtPerms && attemptsUsed >= MAX_BIND_ATTEMPTS

    enum class Placeholder { STARTING, NEEDS_BT_PERMISSION, ERROR_WITH_RETRY }

    /** What the home route shows while the service is not bound. */
    fun placeholder(hasBtPerms: Boolean, lastBindError: String?): Placeholder = when {
        !hasBtPerms -> Placeholder.NEEDS_BT_PERMISSION
        lastBindError != null -> Placeholder.ERROR_WITH_RETRY
        else -> Placeholder.STARTING
    }

    /** Onboarding is needed when never completed or the critical runtime grants are missing. */
    fun needsOnboarding(permissionsCompletedFlag: Boolean, hasBtPerms: Boolean): Boolean =
        !permissionsCompletedFlag || !hasBtPerms

    enum class BatteryPrompt {
        SHOW,
        SKIP_NOT_BOUND,
        SKIP_ONBOARDING,
        SKIP_ALREADY_EXEMPT,
        SKIP_ALREADY_PROMPTED,
    }

    fun batteryPrompt(
        serviceReady: Boolean,
        onboardingPending: Boolean,
        alreadyExempt: Boolean,
        alreadyPrompted: Boolean,
    ): BatteryPrompt = when {
        !serviceReady -> BatteryPrompt.SKIP_NOT_BOUND
        onboardingPending -> BatteryPrompt.SKIP_ONBOARDING
        alreadyExempt -> BatteryPrompt.SKIP_ALREADY_EXEMPT
        alreadyPrompted -> BatteryPrompt.SKIP_ALREADY_PROMPTED
        else -> BatteryPrompt.SHOW
    }
}
