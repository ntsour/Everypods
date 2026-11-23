package io.automated.ventures.everypods.utils

import io.automated.ventures.everypods.utils.peer.GrantDecision
import io.automated.ventures.everypods.utils.peer.GrantDenial
import io.automated.ventures.everypods.utils.peer.HandoverIntent
import io.automated.ventures.everypods.utils.peer.LegacyHandoverRequest
import io.automated.ventures.everypods.utils.peer.LegacyHandoverRequest.Action
import io.automated.ventures.everypods.utils.peer.LegacyHandoverRequest.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyHandoverRequestTest {
    @Test fun requestDisconnectIsMedia() {
        assertEquals(HandoverIntent.MEDIA, LegacyHandoverRequest.intentFor(Kind.DISCONNECT))
    }

    @Test fun requestHandoverIsCall() {
        assertEquals(HandoverIntent.CALL, LegacyHandoverRequest.intentFor(Kind.HANDOVER))
    }

    @Test fun resolveAsksPolicyWithMappedIntent() {
        val asked = mutableListOf<HandoverIntent>()
        LegacyHandoverRequest.resolve(Kind.DISCONNECT) { asked += it; GrantDecision.Grant }
        LegacyHandoverRequest.resolve(Kind.HANDOVER) { asked += it; GrantDecision.Grant }
        assertEquals(listOf(HandoverIntent.MEDIA, HandoverIntent.CALL), asked)
    }

    @Test fun denyRepliesStillHeldWithoutYield() {
        val action = LegacyHandoverRequest.resolve(Kind.HANDOVER) { GrantDecision.Deny(GrantDenial.CALL_ACTIVE) }
        assertEquals(Action.ReplyStillHeld(GrantDenial.CALL_ACTIVE), action)
    }

    @Test fun grantYields() {
        assertEquals(Action.Yield, LegacyHandoverRequest.resolve(Kind.DISCONNECT) { GrantDecision.Grant })
    }
}
