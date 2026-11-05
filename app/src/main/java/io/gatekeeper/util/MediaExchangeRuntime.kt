@file:Suppress("TooGenericExceptionCaught") // Platform boundaries must record/retry failures, including OEM exceptions.

package io.gatekeeper.util

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.util.Log
import io.gatekeeper.services.IMediaExchange
import java.io.IOException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One serialized receiver per profile. The personal endpoint owns every transfer's authorization. */
@Suppress("TooManyFunctions") // Cohesive lifecycle coordinator and its complete AIDL endpoint.
class MediaExchangeRuntime private constructor(private val context: Context) {
    private val work = Utility.isProfileOwner(context)
    private val catalog = MediaExchangeCatalog(context, work)
    private val ledger = MediaExchangeLedger(context)
    private val writer = MediaExchangeWriter(context, work)
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val eventLock = Any()
    private var event: ScheduledFuture<*>? = null
    private var retry: ScheduledFuture<*>? = null
    @Volatile private var peer: IMediaExchange? = null
    @Volatile private var running = false
    @Volatile var status = "media_exchange_waiting"
        private set
    @Volatile var remoteStatus = "media_exchange_waiting"
        private set
    @Volatile var deliveredCount = 0L
        private set
    @Volatile var remoteDeliveredCount = 0L
        private set

    @Suppress("TooManyFunctions") // Implements the complete narrow wire contract.
    val endpoint: IMediaExchange = object : IMediaExchange.Stub() {
        override fun isWorkProfile(): Boolean = work
        override fun status(): String = status
        override fun deliveredCount(): Long = deliveredCount
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            BinderCallers.enforceGatekeeperCaller(code)
            return super.onTransact(code, data, reply, flags)
        }
        override fun configuration(): String = if (!work) MediaExchangeSettings.current().wire()
            else requireNotNull(peer) { "Personal endpoint unavailable" }.configuration()
        override fun identity(): String = catalog.identity()
        override fun latestId(): Long = catalog.latestId()
        override fun images(afterId: Long, limit: Int, configuration: String): List<String> {
            val config = authorize(configuration)
            return catalog.list(afterId, limit, config).map { it.wire() }
        }
        override fun describe(id: Long, configuration: String): String? =
            catalog.describe(id, authorize(configuration))?.wire()
        override fun open(id: Long, version: String, configuration: String): ParcelFileDescriptor =
            catalog.open(id, version, authorize(configuration))
        override fun connect(peer: IMediaExchange) {
            check(MediaExchangeRemote(peer).isWorkProfile != work) { "Expected the other profile" }
            attach(peer)
            check(io.gatekeeper.services.MediaExchangeService.start(context, peer)) { "Media observer could not start" }
        }
        override fun changed() = signal()
    }

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            signal()
            worker.execute {
                try { peer?.changed() } catch (e: Exception) { lostPeer(e) }
            }
        }
    }

    fun attach(remote: IMediaExchange): Boolean {
        check(BinderCallers.isSameApp(android.os.Binder.getCallingUid(), android.os.Process.myUid()))
        if (peer?.asBinder() == remote.asBinder()) return false
        peer = MediaExchangeRemote(remote)
        remote.asBinder().linkToDeath({
            if (peer?.asBinder() == remote.asBinder()) {
                peer = null
                status = "media_exchange_waiting"
                remoteStatus = "media_exchange_waiting"
            }
        }, 0)
        signal()
        return true
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        context.contentResolver.registerContentObserver(catalog.collection, true, observer)
        worker.execute {
            try { writer.cleanPending() } catch (e: Exception) { Log.w(TAG, "Pending cleanup unavailable", e) }
        }
        retry = worker.scheduleWithFixedDelay({ pass() }, 0, RETRY_INTERVAL_SECONDS, TimeUnit.SECONDS)
    }

    @Synchronized
    fun stop() {
        running = false
        context.contentResolver.unregisterContentObserver(observer)
        retry?.cancel(false)
        retry = null
        synchronized(eventLock) { event?.cancel(false); event = null }
        worker.execute {
            try { writer.cleanPending() } catch (e: Exception) { Log.w(TAG, "Pending cleanup unavailable", e) }
        }
    }

    fun configurationChanged() {
        signal()
        worker.execute {
            try { peer?.changed() } catch (e: Exception) { lostPeer(e) }
        }
    }

    private fun signal() {
        synchronized(eventLock) {
            if (event?.isDone == false) return@synchronized
            event = worker.schedule({
                synchronized(eventLock) { event = null }
                pass()
            }, EVENT_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun configWire(): String =
        if (work) requireNotNull(peer).configuration() else MediaExchangeSettings.current().wire()

    private fun authorize(wire: String): MediaExchangeSettings.Config {
        check(configWire() == wire) { "Media configuration changed" }
        return MediaExchangeSettings.parse(wire).also { check(it.enabled) { "Media exchange disabled" } }
    }

    @Suppress("ReturnCount") // Early exits are disabled/unavailable lifecycle states.
    private fun pass() {
        if (!running) return
        try {
            val remote = peer ?: run { status = "media_exchange_waiting"; return }
            remoteStatus = remote.status()
            remoteDeliveredCount = remote.deliveredCount()
            val wire = configWire()
            val config = MediaExchangeSettings.parse(wire)
            if (!config.enabled || !(if (work) config.camera else config.screenshots)) {
                status = "media_exchange_off"
                if (!config.enabled || (!config.screenshots && !config.camera)) {
                    context.stopService(android.content.Intent(context,
                        io.gatekeeper.services.MediaExchangeService::class.java))
                }
                return
            }
            receive(remote, wire, config)
        } catch (e: SecurityException) {
            status = "media_exchange_permission"
            Log.w(TAG, "Media permission unavailable", e)
        } catch (e: Exception) {
            lostPeer(e)
        }
    }

    private fun receive(remote: IMediaExchange, wire: String, config: MediaExchangeSettings.Config) {
        val epoch = if (work) config.cameraEpoch else config.screenshotEpoch
        val source = "${remote.identity()}:$epoch"
        deliveredCount = ledger.deliveredCount(source)
        val cursor = ledger.cursor(source)
        if (cursor == null) {
            // Never export the pre-existing camera archive on first enable.
            ledger.baseline(source, remote.latestId())
            status = "media_exchange_active"
            return
        }
        val items = remote.images(cursor, MediaExchangePolicy.PAGE_SIZE, wire).map { MediaExchangeItem.parse(it) }
        check(items.zipWithNext().all { (a, b) -> a.id < b.id } && items.all { it.id > cursor })
        ledger.discover(source, items)
        status = "media_exchange_active"
        for (delivery in ledger.pending(source, System.currentTimeMillis())) {
            if (!running || configWire() != wire) return
            deliver(remote, wire, delivery)
        }
        deliveredCount = ledger.deliveredCount(source)
        if (status == "media_exchange_active" && ledger.hasPending(source)) status = "media_exchange_retry"
        if (ledger.pending(source, System.currentTimeMillis()).isNotEmpty()) signal()
        if (items.size == MediaExchangePolicy.PAGE_SIZE) signal()
    }

    private fun deliver(remote: IMediaExchange, wire: String, delivery: MediaExchangeLedger.Delivery) {
        var target: Uri? = null
        try {
            val item = remote.describe(delivery.id, wire)?.let(MediaExchangeItem::parse)
            if (item == null || item.mime.isEmpty()) {
                ledger.complete(delivery.token)
                return
            }
            if (!item.ready) throw IOException("Source is not ready")
            if (item.size <= 0) throw IOException("Source is empty")
            if (item.size !in 1..MediaExchangePolicy.MAX_BYTES) {
                status = "media_exchange_too_large"
                ledger.retry(delivery, System.currentTimeMillis())
                return
            }
            target = writer.prepare(delivery, item)
            ledger.setUri(delivery.token, target.toString())
            if (!writer.isPublished(target, item.size)) {
                ParcelFileDescriptor.AutoCloseInputStream(remote.open(item.id, item.version, wire)).use { input ->
                    writer.write(target, input, item.size) { running && localAllows(wire) }
                }
                check(remote.describe(item.id, wire)?.let(MediaExchangeItem::parse)?.version == item.version)
                check(configWire() == wire && running)
                writer.publish(target, item)
            }
            ledger.complete(delivery.token)
        } catch (e: SecurityException) {
            failDelivery(delivery, target, "media_exchange_permission", e)
        } catch (e: Exception) {
            failDelivery(delivery, target, "media_exchange_retry", e)
        }
    }

    private fun failDelivery(delivery: MediaExchangeLedger.Delivery, target: Uri?, state: String, error: Exception) {
        if (target != null) {
            try { writer.discardPending(target); ledger.setUri(delivery.token, null) }
            catch (cleanup: Exception) { Log.w(TAG, "Pending image cleanup failed", cleanup) }
        }
        ledger.retry(delivery, System.currentTimeMillis())
        status = state
        Log.w(TAG, "Image delivery will retry", error)
    }

    // The work side rechecks the coordinator before publishing. Never call Binder for every buffer.
    private fun localAllows(wire: String): Boolean =
        if (work) peer != null else MediaExchangeSettings.current().wire() == wire

    private fun lostPeer(error: Exception) {
        status = "media_exchange_waiting"
        remoteStatus = "media_exchange_waiting"
        Log.w(TAG, "Media exchange unavailable; awaiting reconnection", error)
    }

    companion object {
        private const val TAG = "MediaExchange"
        private const val RETRY_INTERVAL_SECONDS = 30L
        private const val EVENT_DEBOUNCE_MS = 300L
        @android.annotation.SuppressLint("StaticFieldLeak") // get() retains only applicationContext.
        @Volatile private var instance: MediaExchangeRuntime? = null
        @Synchronized fun get(context: Context): MediaExchangeRuntime = instance
            ?: MediaExchangeRuntime(context.applicationContext).also { instance = it }
    }
}
