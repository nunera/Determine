package org.determine.app.net

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
    private val sourcePort = 50000

    @Test
    fun nothingIsAddressableOrAttributableBeforeBoardsAnnounce() {
        assertNull(book.destination(BoardId.CUE))
        assertNull(book.destination(BoardId.CAM))
        assertNull("a PONG from an unknown sender cannot be attributed", book.attribute(null, cueIp, sourcePort))
    }

    @Test
    fun learnsIpsAndUsesProtocolPorts() {
        assertEquals(BoardId.CAM, book.attribute(BoardId.CAM, camIp, sourcePort))
        assertEquals(BoardId.CUE, book.attribute(BoardId.CUE, cueIp, sourcePort))
        assertEquals(InetSocketAddress(camIp, 47002), book.destination(BoardId.CAM))
        assertEquals(InetSocketAddress(cueIp, 47001), book.destination(BoardId.CUE))
    }

    @Test
    fun pongIsAttributedBySourceEndpoint() {
        book.attribute(BoardId.CAM, camIp, sourcePort)
        book.attribute(BoardId.CUE, cueIp, sourcePort)
        assertEquals(BoardId.CAM, book.attribute(null, camIp, sourcePort))
        assertEquals(BoardId.CUE, book.attribute(null, cueIp, sourcePort))
        assertNull(book.attribute(null, cueIp, sourcePort + 1))
        assertNull(book.attribute(null, InetAddress.getByName("192.168.43.99"), sourcePort))
    }

    @Test
    fun boardMovingToNewIpIsFollowed() {
        book.attribute(BoardId.CUE, cueIp, sourcePort)
        val moved = InetAddress.getByName("192.168.43.30")
        book.attribute(BoardId.CUE, moved, sourcePort)
        assertEquals(InetSocketAddress(moved, 47001), book.destination(BoardId.CUE))
        assertNull("old IP no longer belongs to the cue board", book.attribute(null, cueIp, sourcePort))
    }

    @Test
    fun ipTakenOverByOtherBoardIsReassigned() {
        book.attribute(BoardId.CAM, camIp, sourcePort)
        // Camera board went away; cue board rebooted and took its exact sender endpoint.
        book.attribute(BoardId.CUE, camIp, sourcePort)
        assertEquals(BoardId.CUE, book.attribute(null, camIp, sourcePort))
        assertNull("camera must not keep an address now owned by the cue board", book.destination(BoardId.CAM))
    }

    @Test
    fun bothSimulatedBoardsCanShareLocalhost() {
        val localhost = InetAddress.getByName("127.0.0.1")
        book.attribute(BoardId.CAM, localhost, 47002)
        book.attribute(BoardId.CUE, localhost, 47001)
        assertEquals(BoardId.CAM, book.attribute(null, localhost, 47002))
        assertEquals(BoardId.CUE, book.attribute(null, localhost, 47001))
        assertEquals(InetSocketAddress(localhost, 47002), book.destination(BoardId.CAM))
        assertEquals(InetSocketAddress(localhost, 47001), book.destination(BoardId.CUE))
    }
}
