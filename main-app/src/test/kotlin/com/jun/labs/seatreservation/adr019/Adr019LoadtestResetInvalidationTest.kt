package com.jun.labs.seatreservation.adr019

import org.junit.jupiter.api.Test
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals

/*
 * S4 ⑦ · S4′ loadtest 초기화(test-contract §5) — `POST /internal/reset`(loadtest 프로필)은 회차를 지우고 같은 id로 다시 만든다.
 * event 모드에서 초기화는 커밋 뒤 캐시 전체를 비운다: 지워진 회차는 404, 다시 만든 회차는 새 시드대로.
 * 시드 규칙(LoadtestEndpointsTest): 회차 k의 n번째 좌석 id = (k-1)*S + n · 모두 AVAILABLE.
 * 설정 = LoadtestEndpointsTest와 같은 키(매수 none · 확정 current) + event.
 */
@ActiveProfiles("loadtest")
@TestPropertySource(properties = ["seat.hold.limit-strategy=none", "seat.confirm.mode=current", P19_EVENT])
class Adr019LoadtestResetInvalidationTest : Adr019Base() {

    private fun reset(schedules: Int, seats: Int) =
        mockMvc.post("/internal/reset?schedules=$schedules&seatsPerSchedule=$seats").andExpect { status { isOk() } }

    @Test
    fun `S4′ — 초기화 뒤 지워진 회차는 404 · 다시 만든 회차는 새 시드(캐시된 옛 맵 · 옛 HELD가 남지 않는다)`() {
        reset(2, 10)
        assertEquals((11L..20L).associateWith { true }, primeCache(2))
        holdOk(1, 100, scheduleId = 1)
        assertEquals((1L..10L).associateWith { it != 1L }, primeCache(1), "준비 — 회차 1 캐시(좌석 1 HELD)")

        reset(1, 5)
        val gone = seatMapRaw(2)
        assertEquals(404, gone.status, "지워진 회차의 캐시가 남으면 안 된다(초기화는 캐시 전체를 비운다 — S4′): $gone")
        assertEquals("SEAT_NOT_FOUND", gone.code)
        val fresh = seatMap(1, "초기화 뒤 회차 1")
        assertEquals((1L..5L).toList(), fresh.map { it.seatId }, "다시 만든 회차 = 새 시드(5석)")
        assertEquals((1L..5L).associateWith { true }, fresh.availability(), "새 시드는 모두 빈자리 — 옛 HELD가 남지 않는다")
    }
}
