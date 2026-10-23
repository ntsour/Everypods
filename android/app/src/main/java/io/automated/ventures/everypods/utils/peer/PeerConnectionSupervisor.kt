package io.automated.ventures.everypods.utils.peer

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

fun interface PeerSocketFactory {
    fun create(adapter: BluetoothAdapter, mac: String, service: UUID): BluetoothSocket
}

/** Process-owned peer coroutine tree and global RFCOMM dial gate. */
class PeerConnectionSupervisor(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val socketFactory: PeerSocketFactory = PeerSocketFactory { adapter, mac, service ->
        adapter.getRemoteDevice(mac).createInsecureRfcommSocketToServiceRecord(service)
    },
    private val elapsedRealtime: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    private val rootJob = SupervisorJob()
    val scope = CoroutineScope(rootJob + dispatcher)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val dialMutex = Mutex()

    fun elapsedRealtime(): Long = elapsedRealtime.invoke()

    fun createSocket(adapter: BluetoothAdapter, mac: String, service: UUID): BluetoothSocket =
        socketFactory.create(adapter, mac, service)

    /** Installs one owned loop per peer before it can execute. */
    fun launch(connection: PeerConnection, block: suspend CoroutineScope.() -> Unit): Job {
        synchronized(jobs) {
            check(jobs[connection.mac]?.isActive != true) { "peer_loop_already_running" }
            val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY, block = block)
            jobs[connection.mac] = job
            connection.job = job
            job.invokeOnCompletion { jobs.remove(connection.mac, job) }
            job.start()
            return job
        }
    }

    /** Ensures only one peer runs a blocking RFCOMM connect at a time. */
    suspend fun <T> connectSerialized(connect: suspend () -> T): T = dialMutex.withLock { connect() }

    /** Close exact resources first so blocking reads/connects wake, then join cleanup. */
    fun stop(connection: PeerConnection) {
        val job = jobs.remove(connection.mac)
        connection.stop()
        if (job != null) scope.launch { job.cancelAndJoin() }
    }

    suspend fun stopAndJoin(connection: PeerConnection) {
        val job = jobs.remove(connection.mac)
        connection.stop()
        job?.cancelAndJoin()
    }
}
