package com.jun.labs.seatreservation.adr019

import com.fasterxml.jackson.databind.ObjectMapper
import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.adr015.P_A_ON
import com.jun.labs.seatreservation.adr015.P_LOC_BASE
import com.jun.labs.seatreservation.adr015.P_SCHED_OFF
import com.jun.labs.seatreservation.adr017.Adr017DsBase
import com.jun.labs.seatreservation.adr017.DS_MODE_SUBSTITUTE
import com.jun.labs.seatreservation.adr017.DS_SCHEDULER_OFF
import com.jun.labs.seatreservation.domain.ProductSeat
import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.PIN_CLEANUP_OFF
import com.jun.labs.seatreservation.support.PIN_C_OFF
import com.jun.labs.seatreservation.support.PIN_INC_OFF
import com.jun.labs.seatreservation.support.PIN_METHOD_NONE
import com.jun.labs.seatreservation.support.PIN_SAFETY_OFF
import com.jun.labs.seatreservation.support.PIN_SHAPE_ONE_SHOT
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/*
 * S4 · S4′ 대체 배정 경로(test-contract §5 — `seat.compensation.mode=substitute`, adr017 시험 지원 코드 방식) — event 모드에서
 * 결제 뒤 확정 거부 → 대체 배정이 커밋되면 원래 좌석과 새 좌석 둘 다 다음 GET에 반영된다. 대체 배정은 빈 좌석을 바로 RESERVED로 만든다
 * (AVAILABLE → RESERVED — 설계-1 '방향을 가리지 않는 갱신').
 * 컨텍스트 = Adr017SSubstituteTest와 같은 키 + event(흉내 PG 같은 JVM).
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        P_SCHED_OFF, DS_SCHEDULER_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT,
        P_LOC_BASE, DS_MODE_SUBSTITUTE, P19_EVENT,
    ],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
class Adr019SubstituteInvalidationTest : Adr017DsBase() {

    private val om = ObjectMapper()

    private fun seatIn(section: String, seatNo: Int): Long =
        productSeatRepository.save(ProductSeat(scheduleId = schedule.id!!, section = section, rowNo = 1, seatNo = seatNo, status = SeatStatus.AVAILABLE)).id!!

    private fun cacheHits(): Double = meters.find(M19_REQUESTS).tag("source", "cache").counters().sumOf { it.count() }

    private fun mapNow(why: String): Map<Long, Boolean> {
        val r = getHttp("/api/schedules/${schedule.id}/seats", null)
        assertEquals(200, r.status, "$why — 좌석 맵 200: $r")
        return parseSeatMap(om, r.body, why).availability()
    }

    private fun prime(): Map<Long, Boolean> {
        mapNow("캐시 준비 1")
        val c0 = cacheHits()
        val m = mapNow("캐시 준비 2")
        assertEquals(c0 + 1, cacheHits(), "준비 — 두 번째 GET은 캐시에서 답해야 한다(source=cache)")
        return m
    }

    private fun truth(): Map<Long, Boolean> =
        jdbcTemplate.queryForList("SELECT id, status FROM product_seat WHERE schedule_id = ? ORDER BY id", schedule.id)
            .associate { (it["id"] as Number).toLong() to (it["status"] == "AVAILABLE") }

    private fun paid(seatId: Long, user: Long): Paid = startedHold(seatId, user).also { pgPayOk(it.uid) }

    @Test
    fun `S4 ⑤ 대체 배정(409 길 — 배치 전) — 원래 좌석은 풀리고 새 좌석은 RESERVED · 다음 GET이 둘 다 반영`() {
        val free = seatIn("A", 1) // id가 작은 빈 좌석 — 원래 좌석과 다른 좌석이 골라지기 쉽게(어느 좌석인지는 단언하지 않는다)
        val orig = seatIn("A", 2)
        val p = paid(orig, x)
        clock.set(p.expiresAt.plusSeconds(1))
        assertEquals(mapOf(free to true, orig to false), prime())

        val r = confirmHttp(p.holdId, p.user, p.uid)
        assertEquals(409, r.status, "만료 뒤 확정 → 409(원래 거부 코드 그대로): $r")
        val comp = compOrFail(r, "대체 배정")
        assertEquals("SUBSTITUTE", comp.type, "대체 배정 성공: $r")
        val newSeat = assertNotNull(comp.seatId)

        val after = mapNow("대체 배정 뒤")
        assertEquals(truth(), after, "대체 배정 커밋 뒤 다음 GET = 지금 상태(원래 좌석 · 새 좌석 둘 다 — S4)")
        assertEquals(false, after[newSeat], "새 좌석 RESERVED → false")
    }

    @Test
    fun `S4′ 대체 배정(404 길 — 배치 뒤) — 빈 좌석이 바로 RESERVED가 되는 AVAILABLE → RESERVED도 무효화한다`() {
        val orig = seatIn("A", 1)
        val other = seatIn("A", 2)
        val p = paid(orig, x)
        clock.set(p.expiresAt.plusSeconds(1))
        assertEquals(1, expire.expire(), "준비 — 만료 배치가 원래 홀드를 지운다")
        holdOk(orig, y) // 원래 좌석은 다른 사용자가 가져감 → section A의 빈 좌석은 other 하나
        assertEquals(mapOf(orig to false, other to true), prime())

        val r = confirmHttp(p.holdId, p.user, p.uid)
        assertEquals(404, r.status, "배치 뒤 확정 → 404: $r")
        val comp = compOrFail(r, "대체 배정")
        assertEquals("SUBSTITUTE", comp.type, "대체 배정 성공: $r")
        assertEquals(other, comp.seatId, "남은 빈 좌석이 대체 좌석")

        assertEquals(mapOf(orig to false, other to false), mapNow("대체 배정 뒤"), "AVAILABLE → RESERVED가 다음 GET에 보인다(DV-2)")
    }
}
