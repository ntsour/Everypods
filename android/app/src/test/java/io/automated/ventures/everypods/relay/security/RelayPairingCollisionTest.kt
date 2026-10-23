package io.automated.ventures.everypods.relay.security

import java.security.KeyPairGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RelayPairingCollisionTest {
    @Test
    fun `simultaneous pairing converges on the same challenge on both peers`() {
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(256) }
        repeat(50) {
            val first = generator.generateKeyPair().public.encoded
            val second = generator.generateKeyPair().public.encoded
            val firstKeeps = RelayPairingCollision.keepLocalChallenge(first, second)
            val secondKeeps = RelayPairingCollision.keepLocalChallenge(second, first)
            assertNotEquals(firstKeeps, secondKeeps)
            assertEquals(if (firstKeeps) "first nonce" else "second nonce", if (secondKeeps) "second nonce" else "first nonce")
        }
    }

    @Test
    fun `byte ordering is unsigned and self pairing is rejected`() {
        assertEquals(true, RelayPairingCollision.keepLocalChallenge(byteArrayOf(0x7f), byteArrayOf(0x80.toByte())))
        assertThrows(IllegalArgumentException::class.java) {
            RelayPairingCollision.keepLocalChallenge(byteArrayOf(1, 2), byteArrayOf(1, 2))
        }
    }
}
