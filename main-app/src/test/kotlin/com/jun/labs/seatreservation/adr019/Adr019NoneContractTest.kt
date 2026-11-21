package com.jun.labs.seatreservation.adr019

import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals

/*
 * S2 캐시 없음(none — 기본) · S7 푸시 끔(기본) 404.
 * 키를 주지 않는 기본 컨텍스트(IntegrationTest + MockMvc — Adr014SweepDefaultContractTest와 같은 설정)에서 돈다:
 * `seat.seat-map.mode` 기본 none · `seat.seat-map.push.enabled` 기본 false(test-contract §1).
 */
class Adr019NoneContractTest : Adr019ModeContractBase() {

    override val modeValue = "none"

    @Test
    fun `S2 — 캐시 없음은 요청마다 DB를 읽는다 · 앱을 거치지 않은 쓰기도 다음 GET에 바로 보인다 · source=cache 0`() {
        val a = seat()
        val cache0 = requests("cache")
        val db0 = requests("db")
        assertEquals(mapOf(a to true), availability(why = "1"))
        jdbcTemplate.update("UPDATE product_seat SET status = 'HELD' WHERE id = ?", a)
        assertEquals(mapOf(a to false), availability(why = "2"), "none은 캐시가 없다 — 직접 쓴 값이 바로 보인다(S2)")
        jdbcTemplate.update("UPDATE product_seat SET status = 'AVAILABLE' WHERE id = ?", a)
        assertEquals(mapOf(a to true), availability(why = "3"))
        assertEquals(cache0, requests("cache"), "none은 캐시에서 답하지 않는다")
        assertEquals(db0 + 3, requests("db"), "GET마다 source=db +1")
    }

    @Test
    fun `S2 — none의 화면 나이는 0(고정 시계 — 읽기 시작과 응답이 같은 시각)`() {
        seat()
        val before = ageStat()
        repeat(2) { seatMap(why = "age") }
        val after = ageStat()
        assertEquals(before.count + 2, after.count)
        assertEquals(before.totalMillis, after.totalMillis, 0.5, "앱 시계가 움직이지 않으면 DB에서 바로 읽은 맵의 나이 = 0")
    }

    @Test
    fun `S7 — 푸시 끔(기본)이면 stream 엔드포인트가 없다(404)`() {
        seat()
        val r = mockMvc.get("/api/schedules/$s/seats/stream") { accept = MediaType.TEXT_EVENT_STREAM }.andReturn().response
        assertEquals(404, r.status, "seat.seat-map.push.enabled 기본 false → 404(S7): ${r.contentAsString.take(300)}")
    }
}
