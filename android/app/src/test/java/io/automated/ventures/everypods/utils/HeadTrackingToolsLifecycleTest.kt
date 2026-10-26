package io.automated.ventures.everypods.utils

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeadTrackingToolsLifecycleTest {
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }
    @Test fun `background cancels test and diagnostics without requiring route disposal`() {
        val owner = Owner()
        var diagnostics = false
        var testActive = false
        val tools = HeadTrackingToolsLifecycle(true, { diagnostics = true }, { diagnostics = false }, { testActive = false })
        owner.lifecycle.addObserver(tools)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertTrue(diagnostics)
        testActive = true
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertFalse(testActive)
        assertFalse(diagnostics)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertTrue(diagnostics)
        assertFalse(testActive)
    }
    @Test fun `collapsed tools do not start a diagnostic stream on foreground`() {
        val owner = Owner()
        var starts = 0
        val tools = HeadTrackingToolsLifecycle(false, { starts++ }, {}, {})
        owner.lifecycle.addObserver(tools)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals(0, starts)
    }
    @Test fun `disposing tools releases ownership and prevents later lifecycle starts`() {
        val owner = Owner()
        var starts = 0
        var diagnostics = false
        var testActive = true
        val tools = HeadTrackingToolsLifecycle(true, { starts++; diagnostics = true }, { diagnostics = false }, { testActive = false })
        owner.lifecycle.addObserver(tools)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.lifecycle.removeObserver(tools)
        tools.close()
        assertFalse(diagnostics)
        assertFalse(testActive)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals(1, starts)
    }
}
