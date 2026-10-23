package io.automated.ventures.everypods.bluetooth.hrm.dircon

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import io.automated.ventures.everypods.bluetooth.hrm.HeartRateSharingState
import io.automated.ventures.everypods.bluetooth.hrm.SharingError
import io.automated.ventures.everypods.bluetooth.hrm.HeartRateShareIntent
import io.automated.ventures.everypods.bluetooth.hrm.HeartRateTransportPhase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress

class DirconSessionControllerTest {
    private class FakeTransport(
        private val changed: (HeartRateSharingState) -> Unit,
        private val failed: () -> Unit,
    ) : DirconTransport {
        private var state = HeartRateSharingState(active = true)
        override fun start() = changed(state)
        override fun stop(error: SharingError?) { state = state.copy(active = false, error = error) }
        override fun snapshot() = state
        override fun clearReading() = Unit
        override fun publish(bpm: Int) = Unit
        fun advertise() {
            state = state.copy(active = true, advertised = true)
            changed(state)
        }
        fun fail(error: SharingError) {
            state = state.copy(active = false, error = error)
            failed()
        }
        fun lateState(state: HeartRateSharingState) = changed(state)
        fun lateFailure() = failed()
    }

    private fun context(): Context = mockk<Context>(relaxed = true).also {
        every { it.applicationContext } returns it
        every { it.getSystemService(ConnectivityManager::class.java) } returns mockk(relaxed = true)
    }

    private class FakeNetworkMonitor(
        context: Context,
        private val callbacks: MutableList<() -> Unit>,
    ) : DirconNetworkMonitor(context) {
        private val endpoint = Endpoint(
            mockk<Network>(relaxed = true),
            InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 20)) as Inet4Address,
        )
        override fun currentEndpoint(): Endpoint = endpoint
        override fun hasWifiNetwork(): Boolean = true
        override fun watchWifiAvailability(changed: () -> Unit): Closeable {
            callbacks += changed
            return Closeable { }
        }
        override fun close() = Unit
    }

    @Test fun `transient failure retries but Stop invalidates queued retry`() {
        val calls = AtomicInteger()
        val transports = CopyOnWriteArrayList<FakeTransport>()
        val controller = DirconSessionController(
            context(), CoroutineScope(SupervisorJob() + Dispatchers.Default), {}, {},
            retryDelayMs = { 100L },
            createBroadcaster = { _, changed, failed ->
                calls.incrementAndGet()
                FakeTransport(changed, failed).also(transports::add)
            },
        )
        controller.start()
        transports.single().fail(SharingError.NETWORK_LOST)
        assertEquals(HeartRateShareIntent.REQUESTED, controller.snapshot().intent)
        assertEquals(HeartRateTransportPhase.WAITING_NETWORK, controller.snapshot().transportPhase)
        assertTrue(controller.snapshot().nextRetryAtElapsedMs != null)
        controller.stop()
        Thread.sleep(180)
        assertEquals(1, calls.get())
        assertTrue(controller.snapshot().active.not())
    }

    @Test fun `permission failure is terminal and preserves the typed cause`() {
        val calls = AtomicInteger()
        val transports = CopyOnWriteArrayList<FakeTransport>()
        val terminal = CopyOnWriteArrayList<SharingError>()
        val controller = DirconSessionController(
            context(), CoroutineScope(SupervisorJob() + Dispatchers.Default), {}, terminal::add,
            retryDelayMs = { 0L },
            createBroadcaster = { _, changed, failed ->
                calls.incrementAndGet()
                FakeTransport(changed, failed).also(transports::add)
            },
        )
        controller.start()
        transports.single().fail(SharingError.PERMISSION)
        Thread.sleep(50)
        assertEquals(listOf(SharingError.PERMISSION), terminal.toList())
        assertEquals(1, calls.get())
    }

    @Test fun `successful advertisement resets the retry burst`() {
        val calls = AtomicInteger()
        val retryAttempts = CopyOnWriteArrayList<Int>()
        val transports = CopyOnWriteArrayList<FakeTransport>()
        val controller = DirconSessionController(
            context(), CoroutineScope(SupervisorJob() + Dispatchers.Default), {}, {},
            retryDelayMs = { attempt -> retryAttempts += attempt; 50L },
            createBroadcaster = { _, changed, failed ->
                calls.incrementAndGet()
                FakeTransport(changed, failed).also(transports::add)
            },
        )
        controller.start()
        transports.single().fail(SharingError.NETWORK_LOST)
        val firstRetryDeadline = System.nanoTime() + 1_000_000_000L
        while (calls.get() < 2 && System.nanoTime() < firstRetryDeadline) Thread.sleep(5)
        assertEquals(2, calls.get())
        transports[1].advertise()
        assertEquals(HeartRateTransportPhase.DISCOVERABLE, controller.snapshot().transportPhase)
        assertEquals(null, controller.snapshot().nextRetryAtElapsedMs)
        transports[1].fail(SharingError.NETWORK_LOST)
        assertEquals(listOf(1, 1), retryAttempts.toList())
        controller.stop()
    }

    @Test fun `callbacks from stopped session cannot publish or start recovery`() {
        val calls = AtomicInteger()
        val updates = CopyOnWriteArrayList<HeartRateSharingState>()
        val transports = CopyOnWriteArrayList<FakeTransport>()
        val controller = DirconSessionController(
            context(), CoroutineScope(SupervisorJob() + Dispatchers.Default), updates::add, {},
            retryDelayMs = { 0L },
            createBroadcaster = { _, changed, failed ->
                calls.incrementAndGet()
                FakeTransport(changed, failed).also(transports::add)
            },
        )

        controller.start()
        val stoppedTransport = transports.single()
        controller.stop()
        val updateCountAfterStop = updates.size
        stoppedTransport.lateState(HeartRateSharingState(active = true, advertised = true))
        stoppedTransport.lateFailure()
        Thread.sleep(50)

        assertEquals(1, calls.get())
        assertEquals(updateCountAfterStop, updates.size)
        assertEquals(HeartRateTransportPhase.STOPPED, controller.snapshot().transportPhase)
    }

    @Test fun `late failure from replaced transport cannot stop current generation`() {
        val calls = AtomicInteger()
        val transports = CopyOnWriteArrayList<FakeTransport>()
        val controller = DirconSessionController(
            context(), CoroutineScope(SupervisorJob() + Dispatchers.Default), {}, {},
            retryDelayMs = { 10L },
            createBroadcaster = { _, changed, failed ->
                calls.incrementAndGet()
                FakeTransport(changed, failed).also(transports::add)
            },
        )

        controller.start()
        val previous = transports.single()
        previous.fail(SharingError.NETWORK_LOST)
        val retryDeadline = System.nanoTime() + 1_000_000_000L
        while (calls.get() < 2 && System.nanoTime() < retryDeadline) Thread.sleep(5)
        assertEquals(2, calls.get())
        val current = transports[1]
        previous.lateFailure()

        assertEquals(HeartRateTransportPhase.WAITING_NETWORK, controller.snapshot().transportPhase)
        assertTrue(controller.snapshot().active)
        assertTrue(current.snapshot().active)
        controller.stop()
    }

    @Test fun `network callback from stopped generation cannot wake a restarted session`() {
        val callbacks = CopyOnWriteArrayList<() -> Unit>()
        val context = context()
        val networkMonitor = FakeNetworkMonitor(context, callbacks)
        val calls = AtomicInteger()
        val transports = CopyOnWriteArrayList<FakeTransport>()
        val controller = DirconSessionController(
            context, CoroutineScope(SupervisorJob() + Dispatchers.Default), {}, {},
            retryDelayMs = { 1_000L },
            createBroadcaster = { _, changed, failed ->
                calls.incrementAndGet()
                FakeTransport(changed, failed).also(transports::add)
            },
            createNetworkMonitor = { networkMonitor },
        )

        controller.start()
        transports[0].fail(SharingError.NETWORK_LOST)
        controller.stop()
        controller.start()
        transports[1].fail(SharingError.NETWORK_LOST)
        assertEquals(2, callbacks.size)
        callbacks[0]() // queued callback from the invalidated first session
        Thread.sleep(50)

        assertEquals(2, calls.get())
        controller.stop()
    }
}
