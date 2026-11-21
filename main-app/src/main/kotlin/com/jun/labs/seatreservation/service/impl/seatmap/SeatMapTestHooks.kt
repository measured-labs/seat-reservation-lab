package com.jun.labs.seatreservation.service.impl.seatmap

/**
 * 순서 경합 시험 전용 접점 — 운영에서는 둘 다 null이다(부르는 비용 = volatile 읽기 한 번). 시험은 끝날 때 null로 되돌린다.
 * - [afterDbRead]: 좌석 맵 GET이 DB를 읽은 직후 · 캐시에 넣기 전(세대 번호 경합)
 * - [afterSnapshotRead]: SSE 연결이 첫 맵을 DB에서 읽은 직후 · `snapshot`을 보내기 전(구독 먼저 · 첫 맵 · 쌓인 변경 순서)
 */
object SeatMapTestHooks {
    @Volatile var afterDbRead: ((scheduleId: Long) -> Unit)? = null
    @Volatile var afterSnapshotRead: ((scheduleId: Long) -> Unit)? = null
}
