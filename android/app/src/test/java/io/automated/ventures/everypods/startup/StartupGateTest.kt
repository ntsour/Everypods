package io.automated.ventures.everypods.startup

import io.automated.ventures.everypods.startup.StartupGate.BatteryPrompt
import io.automated.ventures.everypods.startup.StartupGate.BindDecision
import io.automated.ventures.everypods.startup.StartupGate.Placeholder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupGateTest {

    @Test
    fun bindDecision_skipsWhenBound() {
        assertEquals(BindDecision.SKIP_ALREADY_BOUND, StartupGate.decideBind(alreadyBound = true, hasBtPerms = true))
        assertEquals(BindDecision.SKIP_ALREADY_BOUND, StartupGate.decideBind(alreadyBound = true, hasBtPerms = false))
    }

    @Test
    fun bindDecision_skipsWithoutBtPerms_soColdInstallNeverAttemptsADroppedStart() {
        assertEquals(BindDecision.SKIP_MISSING_BT_PERMS, StartupGate.decideBind(alreadyBound = false, hasBtPerms = false))
    }

    @Test
    fun bindDecision_bindsWhenPermsAndUnbound() {
        assertEquals(BindDecision.START_AND_BIND, StartupGate.decideBind(alreadyBound = false, hasBtPerms = true))
    }

    @Test
    fun startServiceError_nullComponentIsAFailureEvenWithoutException() {
        val err = StartupGate.startServiceError(returnedComponent = false, error = null)
        assertNotNull(err)
        assertTrue(err!!.contains("null"))
    }

    @Test
    fun startServiceError_exceptionMessageWins() {
        assertEquals("boom", StartupGate.startServiceError(true, SecurityException("boom")))
        assertEquals("IllegalStateException", StartupGate.startServiceError(true, IllegalStateException()))
    }

    @Test
    fun startServiceError_okWhenComponentReturned() {
        assertNull(StartupGate.startServiceError(returnedComponent = true, error = null))
    }

    @Test
    fun bindServiceError_falseIsFailure() {
        assertEquals("bindService returned false", StartupGate.bindServiceError(false, null))
        assertNull(StartupGate.bindServiceError(true, null))
    }

    @Test
    fun retry_doesNotConsumeAttemptsWhileWaitingForPerms() {
        // User sits on the permission screen: loop must keep waiting, not exhaust.
        assertFalse(StartupGate.shouldAttemptRetry(bound = false, hasBtPerms = false, attemptsUsed = 0))
        assertFalse(StartupGate.retriesExhausted(bound = false, hasBtPerms = false, attemptsUsed = 0))
        // Perms granted late (after the old 12 s window): first attempt still allowed.
        assertTrue(StartupGate.shouldAttemptRetry(bound = false, hasBtPerms = true, attemptsUsed = 0))
    }

    @Test
    fun retry_stopsAfterMaxAndReportsExhausted() {
        val max = StartupGate.MAX_BIND_ATTEMPTS
        assertTrue(StartupGate.shouldAttemptRetry(false, true, max - 1))
        assertFalse(StartupGate.shouldAttemptRetry(false, true, max))
        assertTrue(StartupGate.retriesExhausted(false, true, max))
        assertFalse(StartupGate.retriesExhausted(true, true, max))
    }

    @Test
    fun placeholder_missingPermsIsExplicitNotInfiniteStarting() {
        assertEquals(Placeholder.NEEDS_BT_PERMISSION, StartupGate.placeholder(hasBtPerms = false, lastBindError = null))
        assertEquals(Placeholder.NEEDS_BT_PERMISSION, StartupGate.placeholder(hasBtPerms = false, lastBindError = "x"))
        assertEquals(Placeholder.ERROR_WITH_RETRY, StartupGate.placeholder(hasBtPerms = true, lastBindError = "x"))
        assertEquals(Placeholder.STARTING, StartupGate.placeholder(hasBtPerms = true, lastBindError = null))
    }

    @Test
    fun needsOnboarding() {
        assertTrue(StartupGate.needsOnboarding(permissionsCompletedFlag = false, hasBtPerms = true))
        assertTrue(StartupGate.needsOnboarding(permissionsCompletedFlag = true, hasBtPerms = false))
        assertFalse(StartupGate.needsOnboarding(permissionsCompletedFlag = true, hasBtPerms = true))
    }

    @Test
    fun batteryPrompt_showsInFirstSessionOnceOnboardingCompletes() {
        assertEquals(BatteryPrompt.SKIP_ONBOARDING, StartupGate.batteryPrompt(true, onboardingPending = true, alreadyExempt = false, alreadyPrompted = false))
        // After Continue flips onboarding state in the same session, the prompt shows
        // (previously needsPermissions was remember{}'d true for the whole first session).
        assertEquals(BatteryPrompt.SHOW, StartupGate.batteryPrompt(true, onboardingPending = false, alreadyExempt = false, alreadyPrompted = false))
    }

    @Test
    fun batteryPrompt_skips() {
        assertEquals(BatteryPrompt.SKIP_NOT_BOUND, StartupGate.batteryPrompt(false, false, false, false))
        assertEquals(BatteryPrompt.SKIP_ALREADY_EXEMPT, StartupGate.batteryPrompt(true, false, true, false))
        assertEquals(BatteryPrompt.SKIP_ALREADY_PROMPTED, StartupGate.batteryPrompt(true, false, false, true))
    }
}
