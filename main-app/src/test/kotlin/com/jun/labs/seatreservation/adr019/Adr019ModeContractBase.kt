package com.jun.labs.seatreservation.adr019

import com.jun.labs.seatreservation.domain.SeatStatus
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * S1 두 상태 · S2 응답 모양(404 · 빈 회차) — 네 모드(none · ttl · redis · event)가 같은 테스트를 돈다.
 * 하위 클래스는 모드 키만 바꾼다([modeValue]). 캐시 모드에서도 첫 GET 전에 데이터를 다 만들므로 캐시 낡음과 무관하다.
 * 만료 방식은 기본 sweep(보이는 상태 = 저장 상태 — ADR-014 A 판정 그대로).
 */
abstract class Adr019ModeContractBase : Adr019Base() {

    /** `seat.seat-map.mode` 값 */
    abstract val modeValue: String

    @Test
    fun `S1 — 필드 정확히 다섯 개 · seatId 오름차순 · AVAILABLE만 true · HELD(실제 선점 · 저장 HELD · 배치 전 만료 홀드) · RESERVED는 false`() {
        val free = seat()
        val storedHeld = seat(SeatStatus.HELD)
        val reserved = seat(SeatStatus.RESERVED)
        val held = seat()
        holdOk(held, 100)
        val expired = seat()
        holdOk(expired, 200)
        clock.advance(HOLD_TTL.plus(Duration.ofMinutes(1))) // 앱 시계로 만료 — 배치 전이라 저장 HELD 그대로(sweep)
        val free2 = seat()

        val m = seatMap(why = "S1")
        assertEquals(listOf(free, storedHeld, reserved, held, expired, free2), m.map { it.seatId }, "회차의 좌석 전부 · seatId 오름차순")
        assertEquals(
            mapOf(free to true, storedHeld to false, reserved to false, held to false, expired to false, free2 to true),
            m.availability(),
            "available = 보이는 상태가 AVAILABLE(sweep — 저장 상태)일 때만 true(S1)",
        )
        val first = m.first()
        assertEquals(MapSeat(free, "A", 1, 1, true), first, "section · rowNo · seatNo는 좌석 행 그대로")
    }

    @Test
    fun `S2 — 회차 없음 404 SEAT_NOT_FOUND · 좌석 0석 회차 빈 배열 · 되풀이해도 같다`() {
        repeat(2) { i ->
            val r = seatMapRaw(NO_SUCH_SCHEDULE)
            assertEquals(404, r.status, "회차 없음(${i + 1}번째): $r")
            assertEquals("SEAT_NOT_FOUND", r.code, "회차 없음 code(지금과 같음): $r")
        }
        repeat(2) { i ->
            val r = seatMapRaw()
            assertEquals(200, r.status, "0석 회차(${i + 1}번째): $r")
            assertTrue(objectMapper.readTree(r.body).let { it.isArray && it.isEmpty }, "0석 회차 = [] : $r")
        }
    }

    @Test
    fun `지표 — 맵 GET 한 번에 requests 합 +1 · mode 태그 = 이 모드 · age 기록`() {
        seat()
        val before = requests("db") + requests("cache")
        val ageBefore = ageStat().count
        repeat(3) { seatMap(why = "지표") }
        assertEquals(before + 3, requests("db") + requests("cache"), "$M19_REQUESTS{source=db|cache} — GET 한 번에 1")
        assertTrue(requests("db") >= 1.0, "첫 GET은 DB를 읽는다(source=db)")
        assertEquals(setOf(modeValue), requestModeTags().map { it.lowercase() }.toSet(), "$M19_REQUESTS mode 태그 = 지금 모드")
        assertEquals(ageBefore + 3, ageStat().count, "$M19_AGE — 응답한 맵마다 1")
        assertEquals(setOf(modeValue), ageModeTags().map { it.lowercase() }.toSet(), "$M19_AGE mode 태그 = 지금 모드")
    }
}
