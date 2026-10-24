package org.determine.android.net

import org.determine.core.BoardId
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Learns each board's IP from its packets (PROTOCOL.md "Transport": the phone learns each board's IP from
 * its first packet) and attributes packets that carry no board identity (PONG) by source address.
 *
 * Boards send from an ephemeral source port but listen on a fixed port (CUE 47001, CAM 47002), so only the
 * IP is learned; the destination port always comes from the protocol table.
 *
 * The receive thread is the only writer; [destination] is read from the engine thread, hence @Volatile.
 */
class BoardAddressBook(
    private val cuePort: Int = CUE_PORT,
    private val camPort: Int = CAM_PORT,
) {
    @Volatile private var cam: InetSocketAddress? = null
    @Volatile private var cue: InetSocketAddress? = null

    /**
     * Returns which board sent this datagram, or null if it cannot be attributed. [declared] is the board
     * named by the packet itself (`Wire.senderBoard`): null for PONG and invalid packets, which are then
     * attributed by the learned source IP.
     */
    fun attribute(declared: BoardId?, source: InetAddress): BoardId? {
        if (declared != null) {
            learn(declared, source)
            return declared
        }
        return when (source) {
            cue?.address -> BoardId.CUE
            cam?.address -> BoardId.CAM
            else -> null
        }
    }

    private fun learn(board: BoardId, source: InetAddress) {
        when (board) {
            BoardId.CAM -> {
                if (cam?.address != source) cam = InetSocketAddress(source, camPort)
                // A board that rebooted onto an address the other board used to hold takes it over;
                // otherwise PONGs from that IP would feed the wrong board's clock estimate.
                if (cue?.address == source) cue = null
            }
            BoardId.CUE -> {
                if (cue?.address != source) cue = InetSocketAddress(source, cuePort)
                if (cam?.address == source) cam = null
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
    }

    companion object {
        const val PHONE_PORT = 47000
        const val CUE_PORT = 47001
        const val CAM_PORT = 47002
    }
}
