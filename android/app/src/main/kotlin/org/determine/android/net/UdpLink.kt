package org.determine.android.net

import android.util.Log
import org.determine.core.BoardId
import org.determine.core.Outbox
import org.determine.core.wire.Wire
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException

/**
 * The phone end of the UDP link (PROTOCOL.md "Transport"): one socket bound to :47000 receives BEAT, RANGE,
 * FRAME, PONG and ACK from both boards and sends PING/CUE/PROFILE back to each board's learned IP.
 *
 * [onPacket] runs on the receive thread with a buffer that is reused for the next datagram; the engine
 * copies what it keeps (phone/core API.md), so no per-packet allocation happens here.
 *
 * [send] is only invoked from inside the engine lock, so the single reusable send packet is never shared
 * between threads concurrently.
 */
class UdpLink(
    private val addressBook: BoardAddressBook,
    private val clockUs: () -> Long,
    private val onPacket: (board: BoardId, bytes: ByteArray, len: Int, rxPhoneUs: Long) -> Unit,
    private val onUnattributed: () -> Unit,
) : Outbox {
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private val sendPacket = DatagramPacket(ByteArray(0), 0)

    @Volatile var sendErrors = 0L; private set
    @Volatile var sendsWithoutAddress = 0L; private set

    /** Binds :47000 and starts the receive thread. Throws [SocketException] if the port cannot be bound. */
    fun start(port: Int = BoardAddressBook.PHONE_PORT) {
        check(socket == null) { "already started" }
        val s = DatagramSocket(null).apply {
            reuseAddress = true
            // A camera burst is up to ~25 chunks of ≤1438 B; leave room for several frames plus ToF/BEAT.
            receiveBufferSize = 512 * 1024
            bind(InetSocketAddress(port))
        }
        socket = s
        thread = Thread({ receiveLoop(s) }, "determine-udp-rx").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(MAX_DATAGRAM)
        val packet = DatagramPacket(buf, buf.size)
        while (!s.isClosed) {
            try {
                packet.setLength(buf.size)
                s.receive(packet)
                val rxUs = clockUs()
                val len = packet.length
                val board = addressBook.attribute(Wire.senderBoard(buf, len), packet.address)
                if (board == null) {
                    onUnattributed()
                    continue
                }
                onPacket(board, buf, len, rxUs)
            } catch (e: SocketException) {
                if (!s.isClosed) Log.w(TAG, "receive failed", e)
            } catch (e: IOException) {
                Log.w(TAG, "receive failed", e)
            }
        }
    }

    override fun send(board: BoardId, packet: ByteArray) {
        val s = socket ?: return
        val dest = addressBook.destination(board)
        if (dest == null) {
            // Board not heard yet: nothing to address. The engine's liveness rules already treat a silent
            // board as unavailable (Arch §4.2), so this is counted, not escalated here.
            sendsWithoutAddress++
            return
        }
        try {
            sendPacket.setData(packet, 0, packet.size)
            sendPacket.socketAddress = dest
            s.send(sendPacket)
        } catch (e: IOException) {
            // A failed CUE surfaces as a missing ACK inside the engine → UNAVAILABLE (Arch §4.2).
            sendErrors++
            Log.w(TAG, "send to $board failed", e)
        }
    }

    fun stop() {
        socket?.close()
        socket = null
        thread?.join(500)
        thread = null
    }

    private companion object {
        const val TAG = "UdpLink"
        const val MAX_DATAGRAM = 1500
    }
}
