package com.jun.labs.seatreservation.adr019impl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.api.USER_ID_HEADER
import com.jun.labs.seatreservation.service.impl.seatmap.SeatMapTestHooks
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.PIN_A_OFF
import com.jun.labs.seatreservation.support.PIN_CLEANUP_OFF
import com.jun.labs.seatreservation.support.PIN_C_OFF
import com.jun.labs.seatreservation.support.PIN_FLYWAY_BASE
import com.jun.labs.seatreservation.support.PIN_INC_OFF
import com.jun.labs.seatreservation.support.PIN_METHOD_NONE
import com.jun.labs.seatreservation.support.PIN_SAFETY_OFF
import com.jun.labs.seatreservation.support.PIN_SHAPE_ONE_SHOT
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 상태 푸시(S7 · DV-4) — 실제 서버(RANDOM_PORT) · 소켓으로 SSE를 읽는다. 조회 모드는 기본 none(푸시는 모드와 별개 손잡이).
 * 연결 직후 snapshot(GET과 같은 배열) · 그 뒤 change(바뀐 좌석의 보낼 때 상태) · 구독을 먼저 걸어 첫 맵과 그 사이 변경을 놓치지 않는다 · 끊으면 구독 정리.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "seat.hold.expiry-scheduler-enabled=false", "seat.compensation.scheduler-enabled=false", PIN_A_OFF, PIN_C_OFF, PIN_CLEANUP_OFF,
        PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, PIN_FLYWAY_BASE, PUSH_ON,
    ],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
class SeatMapPushTest : IntegrationTest() {
    @LocalServerPort var port: Int = 0
    @Autowired lateinit var meters: MeterRegistry

    private val mapper = ObjectMapper()
    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    private val s: Long get() = schedule.id!!

    @AfterEach
    fun clearHooks() {
        SeatMapTestHooks.afterSnapshotRead = null
    }

    private fun hold(seatId: Long, userId: Long) {
        val r = http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/api/schedules/$s/seats/$seatId/hold"))
                .header(USER_ID_HEADER, userId.toString()).POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(201, r.statusCode(), r.body())
    }

    private fun connections(): Double = meters.find(M_CONNECTIONS).gauge()?.value() ?: -1.0

    /** 소켓 SSE 클라이언트 — 요청을 보내 두고, [status] · [next]는 응답 머리가 온 뒤 읽는다(청크 인코딩을 푼다) */
    private inner class Sse(scheduleId: Long) : AutoCloseable {
        private val socket = Socket("localhost", port).apply { soTimeout = 15_000 }
        private val raw = socket.getInputStream()
        private var reader: BufferedReader? = null
        var status = 0
            private set

        init {
            socket.getOutputStream().apply {
                write("GET /api/schedules/$scheduleId/seats/stream HTTP/1.1\r\nHost: localhost\r\nAccept: text/event-stream\r\n\r\n".toByteArray())
                flush()
            }
        }

        fun open(): Sse {
            status = line(raw).split(" ")[1].toInt()
            var chunked = false
            while (true) {
                val h = line(raw)
                if (h.isEmpty()) break
                if (h.lowercase().startsWith("transfer-encoding:") && "chunked" in h.lowercase()) chunked = true
            }
            reader = BufferedReader(InputStreamReader(if (chunked) Dechunk(raw) else raw, Charsets.UTF_8))
            return this
        }

        /** 다음 이벤트(이름 · data JSON) — 주석(하트비트)은 건너뛴다 */
        fun next(): Pair<String, JsonNode> {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            var name: String? = null
            var data: String? = null
            while (true) {
                check(System.nanoTime() < until) { "15초 안에 이벤트가 오지 않았다(하트비트만)" }
                val l = reader!!.readLine() ?: error("스트림이 끝났다")
                when {
                    l.startsWith("event:") -> name = l.removePrefix("event:").trim()
                    l.startsWith("data:") -> data = l.removePrefix("data:")
                    l.isEmpty() && data != null -> return (name ?: "message") to mapper.readTree(data)
                }
            }
        }

        override fun close() = socket.close()
    }

    private fun line(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    /** HTTP/1.1 청크 인코딩 풀기 — 여러 바이트 읽기는 지금 청크에 남은 만큼만 한 번 읽는다(기본 구현은 버퍼가 찰 때까지 막힌다) */
    private inner class Dechunk(private val input: InputStream) : InputStream() {
        private var left = 0

        private fun nextChunk(): Boolean {
            if (left > 0) return true
            var size = line(input)
            if (size.isEmpty()) size = line(input)
            left = size.substringBefore(';').trim().toInt(16)
            return left > 0
        }

        override fun read(): Int {
            if (!nextChunk()) return -1
            left--
            return input.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!nextChunk()) return -1
            val n = input.read(b, off, minOf(len, left))
            if (n > 0) left -= n
            return n
        }
    }

    private fun JsonNode.availability(): Map<Long, Boolean> = associate { it.get("seatId").asLong() to it.get("available").asBoolean() }

    @Test
    fun `연결 직후 snapshot = GET과 같은 배열 · 선점하면 change로 바뀐 좌석만`() {
        val a = createSeat(1).id!!
        val b = createSeat(2).id!!
        Sse(s).use { sse ->
            assertEquals(200, sse.open().status)
            val (name, snapshot) = sse.next()
            assertEquals("snapshot", name)
            assertEquals(mapOf(a to true, b to true), snapshot.availability())
            assertEquals(setOf("seatId", "section", "rowNo", "seatNo", "available"), snapshot.first().fieldNames().asSequence().toSet())
            hold(a, 100)
            val (n2, change) = sse.next()
            assertEquals("change", n2)
            assertEquals(mapOf(a to false), change.availability(), "바뀐 좌석만 · 보낼 때의 상태")
            assertEquals(setOf("seatId", "available"), change.first().fieldNames().asSequence().toSet())
        }
    }

    @Test
    fun `DV-4 — 첫 맵을 읽은 뒤 · 보내기 전에 커밋된 선점도 change로 온다(구독 먼저)`() {
        val a = createSeat(1).id!!
        val read = CountDownLatch(1)
        val release = CountDownLatch(1)
        SeatMapTestHooks.afterSnapshotRead = { id -> if (id == s) { read.countDown(); release.await(30, TimeUnit.SECONDS) } }
        Sse(s).use { sse ->
            assertTrue(read.await(30, TimeUnit.SECONDS), "연결이 첫 맵을 읽고 멈춘다")
            hold(a, 100)
            release.countDown()
            assertEquals(200, sse.open().status)
            val (name, snapshot) = sse.next()
            assertEquals("snapshot", name)
            assertEquals(mapOf(a to true), snapshot.availability(), "첫 맵은 선점 전에 읽었다")
            val (n2, change) = sse.next()
            assertEquals("change", n2)
            assertEquals(mapOf(a to false), change.availability(), "그 사이의 선점을 놓치지 않는다")
        }
    }

    @Test
    fun `끊으면 구독이 정리된다 — 연결 수 지표가 돌아온다`() {
        createSeat(1)
        awaitConnections(0.0, "앞 시험이 닫은 연결이 먼저 정리된다")
        val sse = Sse(s).open()
        sse.next()
        assertEquals(1.0, connections())
        sse.close()
        awaitConnections(0.0, "끊긴 연결이 정리되지 않았다")
    }

    private fun awaitConnections(expected: Double, why: String) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (connections() != expected) {
            assertTrue(System.nanoTime() < until, "$why: ${connections()}")
            Thread.sleep(100)
        }
    }

    @Test
    fun `회차 없음은 스트림을 열기 전에 404`() {
        val before = connections()
        Sse(987_654_321).use { assertEquals(404, it.open().status) }
        assertEquals(before, connections(), "구독이 남지 않는다")
    }
}
