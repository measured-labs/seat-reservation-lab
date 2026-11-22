package com.jun.labs.seatreservation.adr019impl

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
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals

/**
 * event 모드 × 대체 배정(ADR-017 S — DV-2): 원래 좌석(HELD → AVAILABLE)과 새 좌석(AVAILABLE → RESERVED) 둘 다 다음 GET에 반영된다.
 * 새 좌석은 같은 section의 가장 작은 id AVAILABLE이므로, 원래 좌석을 더 큰 id로 두어 두 좌석이 갈리게 한다.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        P_SCHED_OFF, DS_SCHEDULER_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT,
        P_LOC_BASE, DS_MODE_SUBSTITUTE, MAP_EVENT,
    ],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
class SeatMapSubstituteEventTest : Adr017DsBase() {
    private val mapper = ObjectMapper()

    private fun seatIn(seatNo: Int): Long =
        productSeatRepository.save(ProductSeat(scheduleId = schedule.id!!, section = "A", rowNo = 1, seatNo = seatNo, status = SeatStatus.AVAILABLE)).id!!

    private fun map(): Map<Long, Boolean> {
        val r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:$port/api/schedules/${schedule.id}/seats")).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, r.statusCode(), r.body())
        return mapper.readTree(r.body()).associate { it.get("seatId").asLong() to it.get("available").asBoolean() }
    }

    private fun count(source: String) = meters.find(M_REQUESTS).tags("mode", "event", "source", source).counter()?.count() ?: 0.0

    @Test
    fun `대체 배정 — 원래 좌석은 true · 새 좌석은 false로 다음 GET에 반영(방향 무관 무효화)`() {
        val substitute = seatIn(1)
        val original = seatIn(2)
        val p = startedHold(original, x).also { pgPayOk(it.uid) }
        assertEquals(mapOf(substitute to true, original to false), map())
        val hits = count("cache")
        assertEquals(mapOf(substitute to true, original to false), map())
        assertEquals(hits + 1, count("cache"), "캐시가 찼다")

        clock.set(p.expiresAt.plusSeconds(1)) // 409 HOLD_EXPIRED 길(배치 전 — 원래 홀드 행이 남아 있다)
        val r = confirmHttp(p.holdId, p.user, p.uid)
        assertEquals(409, r.status, "$r")
        assertEquals("SUBSTITUTE", r.json!!.get("compensation").get("type").asText(), "$r")
        assertEquals(substitute, r.json!!.get("compensation").get("seatId").asLong(), "새 좌석 = 같은 section의 가장 작은 id: $r")

        val db = count("db")
        assertEquals(mapOf(substitute to false, original to true), map(), "두 좌석 모두 반영")
        assertEquals(db + 1, count("db"), "대체 배정 커밋 뒤 첫 GET은 DB")
        assertEquals(listOf("RESERVED", "AVAILABLE"), listOf(substitute, original).map { seatStatusOf(it) })
    }
}
