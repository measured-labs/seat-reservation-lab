package com.jun.labs.seatreservation.adr019

import com.fasterxml.jackson.databind.JsonNode
import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.PIN_CLEANUP_OFF
import com.jun.labs.seatreservation.support.PIN_C_OFF
import com.jun.labs.seatreservation.support.PIN_FLYWAY_BASE
import com.jun.labs.seatreservation.support.PIN_INC_OFF
import com.jun.labs.seatreservation.support.PIN_METHOD_NONE
import com.jun.labs.seatreservation.support.PIN_SAFETY_OFF
import com.jun.labs.seatreservation.support.PIN_SHAPE_ONE_SHOT
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.context.annotation.Import
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/*
 * S7 상태 푸시(SSE) — `GET /api/schedules/{id}/seats/stream`(test-contract §2):
 *  첫 이벤트 `snapshot`(GET과 같은 배열) → 그 뒤 `change`([{seatId, available}] — 보낼 때의 보이는 상태, 같은 좌석이 여러 번 올 수 있고 마지막 값이 지금 상태) ·
 *  회차 없음 404 · 끊으면 서버 구독 정리(`seat.seatmap.push.connections` 감소) · DV-4(구독을 먼저 걸고 첫 맵을 보낸 뒤 그 사이 변경을 보낸다).
 * 컨텍스트 = Adr019EventContractTest와 같은 키(같은 컨텍스트를 쓴다). 푸시 끔 404는 Adr019NoneContractTest.
 * SSE는 실제 포트(HttpURLConnection — [SseClient])로, 쓰기는 MockMvc(같은 앱 빈)로 한다.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        P19_SCHED_OFF, P19_COMP_SCHED_OFF, P19_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT,
        PIN_FLYWAY_BASE, P19_GROUP_ON, P19_EVENT, P19_PUSH_ON,
    ],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
class Adr019PushContractTest : Adr019Base() {

    private fun open(scheduleId: Long = s): SseClient = SseClient(streamUrl(scheduleId))

    private fun snapshotOf(c: SseClient): JsonNode {
        assertEquals(200, c.awaitStatus(), "stream 200")
        val ev = assertNotNull(c.next(15), "첫 이벤트를 받지 못했다(error=${c.error})")
        assertEquals("snapshot", ev.name, "첫 이벤트는 snapshot(S7): $ev")
        val node = objectMapper.readTree(ev.data)
        parseSeatMap(objectMapper, ev.data, "snapshot") // 모양 = GET 배열과 같다(필드 다섯 · 오름차순)
        return node
    }

    /** change 이벤트를 [state]에 순서대로 적용한다. 모양(배열 · 원소 {seatId, available})도 단언한다. */
    private fun applyChange(ev: SseClient.Event, state: MutableMap<Long, Boolean>) {
        assertEquals("change", ev.name, "snapshot 뒤 이벤트는 change뿐이다(S7): $ev")
        val node = objectMapper.readTree(ev.data)
        assertTrue(node.isArray, "change data는 배열: $ev")
        node.forEach { e ->
            assertEquals(CHANGE_FIELDS, e.fieldNames().asSequence().toSet(), "change 원소 = {seatId, available}: $e")
            assertTrue(e.get("available").isBoolean, "available bool: $e")
            state[e.get("seatId").asLong()] = e.get("available").asBoolean()
        }
    }

    /** [seat]의 마지막 값이 [want]가 될 때까지 change를 받는다 — 받은 값은 [state]에 쌓는다 */
    private fun awaitSeat(c: SseClient, state: MutableMap<Long, Boolean>, seat: Long, want: Boolean, why: String, seconds: Long = 10) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (state[seat] != want) {
            val left = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime())
            if (left <= 0) fail("$why — 좌석 $seat 의 마지막 값이 $want 가 되지 않았다(지금 ${state[seat]} · error=${c.error})")
            val ev = c.events.poll(left, TimeUnit.MILLISECONDS) ?: continue
            applyChange(ev, state)
        }
    }

    /** 이미 받은 이벤트를 [ms] 동안 더 받아 적용한다(마지막 값이 뒤집히지 않는지 보기) */
    private fun drain(c: SseClient, state: MutableMap<Long, Boolean>, ms: Long = 700) {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
        while (true) {
            val left = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime())
            if (left <= 0) return
            val ev = c.events.poll(left, TimeUnit.MILLISECONDS) ?: return
            applyChange(ev, state)
        }
    }

    @Test
    fun `S7 — 연결 직후 snapshot(GET과 같은 배열) → 선점 · 해제마다 change · 마지막 값 = 지금 상태`() {
        val a = seat()
        val b = seat()
        val held = seat()
        holdOk(held, 300)
        open().use { c ->
            val snap = snapshotOf(c)
            assertEquals(objectMapper.readTree(seatMapRaw().body), snap, "snapshot data = 같은 시점 GET 배열(S7 · S1)")
            val state = parseSeatMap(objectMapper, snap.toString()).availability().toMutableMap()
            assertEquals(mapOf(a to true, b to true, held to false), state)

            val h = holdOk(a, 100)
            awaitSeat(c, state, a, false, "선점 뒤 change")
            assertEquals(204, releaseHold(h, 100).status)
            awaitSeat(c, state, a, true, "해제 뒤 change")
            drain(c, state)
            assertEquals(dbTruth(), state, "snapshot + change를 순서대로 적용한 맵 = 지금 상태")
        }
    }

    @Test
    fun `S7 — 회차 없음은 스트림을 열기 전에 404`() {
        open(NO_SUCH_SCHEDULE).use { c ->
            assertEquals(404, c.awaitStatus(), "회차 없음 = 404(test-contract §2)")
            assertTrue(c.events.isEmpty(), "이벤트를 보내지 않는다")
        }
    }

    @Test
    fun `S7 — 다른 회차의 변경은 이 회차 스트림의 마지막 값을 바꾸지 않는다`() {
        val a = seat()
        val other = productScheduleRepository.save(
            com.jun.labs.seatreservation.domain.ProductSchedule(productId = schedule.productId, startsAt = schedule.startsAt),
        )
        val x = productSeatRepository.save(
            com.jun.labs.seatreservation.domain.ProductSeat(scheduleId = other.id!!, section = "A", rowNo = 1, seatNo = 1),
        ).id!!
        open().use { c ->
            val state = parseSeatMap(objectMapper, snapshotOf(c).toString()).availability().toMutableMap()
            holdOk(x, 100, other.id!!)
            holdOk(a, 200)
            awaitSeat(c, state, a, false, "이 회차 선점")
            drain(c, state)
            assertEquals(mapOf(a to false), state, "이 회차 좌석만 온다")
        }
    }

    @Test
    fun `S7 · S4′ DV-4 — 첫 맵을 읽은 뒤 보내기 전에 멈춘 사이 선점이 커밋되면 클라이언트는 그 좌석의 최종 false를 받는다`() {
        val a = seat()
        val b = seat()
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val once = AtomicBoolean(false)
        SeatMapHooks.afterSnapshotRead { id ->
            if (id == s && once.compareAndSet(false, true)) {
                reached.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        try {
            open().use { c ->
                assertTrue(reached.await(15, TimeUnit.SECONDS), "SSE 연결이 afterSnapshotRead 훅을 부르지 않았다(test-contract §4)")
                val holding = pool.submit<Long> { holdOk(a, 100) } // 쓰기 경로가 스트림을 기다려도 시험이 멈추지 않게
                runCatching { holding.get(5, TimeUnit.SECONDS) }
                release.countDown()
                holding.get(30, TimeUnit.SECONDS)

                val snap = snapshotOf(c)
                val state = parseSeatMap(objectMapper, snap.toString()).availability().toMutableMap()
                awaitSeat(c, state, a, false, "DV-4 — 첫 맵과 구독 사이의 변경을 놓쳤다(구독을 먼저 걸어야 한다)")
                drain(c, state)
                assertEquals(mapOf(a to false, b to true), state, "클라이언트가 마지막으로 가진 값 = 지금 상태")
            }
        } finally {
            release.countDown()
            SeatMapHooks.afterSnapshotRead(null)
        }
    }

    @Test
    fun `S7 — 끊긴 클라이언트의 구독은 정리된다(connections 게이지 +1 뒤 원래대로)`() {
        val a = seat()
        val c = open()
        val peak: Double
        try {
            snapshotOf(c)
            awaitTrue(5, "연결 뒤 $M19_PUSH_CONNECTIONS ≥ 1") { pushConnections() >= 1.0 }
            peak = pushConnections()
        } finally {
            c.close()
        }
        // 서버는 보낼 때 끊김을 알 수 있다 — 이 회차에 변경을 만들어 쓰기를 일으킨다(선점 ↔ 해제를 번갈아)
        // (앞 시험이 남긴 다른 회차 연결이 그사이 정리돼도 맞게 '연결 때 값 − 1 이하'로 본다)
        var holdId: Long? = null
        awaitTrue(20, "끊은 뒤 $M19_PUSH_CONNECTIONS ≤ ${peak - 1}(구독 정리 — S7)", poke = {
            val h = holdId
            holdId = if (h == null) holdOk(a, 100) else { releaseHold(h, 100); null }
        }) { pushConnections() <= peak - 1 }
        holdId?.let { releaseHold(it, 100) }
    }
}
