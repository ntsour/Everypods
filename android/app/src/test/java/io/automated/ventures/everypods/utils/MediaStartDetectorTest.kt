package io.automated.ventures.everypods.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MediaStartDetectorTest {
    @Test fun emptyPlaybackListIsVerifiedAndDoesNotConsumeTheStart() {
        val detector = MediaStartDetector()
        assertEquals(MediaStartDecision.VERIFY, detector.observe(true, MediaPlaybackEvidence.UNKNOWN, false))
        assertFalse(detector.active)
        assertEquals(MediaStartDecision.REQUEST_HANDOVER, detector.observe(true, MediaPlaybackEvidence.USER_MEDIA, false))
    }

    @Test fun stopAndImmediateStartProduceANewHandoverWithoutAThreeSecondLockout() {
        val detector = MediaStartDetector()
        detector.observe(true, MediaPlaybackEvidence.USER_MEDIA, false)
        detector.observe(false, MediaPlaybackEvidence.UNKNOWN, false)
        assertEquals(MediaStartDecision.REQUEST_HANDOVER, detector.observe(true, MediaPlaybackEvidence.USER_MEDIA, false))
    }

    @Test fun duplicateAndRedactedCallbacksDoNotRepeatTheHandover() {
        val detector = MediaStartDetector()
        detector.observe(true, MediaPlaybackEvidence.USER_MEDIA, false)
        assertEquals(MediaStartDecision.IGNORE, detector.observe(true, MediaPlaybackEvidence.UNKNOWN, false))
        assertEquals(MediaStartDecision.IGNORE, detector.observe(true, MediaPlaybackEvidence.USER_MEDIA, false))
    }

    @Test fun recognizedMediaCanBecomeMusicActiveAfterItsFirstCallback() {
        val detector = MediaStartDetector()
        assertEquals(MediaStartDecision.VERIFY, detector.observe(false, MediaPlaybackEvidence.USER_MEDIA, false))
        assertEquals(MediaStartDecision.REQUEST_HANDOVER, detector.observe(true, MediaPlaybackEvidence.USER_MEDIA, false))
    }

    @Test fun speechAndSonificationDoNotStartOrConsumeMediaPlayback() {
        val detector = MediaStartDetector()
        assertEquals(MediaStartDecision.IGNORE, detector.observe(true, MediaPlaybackEvidence.OTHER, false))
        assertFalse(detector.active)
        assertEquals(MediaStartDecision.REQUEST_HANDOVER, detector.observe(true, MediaPlaybackEvidence.USER_MEDIA, false))
    }

    @Test fun stoppingRelayDoesNotAutomaticallyReclaimContinuingSourceAudio() {
        val detector = MediaStartDetector()
        assertEquals(MediaStartDecision.IGNORE, detector.observe(true, MediaPlaybackEvidence.USER_MEDIA, true))
        detector.rearm(true)
        assertEquals(MediaStartDecision.IGNORE, detector.observe(true, MediaPlaybackEvidence.USER_MEDIA, false))
        detector.observe(false, MediaPlaybackEvidence.UNKNOWN, false)
        assertEquals(MediaStartDecision.REQUEST_HANDOVER, detector.observe(true, MediaPlaybackEvidence.USER_MEDIA, false))
    }

    @Test fun stoppingRelayWithNoLocalPlaybackAllowsNextStart() {
        val detector = MediaStartDetector()
        detector.observe(true, MediaPlaybackEvidence.USER_MEDIA, true)
        detector.rearm(false)
        assertEquals(MediaStartDecision.REQUEST_HANDOVER, detector.observe(true, MediaPlaybackEvidence.USER_MEDIA, false))
    }

    @Test fun suppressedUnknownAudioNeverStartsADeferredHandover() {
        val detector = MediaStartDetector()
        assertEquals(MediaStartDecision.IGNORE, detector.observe(true, MediaPlaybackEvidence.UNKNOWN, true))
    }
}
