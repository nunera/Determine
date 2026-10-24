package org.determine.android.net

import org.determine.core.BoardId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress

class BoardAddressBookTest {
    private val book = BoardAddressBook()
    private val camIp = InetAddress.getByName("192.168.43.20")
    private val cueIp = InetAddress.getByName("192.168.43.21")

    @Test
    fun nothingIsAddressableOrAttributableBeforeBoardsAnnounce() {
        assertNull(book.destination(BoardId.CUE))
        assertNull(book.destination(BoardId.CAM))
        assertNull("a PONG from an unknown sender cannot be attributed", book.attribute(null, cueIp))
    }

    @Test
    fun learnsIpsAndUsesProtocolPorts() {
        assertEquals(BoardId.CAM, book.attribute(BoardId.CAM, camIp))
        assertEquals(BoardId.CUE, book.attribute(BoardId.CUE, cueIp))
        assertEquals(InetSocketAddress(camIp, 47002), book.destination(BoardId.CAM))
        assertEquals(InetSocketAddress(cueIp, 47001), book.destination(BoardId.CUE))
    }

    @Test
    fun pongIsAttributedBySourceIp() {
        book.attribute(BoardId.CAM, camIp)
        book.attribute(BoardId.CUE, cueIp)
        assertEquals(BoardId.CAM, book.attribute(null, camIp))
        assertEquals(BoardId.CUE, book.attribute(null, cueIp))
        assertNull(book.attribute(null, InetAddress.getByName("192.168.43.99")))
    }

    @Test
    fun boardMovingToNewIpIsFollowed() {
        book.attribute(BoardId.CUE, cueIp)
        val moved = InetAddress.getByName("192.168.43.30")
        book.attribute(BoardId.CUE, moved)
        assertEquals(InetSocketAddress(moved, 47001), book.destination(BoardId.CUE))
        assertNull("old IP no longer belongs to the cue board", book.attribute(null, cueIp))
    }

    @Test
    fun ipTakenOverByOtherBoardIsReassigned() {
        book.attribute(BoardId.CAM, camIp)
        // Camera board went away; cue board rebooted and got the camera's old lease.
        book.attribute(BoardId.CUE, camIp)
        assertEquals(BoardId.CUE, book.attribute(null, camIp))
        assertNull("camera must not keep an address now owned by the cue board", book.destination(BoardId.CAM))
    }
}
