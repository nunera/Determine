package org.determine.app.net

import org.determine.core.BoardId
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Learns each board's sender endpoint from identity-bearing packets and uses it to attribute PONGs, which
 * contain no board identity. Endpoint matching also supports two simulated boards on one host: their source
 * ports differ even though their IP is the same.
 *
 * The learned source port is only for attribution. Destination ports stay fixed (CUE 47001, CAM 47002).
 *
 * The receive thread is the only writer; [destination] is read from the engine thread, hence @Volatile.
 */
class BoardAddressBook(
    private val cuePort: Int = CUE_PORT,
    private val camPort: Int = CAM_PORT,
) {
    @Volatile private var cam: InetSocketAddress? = null
    @Volatile private var cue: InetSocketAddress? = null
    @Volatile private var camSource: InetSocketAddress? = null
    @Volatile private var cueSource: InetSocketAddress? = null

    /**
     * Returns which board sent this datagram, or null if it cannot be attributed. [declared] is the board
     * named by the packet itself (`Wire.senderBoard`): null for PONG and invalid packets, which are then
     * attributed by the learned source endpoint.
     */
    fun attribute(declared: BoardId?, source: InetAddress, sourcePort: Int): BoardId? {
        if (declared != null) {
            learn(declared, source, sourcePort)
            return declared
        }
        return when {
            cueSource?.let { it.address == source && it.port == sourcePort } == true -> BoardId.CUE
            camSource?.let { it.address == source && it.port == sourcePort } == true -> BoardId.CAM
            else -> null
        }
    }

    private fun learn(board: BoardId, source: InetAddress, sourcePort: Int) {
        when (board) {
            BoardId.CAM -> {
                if (cam?.address != source) cam = InetSocketAddress(source, camPort)
                if (camSource?.let { it.address == source && it.port == sourcePort } != true) {
                    camSource = InetSocketAddress(source, sourcePort)
                }
                // Only the exact sender endpoint can take over another board's identity.
                if (cueSource == camSource) { cue = null; cueSource = null }
            }
            BoardId.CUE -> {
                if (cue?.address != source) cue = InetSocketAddress(source, cuePort)
                if (cueSource?.let { it.address == source && it.port == sourcePort } != true) {
                    cueSource = InetSocketAddress(source, sourcePort)
                }
                if (camSource == cueSource) { cam = null; camSource = null }
            }
        }
    }

    /** Where to send packets for [board], or null while the board has not announced itself. */
    fun destination(board: BoardId): InetSocketAddress? = when (board) {
        BoardId.CAM -> cam
        BoardId.CUE -> cue
    }

    fun clear() {
        cam = null
        cue = null
        camSource = null
        cueSource = null
    }

    companion object {
        const val PHONE_PORT = 47000
        const val CUE_PORT = 47001
        const val CAM_PORT = 47002
    }
}
