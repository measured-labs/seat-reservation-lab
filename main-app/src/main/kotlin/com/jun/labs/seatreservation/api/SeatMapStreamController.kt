package com.jun.labs.seatreservation.api

import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.domain.httpStatusCode
import com.jun.labs.seatreservation.service.impl.seatmap.SeatMapPush
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Profile
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * ADR-019 상태 푸시(SSE — 설계-2) — `seat.seat-map.push.enabled=true`일 때만 등록한다(끄면 404). 연결 직후 `snapshot`(전체 맵) · 그 뒤 `change`(바뀐 좌석만).
 * 회차가 없으면 스트림을 열기 전에 404다. 응답은 서블릿 비동기 · 비차단 쓰기로 SeatMapPush가 직접 쓴다(요청 스레드 · 전송 스레드가 소켓 쓰기에서 기다리지 않는다).
 */
@RestController
@Profile("!reconciler")
@Conditional(SeatMapPushEnabledCondition::class)
class SeatMapStreamController(
    private val push: SeatMapPush,
) {
    @GetMapping("/api/schedules/{scheduleId}/seats/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@PathVariable scheduleId: Long, request: HttpServletRequest, response: HttpServletResponse) = push.open(scheduleId, request, response)

    /**
     * 거부(회차 없음)는 여기서 JSON 본문으로 답한다 — 요청의 Accept가 text/event-stream뿐이라 공용 예외 처리기(ApiExceptionHandler)의 JSON은
     * 내용 협상에서 막힌다. 본문 형식을 미리 정해 두면 협상을 건너뛰고 같은 `{code, message}`가 나간다.
     */
    @ExceptionHandler(SeatReservationException::class)
    fun rejected(e: SeatReservationException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(e.errorCode.httpStatusCode()).contentType(MediaType.APPLICATION_JSON)
            .body(ErrorResponse(code = e.errorCode.name, message = e.errorCode.message))
}

/** 푸시 엔드포인트 등록 조건 = `seat.seat-map.push.enabled` 바인딩 값(Boolean 바인딩 규칙 · 키 없으면 끔 — SeatMapProperties와 같다) */
class SeatMapPushEnabledCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
        Binder.get(context.environment).bind("seat.seat-map.push.enabled", Boolean::class.javaObjectType).orElse(false)
}
