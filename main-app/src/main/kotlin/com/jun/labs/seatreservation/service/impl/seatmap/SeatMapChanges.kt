package com.jun.labs.seatreservation.service.impl.seatmap

import com.jun.labs.seatreservation.service.SeatMapMode
import com.jun.labs.seatreservation.service.SeatMapProperties
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * ADR-019 좌석 맵 변경 신호(설계-1 · 3) — 좌석 상태를 쓰는 모든 경로가 방향을 가리지 않고 부른다(목록은 adr019impl 쓰기 지점 감시 테스트가 고정한다).
 * 신호는 **커밋 뒤에만** 간다(트랜잭션 동기화 afterCommit · 트랜잭션 밖이면 부른 자리에서 곧바로) — 롤백된 쓰기는 맵을 바꾸지 않는다.
 * 받는 쪽: event 모드면 그 회차 캐시 무효화(SeatMapView 세대 번호) · 푸시를 켰으면 그 좌석들의 변경 전송(SeatMapPush).
 * 둘 다 아니면(기본 none · ttl · redis) 아무것도 하지 않는다 — 쓰기 경로에 더하는 일은 불 하나 확인이다(redis는 TTL로만 — 설계-4).
 */
@Component
class SeatMapChanges(
    properties: SeatMapProperties,
    private val view: SeatMapView,
    private val push: SeatMapPush,
    private val jdbc: JdbcTemplate,
) {
    private val invalidates = properties.mode == SeatMapMode.EVENT
    private val pushes = properties.push.enabled
    private val active = invalidates || pushes

    /** 그 회차의 [seatIds] 상태를 이 트랜잭션이 썼다(또는 썼을 수 있다) */
    fun publish(scheduleId: Long, seatIds: Collection<Long>) {
        if (!active || seatIds.isEmpty()) return
        val ids = seatIds.toList()
        afterCommit { deliver(scheduleId, ids) }
    }

    /** 회차를 모르는 경로(C 만료 배치의 좌석 되돌림) — 켜져 있을 때만 좌석의 회차를 읽어 [publish]와 같이 보낸다 */
    fun publishSeats(seatIds: Collection<Long>) {
        if (!active || seatIds.isEmpty()) return
        jdbc.query("SELECT schedule_id, id FROM product_seat WHERE id = ANY(?::bigint[])", { rs, _ -> rs.getLong(1) to rs.getLong(2) }, seatIds.toTypedArray())
            .groupBy({ it.first }, { it.second })
            .forEach { (scheduleId, ids) -> afterCommit { deliver(scheduleId, ids) } }
    }

    /** 회차를 지우고 같은 id로 다시 만드는 경로(loadtest 초기화 — 설계-3 둘째 순서) — 커밋 뒤 캐시 전체를 비우고 푸시 연결을 모두 닫는다(모드와 무관) */
    fun publishAll() {
        afterCommit {
            view.clearAll()
            push.closeAll()
        }
    }

    private fun deliver(scheduleId: Long, seatIds: List<Long>) {
        if (invalidates) view.invalidate(scheduleId)
        if (pushes) push.changed(scheduleId, seatIds)
    }

    private fun afterCommit(block: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return block()
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = block()
        })
    }
}
