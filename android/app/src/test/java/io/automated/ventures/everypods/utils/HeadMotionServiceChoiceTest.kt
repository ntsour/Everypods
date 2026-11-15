package io.automated.ventures.everypods.utils

import org.junit.Assert.*
import org.junit.Test

class HeadMotionServiceChoiceTest {
    @Test fun `starts on the remembered service and has nothing to save while it works`() {
        val choice = HeadMotionServiceChoice(remembered = true)
        assertTrue(choice.alternate)
        assertFalse(choice.onData())
        assertFalse(HeadMotionServiceChoice(remembered = false).alternate)
    }

    @Test fun `no data switches to the other service once per connection`() {
        val choice = HeadMotionServiceChoice()
        assertTrue(choice.onNoData(generation = 1))
        assertFalse(choice.alternate)
        assertFalse(choice.onNoData(generation = 1))
        assertFalse(choice.alternate)
    }

    @Test fun `a fallback that delivers data is saved once`() {
        val choice = HeadMotionServiceChoice()
        choice.onNoData(1)
        assertTrue(choice.onData())
        assertFalse(choice.onData())
        choice.onNewConnection(2)
        assertFalse(choice.alternate)
    }

    @Test fun `a fallback that also fails is forgotten on the next connection`() {
        val choice = HeadMotionServiceChoice()
        choice.onNoData(1)
        choice.onNewConnection(1)
        assertFalse(choice.alternate)
        choice.onNewConnection(2)
        assertTrue(choice.alternate)
        assertTrue(choice.onNoData(2))
    }
}
