package com.jun.labs.seatreservation.adr019impl

import com.jun.labs.seatreservation.api.USER_ID_HEADER
import org.junit.jupiter.api.Test
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals

/** event 모드 × loadtest 초기화(DV-3) — 회차를 지우고 같은 id로 다시 만들므로 커밋 뒤 캐시 전체를 비운다 */
@ActiveProfiles("loadtest")
@TestPropertySource(properties = [MAP_EVENT])
class SeatMapResetEventTest : SeatMapImplBase() {

    private fun reset(schedules: Int, seats: Int) =
        assertEquals(200, mockMvc.post("/internal/reset?schedules=$schedules&seatsPerSchedule=$seats").andReturn().response.status)

    @Test
    fun `초기화 뒤 — 지워진 회차는 404 · 다시 만든 회차는 새 시드대로(옛 캐시 없음)`() {
        reset(2, 3)
        assertEquals(mapOf(4L to true, 5L to true, 6L to true), map(2))
        assertEquals(mapOf(4L to true, 5L to true, 6L to true), map(2)) // 캐시
        val r = mockMvc.post("/api/schedules/1/seats/1/hold") { header(USER_ID_HEADER, 100) }.andReturn().response
        assertEquals(201, r.status, r.contentAsString)
        assertEquals(mapOf(1L to false, 2L to true, 3L to true), map(1))
        assertEquals(mapOf(1L to false, 2L to true, 3L to true), map(1)) // 캐시

        reset(1, 3)
        assertEquals(404, mapRaw(2).status, "지워진 회차의 캐시가 남지 않는다")
        assertEquals(mapOf(1L to true, 2L to true, 3L to true), map(1), "같은 id로 다시 만든 회차는 새 시드(홀드 없음)")
    }
}
