package com.jun.labs.seatreservation.service

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * ADR-019 좌석 맵 조회 방식(`seat.seat-map.*` — 설계-2). 기본 none(캐시 없음 — 요청마다 SQL 한 문장). 기본값은 2단계 측정 뒤 정한다.
 * event · 푸시는 sweep 만료 방식에서만 뜬다(기동 검사 [com.jun.labs.seatreservation.service.impl.seatmap.SeatMapStartupCheck] — 설계-3 마지막 문단).
 */
@ConfigurationProperties("seat.seat-map")
data class SeatMapProperties(
    val mode: SeatMapMode = SeatMapMode.NONE,
    /** ttl · redis의 캐시 수명 — 맵을 DB에서 읽기 시작한 시각부터 앱 Clock으로 잰다(설계-4) */
    val ttl: Duration = Duration.ofSeconds(1),
    val push: Push = Push(),
) {
    data class Push(
        /** `GET /api/schedules/{id}/seats/stream`(SSE) — 끄면 엔드포인트가 없다(404) */
        val enabled: Boolean = false,
    )
}

enum class SeatMapMode {
    /** 캐시 없음(베이스라인) */
    NONE,
    /** 앱 메모리에 회차별 맵을 TTL 동안 둔다 */
    TTL,
    /** Redis에 둔 맵 하나를 앱 여러 대가 같이 본다 — TTL로만 낡음을 막는다(쓰기 무효화 없음) */
    REDIS,
    /** 앱 메모리 캐시 + 맵을 바꾸는 쓰기의 커밋 뒤 그 회차를 무효화한다(sweep 전용) */
    EVENT,
    ;

    /** 지표 태그 값 */
    val tag: String get() = name.lowercase()
}
