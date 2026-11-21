package com.jun.labs.seatreservation.service.impl.seatmap

import com.jun.labs.seatreservation.service.ExpiryMode
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.SeatMapMode
import com.jun.labs.seatreservation.service.SeatMapProperties
import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * ADR-019 기동 검사(설계-3 마지막 문단 · 명세 S5). lazy · lazy-sweep · derived 만료 방식은 쓰기 없이 시각만으로 홀드가 풀려 변경 신호로 잡을 수 없다 —
 * event 모드 · 푸시는 sweep에서만 뜬다. 맞지 않으면 자동으로 끄지 않고 거부하며, 거부 메시지에 되돌릴 설정을 적는다(첫 거부에 안내 — ADR-018과 같다).
 * 설정만 보므로 Flyway migrate 앞(HoldStrategyMigrationGate 맨 앞)에서 먼저 돌고, Flyway가 꺼진 구성을 위해 [check]가 한 번 더 한다.
 * reconciler 프로필은 업무 요청을 받지 않으므로 보지 않는다.
 */
@Component
@Profile("!reconciler")
class SeatMapStartupCheck(
    private val properties: SeatMapProperties,
    private val hold: SeatHoldProperties,
) {
    @PostConstruct
    fun check() = checkConfig(properties, hold)

    companion object {
        fun checkConfig(properties: SeatMapProperties, hold: SeatHoldProperties) {
            val usesSignal = properties.mode == SeatMapMode.EVENT || properties.push.enabled
            check(!usesSignal || hold.expiryMode == ExpiryMode.SWEEP) {
                val asked = listOfNotNull(
                    "seat.seat-map.mode=event".takeIf { properties.mode == SeatMapMode.EVENT },
                    "seat.seat-map.push.enabled=true".takeIf { properties.push.enabled },
                ).joinToString(" · ")
                "$asked 는 seat.hold.expiry-mode=sweep에서만 쓴다 — 현재 ${hold.expiryMode.name.lowercase().replace('_', '-')} " +
                    "(lazy · lazy-sweep · derived는 쓰기 없이 시각만으로 홀드가 풀려 변경 신호로 잡을 수 없다 · ADR-019 설계-3) · " +
                    "되돌리려면 seat.seat-map.mode=none(또는 ttl · redis) · seat.seat-map.push.enabled=false, 또는 seat.hold.expiry-mode=sweep"
            }
            check(properties.mode !in TTL_MODES || properties.ttl > java.time.Duration.ZERO) {
                "seat.seat-map.ttl은 0보다 커야 한다(seat.seat-map.mode=${properties.mode.tag}) — 현재 ${properties.ttl}"
            }
        }

        private val TTL_MODES = setOf(SeatMapMode.TTL, SeatMapMode.REDIS)
    }
}
