package com.jun.labs.seatreservation.adr019impl

import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import kotlin.test.assertEquals

/**
 * R2-E — event 모드 × 확정 모드 current(sweep): 엔티티 경로(ExpireHoldsService의 ProductSeat.expireHolds · ConfirmReservationService의 ProductSeat.confirm)도
 * 커밋 뒤 무효화한다 — '쓰기 → 다음 GET 반영'(다음 GET이 DB를 읽는다).
 */
@TestPropertySource(properties = [MAP_EVENT, "seat.confirm.mode=current"])
class SeatMapEventCurrentModeTest : SeatMapImplBase() {

    private fun primed() {
        map()
        val hits = requests("event", "cache")
        map()
        assertEquals(hits + 1, requests("event", "cache"), "두 번째 GET은 캐시 적중")
    }

    private fun mapAfterWrite(): Map<Long, Boolean> {
        val db = requests("event", "db")
        val m = map()
        assertEquals(db + 1, requests("event", "db"), "쓰기 커밋 뒤 첫 GET은 DB를 읽는다(무효화)")
        return m
    }

    @Test
    fun `엔티티 만료(expireHolds) → true로 반영`() {
        val (a, b) = seats(2)
        hold(a, 100)
        primed()
        clock.advance(Duration.ofMinutes(5).plusSeconds(1))
        assertEquals(1, expireHolds.expire())
        assertEquals(mapOf(a to true, b to true), mapAfterWrite())
    }

    @Test
    fun `엔티티 확정(confirm) → 무효화(available은 false 그대로)`() {
        val (a) = seats(1)
        val h = hold(a, 100)
        primed()
        assertStatus(200, confirm(h, 100), "current 확정")
        assertEquals(mapOf(a to false), mapAfterWrite())
        assertEquals("RESERVED", jdbcTemplate.queryForObject("SELECT status FROM product_seat WHERE id = ?", String::class.java, a))
    }
}
