package io.automated.ventures.everypods.utils

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** Explicit diagnostic/test ownership follows the visible tool, not the background Activity. */
class HeadTrackingToolsLifecycle(
    private val diagnosticsExpanded: Boolean,
    private val startDiagnostics: () -> Unit,
    private val stopDiagnostics: () -> Unit,
    private val cancelTest: () -> Unit,
) : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) {
        if (diagnosticsExpanded) startDiagnostics()
    }
    override fun onStop(owner: LifecycleOwner) { close() }
    override fun onDestroy(owner: LifecycleOwner) { close() }
    fun close() { cancelTest(); stopDiagnostics() }
}
