package io.automated.ventures.everypods.relay

import org.junit.Assert.assertEquals
import org.junit.Test

class RelayReceiverAdmissionTest {
    private fun decide(requester: String, active: String? = null, reservation: String? = null, pending: String? = null) =
        RelayReceiverAdmission.decide(
            requester = requester,
            activeSender = active,
            active = active != null,
            reservedSender = reservation,
            pendingSender = pending,
        )

    @Test
    fun eitherAuthorizedSenderCanReserveAnIdleReceiver() {
        assertEquals(RelayAdmissionDecision.RESERVE_RECEIVER, decide("B"))
        assertEquals(RelayAdmissionDecision.RESERVE_RECEIVER, decide("C"))
    }

    @Test
    fun simultaneousStartsAdmitOneAndRejectTheOtherAsBusy() {
        assertEquals(RelayAdmissionDecision.RESERVE_RECEIVER, decide("B", reservation = null))
        assertEquals(RelayAdmissionDecision.BUSY, decide("C", reservation = "B"))
    }

    @Test
    fun anotherSenderRequestsSwitchWhileExistingSenderContinues() {
        assertEquals(RelayAdmissionDecision.REQUEST_SWITCH, decide("C", active = "B"))
        assertEquals(RelayAdmissionDecision.DUPLICATE_REQUEST, decide("C", active = "B", pending = "C"))
        assertEquals(RelayAdmissionDecision.BUSY, decide("D", active = "B", pending = "C"))
    }

    @Test
    fun activeSenderRetryDoesNotReplaceItsOwnSession() {
        assertEquals(RelayAdmissionDecision.DUPLICATE_REQUEST, decide("b", active = "B"))
    }

    @Test
    fun sameReservationRetryIsIdempotentAndOtherSenderIsBusy() {
        assertEquals(RelayAdmissionDecision.DUPLICATE_REQUEST, decide("b", reservation = "B"))
        assertEquals(RelayAdmissionDecision.BUSY, decide("C", reservation = "B"))
    }

    @Test
    fun switchAndReservationTimeoutsMatchProductContract() {
        assertEquals(30_000L, RelayReceiverAdmission.SWITCH_REQUEST_TIMEOUT_MS)
        assertEquals(60_000L, RelayReceiverAdmission.RESERVATION_TIMEOUT_MS)
    }
}
