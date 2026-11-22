package com.jun.labs.seatreservation.adr019impl

import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.assertTrue

/** P2 — 시험 헬퍼 readUntilEof: read() == -1만 EOF다. 연결 재설정(RST) 같은 I/O 오류는 EOF로 보지 않는다(그린 위장 방지) */
class SocketEndHelperTest {

    @Test
    fun `서버가 정상 종료하면 EOF · 연결 재설정(I O 오류)은 EOF가 아니다`() {
        ServerSocket(0).use { server ->
            val closer = thread { server.accept().use { it.getOutputStream().write("bye".toByteArray()) } }
            Socket("localhost", server.localPort).use { c ->
                c.soTimeout = 200
                val end = readUntilEof(c, 5)
                assertTrue(end is SocketEnd.Eof && end.text == "bye", "정상 종료 = EOF: $end")
            }
            closer.join()
        }
        // I/O 오류: 읽기가 IOException으로 끝나는 소켓(연결 재설정 등을 결정적으로 흉내)
        val broken = object : Socket() {
            override fun getInputStream(): java.io.InputStream = object : java.io.InputStream() {
                override fun read(): Int = throw java.io.IOException("주입: Connection reset")
                override fun read(b: ByteArray, off: Int, len: Int): Int = throw java.io.IOException("주입: Connection reset")
            }
        }
        val end = readUntilEof(broken, 5)
        assertTrue(end is SocketEnd.Error, "I/O 오류 = EOF 아님: $end")
    }
}
