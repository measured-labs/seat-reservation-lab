package com.jun.labs.seatreservation.adr019impl

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** none(기본 · S2) — 요청마다 DB · 두 상태 응답 · 회차 없음 404 · 0석 회차 빈 배열 */
class SeatMapNoneTest : SeatMapImplBase() {

    @Test
    fun `기본 none — 요청마다 DB를 읽고 쓰기가 곧바로 보인다 · 404 · 빈 배열`() {
        assertEquals(emptyMap(), map(), "0석 회차 = []")
        val (a, b) = seats(2)
        val db = requests("none", "db")
        assertEquals(mapOf(a to true, b to true), map())
        jdbcTemplate.update("UPDATE product_seat SET status = 'RESERVED' WHERE id = ?", b)
        assertEquals(mapOf(a to true, b to false), map(), "캐시 없음 — 직접 DB 변경도 다음 GET에")
        assertEquals(db + 2, requests("none", "db"))
        assertEquals(0.0, requests("none", "cache"))
        val r = mapRaw(987_654_321)
        assertEquals(404, r.status)
        assertEquals("SEAT_NOT_FOUND", json(r).get("code").asText())
    }
}
