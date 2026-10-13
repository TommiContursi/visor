package com.macedonga.visor.core.transport

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** The service class UUID the glasses publish in SDP, SDP attribute 0x0001. SDP maps it to RFCOMM channel 7. */
val META_RFCOMM_SERVICE_UUID: UUID = UUID.fromString("8b0d2687-42a4-44cb-9436-fba3b9b96de2")

/** SDP attribute 0x0100 on that record. */
const val META_RFCOMM_SERVICE_NAME: String = "Meta"

/** The byte pipe under AirShield, over Bluetooth Classic RFCOMM. */
class BluetoothRfcommTransport(
    private val context: Context,
    private val address: String,
    private val serviceUuid: UUID = META_RFCOMM_SERVICE_UUID,
    private val wireLog: WireLog? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : Transport {

    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var stream: SduStream? = null

    /** Counts closes, so a connect that was in flight when one happened does not undo it. */
    private val closes = AtomicInteger(0)

    // Lint asks for either a permission check or explicit handling of the SecurityException, and
    // this file does the second everywhere it touches the radio: the discovery cancel is inside
    // runCatching and every other call has a catch that turns it into
    // ConnectFailure.PERMISSION_REFUSED.
    @android.annotation.SuppressLint("MissingPermission")
    override suspend fun connect() {
        val before = closes.get()
        withContext(io) {
            // Never leave the last socket open.
            shutSocket()
            val device = resolve()
            val opening = try {
                device.createRfcommSocketToServiceRecord(serviceUuid)
            } catch (error: SecurityException) {
                throw permissionRefused(error)
            }
            // Discovery and a connect attempt compete for the radio, and discovery wins.
            runCatching { adapter().cancelDiscovery() }
            try {
                opening.connect()
            } catch (error: SecurityException) {
                runCatching { opening.close() }
                throw permissionRefused(error)
            } catch (error: IOException) {
                runCatching { opening.close() }
                throw TransportConnectException(
                    ConnectFailure.NO_ANSWER,
                    "the glasses did not answer",
                    error,
                )
            }
            if (closes.get() != before) {
                runCatching { opening.close() }
                throw TransportClosedException("the link was closed while it was opening")
            }
            socket = opening
            stream = SduStream(opening.inputStream, opening.outputStream, wireLog)
        }
    }

    override suspend fun send(frame: ByteArray) {
        withContext(io) { open().send(frame) }
    }

    override suspend fun receive(): ByteArray = withContext(io) { open().receive() }

    override fun close() {
        closes.incrementAndGet()
        shutSocket()
    }

    private fun shutSocket() {
        stream = null
        socket?.let { runCatching { it.close() } }
        socket = null
    }

    private fun open(): SduStream = stream ?: throw TransportClosedException("not connected")

    private fun adapter(): BluetoothAdapter =
        context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw TransportConnectException(
                ConnectFailure.BLUETOOTH_UNSUPPORTED,
                "this phone has no Bluetooth",
            )

    /**
     * Everything that can be known before the radio is used, in the order that makes each answer
     * true.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun resolve(): BluetoothDevice {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            throw TransportConnectException(
                ConnectFailure.BAD_ADDRESS,
                "$address is not a Bluetooth address",
            )
        }
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            throw permissionRefused(null)
        }
        val adapter = adapter()
        if (!adapter.isEnabled) {
            throw TransportConnectException(ConnectFailure.BLUETOOTH_OFF, "Bluetooth is off")
        }
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (error: IllegalArgumentException) {
            throw TransportConnectException(
                ConnectFailure.BAD_ADDRESS,
                "$address is not a Bluetooth address",
                error,
            )
        }
        val bonded = try {
            device.bondState == BluetoothDevice.BOND_BONDED
        } catch (error: SecurityException) {
            throw permissionRefused(error)
        }
        if (!bonded) {
            throw TransportConnectException(
                ConnectFailure.NOT_PAIRED,
                "these glasses are not paired with this phone yet",
            )
        }
        return device
    }

    private fun permissionRefused(cause: Throwable?) = TransportConnectException(
        ConnectFailure.PERMISSION_REFUSED,
        "Visor is not allowed to use Bluetooth yet",
        cause,
    )
}
