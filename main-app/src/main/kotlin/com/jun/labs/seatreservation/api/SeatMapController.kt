package com.jun.labs.seatreservation.api

import com.jun.labs.seatreservation.service.impl.seatmap.SeatMapSeat
import com.jun.labs.seatreservation.service.impl.seatmap.SeatMapView
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * ADR-014 좌석 맵(§설계-3.3 · Q11) — 페이지 없음(회차당 1만 석) · 보이는 상태만. 모든 만료 방식에서 연다.
 * ADR-019: 좌석마다 `available`(지금 고를 수 있나) · 조회 방식은 `seat.seat-map.mode`([SeatMapView]).
 */
@RestController
// F(reconciler 프로필 — 대조 전용 프로세스)는 업무 요청을 받지 않는다(코드 루프 1 L1-5)
@Profile("!reconciler")
class SeatMapController(
    private val seatMap: SeatMapView,
) {

    @GetMapping("/api/schedules/{scheduleId}/seats")
    fun seats(@PathVariable scheduleId: Long): List<SeatMapSeat> = seatMap.seats(scheduleId)
}
