package com.macedonga.visor.core.media

import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.session.ServiceData
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The glasses' app manager install, driven as the Meta app's AppHub does it (Meta AI 294, firmware 70523680060800100):
 * a lease on the remote service manager (DataX service 56) for feature 18, which has the app manager register its
 * DataX service 66; a start of APP_MANAGER (14) there; an AppInstallRequest; the APK over airship namespace
 * "appmanager"; the lease released. The app manager installs with a PackageInstaller session after its own signer check
 * (wss_appmanager.signature_verification_enabled).
 */
class AppInstall(private val connection: GlassesConnection, private val airship: AirshipReceiver) {

    /** Install [apk] as [packageName]; [note] hears every step. Returns how it ended. */
    suspend fun install(apk: File, packageName: String, versionCode: Long, note: (String) -> Unit): String =
        withAppManager(apk.length() / 1_048_576 + 50, note) { send, next ->
        val uuid = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = ProtoWriter().string(1, UUID.randomUUID().toString()).build()
        val request = ProtoWriter().bytes(1, key).string(2, packageName).string(3, versionCode.toString()).bytes(4, uuid)
            .varint(5, apk.length()).string(8, packageName).build()
        send(MT_INSTALL, request)
        note("App install: asked to install $packageName (${sizeWords(apk.length())})")
        val answer = next(ANSWER_MS) ?: return@withAppManager "no answer to the install request"
        note("App install: ${describe(answer)}")
        val f = fields(answer.payload)
        // Field 2 is the file action; 0 (absent on the wire) is SEND.
        if (answer.messageType != MT_ANSWER || f.long(2) != 0L || f.long(4) != 0L) return@withAppManager describe(answer)

        val sent = CompletableDeferred<String?>()
        connection.submit { mux -> sent.complete(airship.send(mux, NAMESPACE, "$packageName.apk", apk.readBytes(), uuid = uuid)) }
        val why = withTimeoutOrNull(TRANSFER_MS) { sent.await() }
        if (!sent.isCompleted || why != null) return@withAppManager "transfer failed: ${why ?: "no link or no answer"}"
        note("App install: file sent")

        while (true) {
            val m = next(INSTALL_MS) ?: return@withAppManager "no word from the glasses after the transfer"
            note("App install: ${describe(m)}")
            if (m.messageType == MT_ERROR) return@withAppManager describe(m)
            if (m.messageType == MT_PROGRESS && fields(m.payload).long(3) == STAGE_COMPLETE) return@withAppManager "installed"
        }
        @Suppress("UNREACHABLE_CODE")
        "unreachable"
    }

    /** What the app manager knows about [packageName]: installed, version, sideloaded. */
    suspend fun query(packageName: String, note: (String) -> Unit = {}): String = withAppManager(50, note) { send, next ->
        val key = ProtoWriter().string(1, UUID.randomUUID().toString()).build()
        send(MT_QUERY, ProtoWriter().bytes(1, key).string(2, packageName).build())
        val m = next(ANSWER_MS) ?: return@withAppManager "no answer"
        if (m.messageType != MT_QUERY_ANSWER) return@withAppManager describe(m)
        val f = fields(m.payload)
        "installed=${f.long(5) != 0L} versionCode=${f.long(3)} versionName=${f.text(4)} sideloaded=${f.long(8) != 0L} " +
            "system=${f.long(7) != 0L} entitled=${f.long(6) != 0L}"
    }

    /**
     * [work] with the app manager's service up, on a channel of its own: the lease taken and its BOUND awaited, a start
     * of APP_MANAGER answered (the service registers a moment after the bind, so a channel the glasses did not know is
     * dropped and another one opened), the lease given back after.
     */
    private suspend fun withAppManager(
        megabytes: Long,
        note: (String) -> Unit,
        work: suspend (send: suspend (Int, ByteArray) -> Unit, next: suspend (Long) -> ServiceData?) -> String,
    ): String = coroutineScope {
        val inbox = mapOf(SVC_LEASE to Channel<ServiceData>(Channel.UNLIMITED), SVC to Channel(Channel.UNLIMITED))
        val listening = CompletableDeferred<Unit>()
        val listener = launch {
            connection.serviceData.onSubscription { listening.complete(Unit) }.collect { inbox[it.serviceId]?.send(it) }
        }
        listening.await()
        var channel: Int? = null
        val send: suspend (Int, ByteArray) -> Unit = { type, payload ->
            connection.submit { mux ->
                val open = channel
                if (open != null) {
                    mux.send(open, payload, messageType = type)
                } else {
                    val fresh = mux.openChannel(SVC)
                    channel = fresh
                    mux.send(fresh, payload, messageType = type, serviceId = SVC)
                }
            }
        }
        val next: suspend (Long) -> ServiceData? = { ms -> withTimeoutOrNull(ms) { inbox.getValue(SVC).receive() } }
        val lease = ProtoWriter().varint(1, FEATURE_APP_MANAGER).build()
        try {
            connection.submit { it.sendOnService(SVC_LEASE, MT_LEASE, lease) }
            val bound = withTimeoutOrNull(ANSWER_MS) {
                while (true) {
                    val m = inbox.getValue(SVC_LEASE).receive()
                    val f = fields(m.payload)
                    note("App install: lease answer type ${m.messageType}, state ${f.long(2)}")
                    if (m.messageType == MT_LEASE_ERROR) return@withTimeoutOrNull false
                    if (m.messageType == MT_LIFECYCLE && f.long(2) == STATE_BOUND) return@withTimeoutOrNull true
                }
                @Suppress("UNREACHABLE_CODE")
                false
            }
            if (bound != true) return@coroutineScope "the app manager was not bound (${if (bound == null) "no answer" else "lease refused"})"

            val policy = ProtoWriter().bytes(1, ProtoWriter().varint(1, megabytes).build()).build()
            val start = ProtoWriter().varint(1, APP_MANAGER).bytes(2, policy).build()
            var started: ServiceData? = null
            for (attempt in 1..START_TRIES) {
                delay(REGISTER_MS)
                channel = null
                send(MT_START, start)
                started = next(START_MS)
                if (started != null) break
                note("App install: no answer to the start yet (try $attempt)")
            }
            val answer = started ?: return@coroutineScope "the app manager's service never answered"
            note("App install: ${describe(answer)}")
            work(send, next)
        } finally {
            connection.submit { it.sendOnService(SVC_LEASE, MT_RELEASE, lease) }
            listener.cancel()
        }
    }

    private fun describe(m: ServiceData): String {
        val f = fields(m.payload)
        return when (m.messageType) {
            MT_STARTED -> "app manager start: error code ${f.long(1)}, status ${f.long(2)}"
            MT_ANSWER -> "answer: file action ${f.long(2)}, error ${f.long(4)} ${f.text(5)}".trim()
            MT_PROGRESS -> "stage ${STAGES.getOrElse(f.long(3).toInt()) { f.long(3).toString() }}"
            MT_ERROR -> "error ${ERRORS.getOrElse(f.long(4).toInt()) { f.long(4).toString() }} at stage ${f.long(3)}: ${f.text(5)}"
            else -> "message ${m.messageType}, ${m.payload.size} bytes"
        }
    }

    private companion object {
        const val SVC_LEASE = 56
        const val MT_LEASE = 1
        const val MT_RELEASE = 2
        const val MT_LIFECYCLE = 3
        const val MT_LEASE_ERROR = 4
        const val STATE_BOUND = 1L
        const val REGISTER_MS = 1_500L
        const val START_MS = 4_000L
        const val START_TRIES = 4
        const val FEATURE_APP_MANAGER = 18L
        const val SVC = 66
        const val MT_START = 2000
        const val MT_STARTED = 2001
        const val APP_MANAGER = 14L
        const val MT_INSTALL = 2500
        const val MT_ANSWER = 2501
        const val MT_PROGRESS = 2502
        const val MT_ERROR = 2503
        const val MT_QUERY = 2800
        const val MT_QUERY_ANSWER = 2801
        const val NAMESPACE = "appmanager"
        const val STAGE_COMPLETE = 3L
        const val ANSWER_MS = 15_000L
        const val TRANSFER_MS = 10 * 60_000L
        const val INSTALL_MS = 120_000L
        val STAGES = listOf("unknown", "transfer", "installing", "complete", "stopped")
        val ERRORS = listOf(
            "unknown", "success", "unknown message", "unhandled message", "payload corrupted", "unsupported app",
            "not enough battery", "thermal throttling", "not enough storage", "start failed", "stop failed",
            "not running", "already started", "not enough priority", "charger not connected", "denied by peak power", "power down",
        )
    }
}

private fun Map<Int, List<Any>>.text(n: Int): String = bytes(n).decodeToString()
