package com.markusmaribu.picochat.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.markusmaribu.picochat.util.CompressionUtil
import com.markusmaribu.picochat.util.Constants
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@android.annotation.SuppressLint("MissingPermission")
class L2capManager(context: Context) {

    companion object {
        private const val TAG = "L2capManager"
        private const val FRAME_HEADER_SIZE = 13 // 1 type + 4 hash + 8 timestamp
        private const val TYPE_DRAWING: Byte = 0x01
        private const val TYPE_TEXT: Byte = 0x02
        private const val TYPE_PULL_REQUEST: Byte = 0x03
    }

    data class DrawingPayload(
        val username: String,
        val hash: Int,
        val timestamp: Long,
        val rawBits: ByteArray,
        val colorIndex: Int = 0,
        val rainbowBits: ByteArray? = null
    )

    data class PullResult(
        val hash: Int,
        val timestamp: Long,
        val rawBits: ByteArray,
        val colorIndex: Int = 0,
        val rainbowBits: ByteArray? = null
    )

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    private var serverSocket: BluetoothServerSocket? = null
    private var serverPsm: Int = -1
    @Volatile
    private var serverRunning = false
    private var serverThread: Thread? = null

    private val recvExecutor = Executors.newFixedThreadPool(4)
    private val connectExecutor = Executors.newCachedThreadPool()

    private var messageProvider: ((Int) -> DrawingPayload?)? = null
    private var onTextReceivedViaL2cap: ((ByteArray) -> Unit)? = null

    fun setMessageProvider(provider: (Int) -> DrawingPayload?) {
        messageProvider = provider
    }

    fun setOnTextReceived(callback: (ByteArray) -> Unit) {
        onTextReceivedViaL2cap = callback
    }

    fun startServer(onDrawingReceived: (String, Int, Long, ByteArray, Int, ByteArray?) -> Unit) {
        if (adapter == null || !adapter.isEnabled) return

        try {
            serverSocket = adapter.listenUsingInsecureL2capChannel()
            serverPsm = serverSocket!!.psm
            serverRunning = true
            Log.d(TAG, "L2CAP server started on PSM $serverPsm")

            serverThread = Thread({
                while (serverRunning) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        recvExecutor.submit { handleIncomingConnection(socket, onDrawingReceived) }
                    } catch (e: IOException) {
                        if (serverRunning) {
                            Log.e(TAG, "Accept error", e)
                        }
                        break
                    }
                }
            }, "L2CAP-Server").also { it.isDaemon = true; it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start L2CAP server", e)
        }
    }

    fun stopServer() {
        serverRunning = false
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing server socket", e)
        }
        serverSocket = null
        serverThread?.interrupt()
        serverThread = null
        recvExecutor.shutdownNow()
        connectExecutor.shutdownNow()
    }

    fun getServerPsm(): Int = serverPsm

    private fun handleIncomingConnection(
        socket: BluetoothSocket,
        onDrawingReceived: (String, Int, Long, ByteArray, Int, ByteArray?) -> Unit
    ) {
        try {
            val input = socket.inputStream
            val header = readExact(input, FRAME_HEADER_SIZE) ?: return
            val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
            val type = buf.get()
            val hashOrLength = buf.int
            val timestamp = buf.long

            when (type) {
                TYPE_DRAWING -> {
                    val lenBuf = readExact(input, 1) ?: return
                    val nameLen = lenBuf[0].toInt() and 0xFF
                    val nameBytes = readExact(input, nameLen) ?: return
                    val senderName = String(nameBytes, Charsets.UTF_8)
                    val flagBuf = readExact(input, 1) ?: return
                    val flagByte = flagBuf[0].toInt() and 0xFF
                    val colorIdx = flagByte and 0x0F
                    val hasRainbow = (flagByte and 0x10) != 0
                    val data = readExact(input, Constants.DRAWING_BYTES)
                    if (data != null) {
                        val rainbowBits = if (hasRainbow) {
                            val rb = readCompressedRainbow(input)
                            rb
                        } else null
                        try {
                            socket.outputStream.write(0x01)
                            socket.outputStream.flush()
                        } catch (_: IOException) {}
                        onDrawingReceived(senderName, hashOrLength, timestamp, data, colorIdx, rainbowBits)
                    }
                }
                TYPE_TEXT -> {
                    if (hashOrLength in 1..4096) {
                        val data = readExact(input, hashOrLength)
                        if (data != null) {
                            onTextReceivedViaL2cap?.invoke(data)
                        }
                    }
                }
                TYPE_PULL_REQUEST -> {
                    handlePullRequest(socket, hashOrLength)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling L2CAP connection", e)
        } finally {
            try { socket.close() } catch (_: IOException) {}
        }
    }

    private fun handlePullRequest(socket: BluetoothSocket, requestedHash: Int) {
        try {
            val msg = messageProvider?.invoke(requestedHash)
            if (msg == null) {
                socket.outputStream.write(0x00)
                socket.outputStream.flush()
                return
            }

            val usernameBytes = msg.username.toByteArray(Charsets.UTF_8)
            val output = socket.outputStream
            val header = ByteBuffer.allocate(FRAME_HEADER_SIZE)
                .order(ByteOrder.BIG_ENDIAN)
                .put(TYPE_DRAWING)
                .putInt(msg.hash)
                .putLong(msg.timestamp)
                .array()
            output.write(header)
            output.write(byteArrayOf(usernameBytes.size.toByte()))
            output.write(usernameBytes)
            val flags = msg.colorIndex.coerceIn(0, 15) or (if (msg.rainbowBits != null) 0x10 else 0x00)
            output.write(byteArrayOf(flags.toByte()))
            output.write(msg.rawBits)
            writeCompressedRainbow(output, msg.rainbowBits)
            output.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Error responding to pull request", e)
        }
    }

    private fun connectWithTimeout(socket: BluetoothSocket) {
        val future = connectExecutor.submit { socket.connect() }
        try {
            future.get(Constants.L2CAP_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            try { socket.close() } catch (_: IOException) {}
            throw IOException("L2CAP connect timed out")
        } catch (e: Exception) {
            future.cancel(true)
            throw e.cause ?: e
        }
    }

    fun sendDrawing(
        device: BluetoothDevice,
        psm: Int,
        hash: Int,
        timestamp: Long,
        username: String,
        data: ByteArray,
        colorIndex: Int = 0,
        rainbowBits: ByteArray? = null
    ): Boolean {
        if (data.size != Constants.DRAWING_BYTES) {
            Log.w(TAG, "Invalid drawing data size: ${data.size}")
            return false
        }

        var socket: BluetoothSocket? = null
        return try {
            val usernameBytes = username.toByteArray(Charsets.UTF_8)
            socket = device.createInsecureL2capChannel(psm)
            connectWithTimeout(socket)
            val output = socket.outputStream
            val header = ByteBuffer.allocate(FRAME_HEADER_SIZE)
                .order(ByteOrder.BIG_ENDIAN)
                .put(TYPE_DRAWING)
                .putInt(hash)
                .putLong(timestamp)
                .array()
            output.write(header)
            output.write(byteArrayOf(usernameBytes.size.toByte()))
            output.write(usernameBytes)
            val flags = colorIndex.coerceIn(0, 15) or (if (rainbowBits != null) 0x10 else 0x00)
            output.write(byteArrayOf(flags.toByte()))
            output.write(data)
            writeCompressedRainbow(output, rainbowBits)
            output.flush()
            waitForAck(socket)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send drawing to ${device.address} on PSM $psm", e)
            false
        } finally {
            try { socket?.close() } catch (_: IOException) {}
        }
    }

    private fun waitForAck(socket: BluetoothSocket) {
        val future = connectExecutor.submit<Int> { socket.inputStream.read() }
        try {
            future.get(Constants.L2CAP_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            future.cancel(true)
        }
    }

    fun sendTextData(device: BluetoothDevice, psm: Int, data: ByteArray): Boolean {
        var socket: BluetoothSocket? = null
        return try {
            socket = device.createInsecureL2capChannel(psm)
            connectWithTimeout(socket)
            val output = socket.outputStream
            val header = ByteBuffer.allocate(FRAME_HEADER_SIZE)
                .order(ByteOrder.BIG_ENDIAN)
                .put(TYPE_TEXT)
                .putInt(data.size)
                .putLong(0L)
                .array()
            output.write(header)
            output.write(data)
            output.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send text data to ${device.address}", e)
            false
        } finally {
            try { socket?.close() } catch (_: IOException) {}
        }
    }

    fun pullMessage(device: BluetoothDevice, psm: Int, requestedHash: Int): PullResult? {
        var socket: BluetoothSocket? = null
        return try {
            socket = device.createInsecureL2capChannel(psm)
            connectWithTimeout(socket)

            val output = socket.outputStream
            val request = ByteBuffer.allocate(FRAME_HEADER_SIZE)
                .order(ByteOrder.BIG_ENDIAN)
                .put(TYPE_PULL_REQUEST)
                .putInt(requestedHash)
                .putLong(0L)
                .array()
            output.write(request)
            output.flush()

            val input = socket.inputStream

            val firstByte = input.read()
            if (firstByte == 0x00 || firstByte == -1) return null

            val restOfHeader = readExact(input, FRAME_HEADER_SIZE - 1) ?: return null
            val fullHeader = ByteArray(FRAME_HEADER_SIZE)
            fullHeader[0] = firstByte.toByte()
            System.arraycopy(restOfHeader, 0, fullHeader, 1, FRAME_HEADER_SIZE - 1)

            val buf = ByteBuffer.wrap(fullHeader).order(ByteOrder.BIG_ENDIAN)
            val type = buf.get()
            val hashOrLength = buf.int
            val timestamp = buf.long

            when (type) {
                TYPE_DRAWING -> {
                    val lenBuf = readExact(input, 1) ?: return null
                    val nameLen = lenBuf[0].toInt() and 0xFF
                    readExact(input, nameLen) ?: return null
                    val flagBuf = readExact(input, 1) ?: return null
                    val flagByte = flagBuf[0].toInt() and 0xFF
                    val colorIdx = flagByte and 0x0F
                    val hasRainbow = (flagByte and 0x10) != 0
                    val data = readExact(input, Constants.DRAWING_BYTES) ?: return null
                    val rainbowBits = if (hasRainbow) readCompressedRainbow(input) else null
                    PullResult(hashOrLength, timestamp, data, colorIdx, rainbowBits)
                }
                TYPE_TEXT -> null
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull message from ${device.address}", e)
            null
        } finally {
            try { socket?.close() } catch (_: IOException) {}
        }
    }

    private fun writeCompressedRainbow(output: java.io.OutputStream, rainbowBits: ByteArray?) {
        if (rainbowBits == null) return
        val compressed = CompressionUtil.deflate(rainbowBits)
        output.write(byteArrayOf(
            (compressed.size and 0xFF).toByte(),
            ((compressed.size shr 8) and 0xFF).toByte()
        ))
        output.write(compressed)
    }

    private fun readCompressedRainbow(input: InputStream): ByteArray? {
        val lenBytes = readExact(input, 2) ?: return null
        val len = (lenBytes[0].toInt() and 0xFF) or ((lenBytes[1].toInt() and 0xFF) shl 8)
        if (len <= 0 || len > 8192) return null
        val compressed = readExact(input, len) ?: return null
        return try {
            CompressionUtil.inflate(compressed, Constants.DRAWING_BYTES)
        } catch (_: Exception) { null }
    }

    private fun readExact(input: InputStream, length: Int): ByteArray? {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read == -1) return null
            offset += read
        }
        return buffer
    }
}
