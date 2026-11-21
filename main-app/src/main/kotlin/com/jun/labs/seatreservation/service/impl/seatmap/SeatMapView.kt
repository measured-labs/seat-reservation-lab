package com.jun.labs.seatreservation.service.impl.seatmap

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.service.SeatMapMode
import com.jun.labs.seatreservation.service.SeatMapProperties
import com.jun.labs.seatreservation.service.impl.expiry.SeatMapEntry
import com.jun.labs.seatreservation.service.impl.expiry.SeatMapQuery
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 좌석 맵 응답 한 칸(ADR-019 설계-1) — 화면이 묻는 것은 '지금 고를 수 있나' 하나다. 누구의 홀드인지 · HELD와 RESERVED의 구별은 내지 않는다 */
data class SeatMapSeat(val seatId: Long, val section: String, val rowNo: Int, val seatNo: Int, val available: Boolean)

/** 푸시의 변경 한 칸 — 보낼 때 다시 읽은 지금 보이는 상태 */
data class SeatMapChange(val seatId: Long, val available: Boolean)

/**
 * ADR-019 좌석 맵 응답 층(설계-1 · 2 · 3 · 4) — [SeatMapQuery](만료 방식별 보이는 상태 · 3값) 위에서 두 상태로 바꾸고 `seat.seat-map.mode`대로 캐시한다.
 * - none: 요청마다 SQL 한 문장(지금 그대로)
 * - ttl · event: 앱 메모리(회차별). ttl은 맵을 DB에서 읽기 시작한 시각 + TTL 전까지(앱 Clock · 정각 = 지남) · event는 무효화 전까지
 * - redis: 키 `seatmap:{회차}`에 읽기 시작 시각과 맵을 둔다 — 판정은 앱 Clock(Redis 자체 만료는 청소용). 읽기 · 쓰기 실패는 DB 응답 + 지표(설계-4)
 * 세대 번호(설계-3 첫째 순서): 무효화가 회차 세대를 올린다. 적재는 DB를 읽기 전에 본 세대가 그대로일 때만 하고, 꺼낼 때도 세대가 같은 항목만 쓴다 —
 * DB를 읽은 뒤 · 적재 전에 무효화가 끼어든 옛 맵은 나가지 않는다. 전체 비움(loadtest 초기화)은 모든 회차의 세대를 함께 올린다(epoch).
 */
@Component
class SeatMapView(
    private val query: SeatMapQuery,
    properties: SeatMapProperties,
    private val clock: Clock,
    private val meters: MeterRegistry,
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
) {
    private val mode = properties.mode
    private val ttl = properties.ttl
    private val fromDb = meters.counter(REQUESTS_METRIC, "mode", mode.tag, "source", "db")
    private val fromCache = meters.counter(REQUESTS_METRIC, "mode", mode.tag, "source", "cache")
    private val age = meters.timer(AGE_METRIC, "mode", mode.tag)

    private val local = ConcurrentHashMap<Long, Cached>()
    private val generations = ConcurrentHashMap<Long, Long>()
    private val epoch = AtomicLong()

    private data class Stamp(val epoch: Long, val generation: Long)
    private class Cached(val seats: List<SeatMapSeat>, val readAt: Instant, val stamp: Stamp)

    /** Redis 값 — 읽기 시작 시각(ISO-8601)과 맵 */
    private data class RedisEntry(val readAt: String, val seats: List<SeatMapSeat>)

    fun seats(scheduleId: Long): List<SeatMapSeat> = when (mode) {
        SeatMapMode.NONE -> {
            val readAt = clock.instant()
            val seats = readDb(scheduleId)
            SeatMapTestHooks.afterDbRead?.invoke(scheduleId)
            respond(seats, readAt, fromDb)
        }
        SeatMapMode.TTL, SeatMapMode.EVENT -> local(scheduleId)
        SeatMapMode.REDIS -> redis(scheduleId)
    }

    /** 캐시를 거치지 않고 DB를 읽는다(SSE 첫 맵 — 설계-3 셋째 문단) */
    fun readDb(scheduleId: Long): List<SeatMapSeat> = query.seats(scheduleId).map { it.toSeat() }

    /** 그 회차 [seatIds]의 지금 보이는 상태(SSE 변경 전송) */
    fun readChanges(scheduleId: Long, seatIds: Collection<Long>): List<SeatMapChange> =
        query.seatsIn(scheduleId, seatIds).map { SeatMapChange(it.seatId, it.status == SeatStatus.AVAILABLE) }

    /** event 무효화(커밋 뒤 — SeatMapChanges) — 세대를 먼저 올리고 항목을 지운다 */
    fun invalidate(scheduleId: Long) {
        generations.merge(scheduleId, 1L, Long::plus)
        local.remove(scheduleId)
    }

    /** 전체 비움(loadtest 초기화 커밋 뒤 — 지워진 회차의 맵이 남지 않게). 앱 메모리만 — Redis 맵은 TTL로 낡음을 막는다 */
    fun clearAll() {
        epoch.incrementAndGet()
        local.clear()
    }

    private fun stamp(scheduleId: Long) = Stamp(epoch.get(), generations[scheduleId] ?: 0L)

    private fun local(scheduleId: Long): List<SeatMapSeat> {
        val before = stamp(scheduleId)
        val now = clock.instant()
        local[scheduleId]?.takeIf { it.stamp == before && fresh(it.readAt, now) }?.let { return respond(it.seats, it.readAt, fromCache) }
        val readAt = clock.instant()
        val seats = readDb(scheduleId)
        SeatMapTestHooks.afterDbRead?.invoke(scheduleId)
        if (stamp(scheduleId) == before) local[scheduleId] = Cached(seats, readAt, before)
        return respond(seats, readAt, fromDb)
    }

    /** event는 무효화 전까지 · ttl은 읽기 시작 + TTL 전까지(정각 = 지남) */
    private fun fresh(readAt: Instant, now: Instant) = mode == SeatMapMode.EVENT || now.isBefore(readAt.plus(ttl))

    private fun redis(scheduleId: Long): List<SeatMapSeat> {
        val key = "$REDIS_KEY_PREFIX$scheduleId"
        val raw = try {
            redis.opsForValue().get(key)
        } catch (e: RuntimeException) {
            redisFailure("get", scheduleId, e)
            null
        }
        if (raw != null) {
            val entry = try {
                mapper.readValue<RedisEntry>(raw)
            } catch (e: Exception) {
                redisFailure("get", scheduleId, e) // 읽을 수 없는 값 — 없는 것으로 보고 DB로(조용히 넘기지 않는다)
                null
            }
            if (entry != null) {
                val readAt = Instant.parse(entry.readAt)
                if (clock.instant().isBefore(readAt.plus(ttl))) return respond(entry.seats, readAt, fromCache)
            }
        }
        val readAt = clock.instant()
        val seats = readDb(scheduleId)
        SeatMapTestHooks.afterDbRead?.invoke(scheduleId)
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(RedisEntry(readAt.toString(), seats)), ttl.plus(REDIS_EXPIRY_SLACK))
        } catch (e: RuntimeException) {
            redisFailure("put", scheduleId, e)
        }
        return respond(seats, readAt, fromDb)
    }

    private fun redisFailure(op: String, scheduleId: Long, e: Exception) {
        meters.counter(REDIS_FAILURES_METRIC, "op", op).increment()
        log.warn("좌석 맵 Redis {} 실패 — DB로 응답한다(회차 {}): {}", op, scheduleId, e.toString())
    }

    private fun respond(seats: List<SeatMapSeat>, readAt: Instant, source: io.micrometer.core.instrument.Counter): List<SeatMapSeat> {
        source.increment()
        val shown = Duration.between(readAt, clock.instant())
        age.record(if (shown.isNegative) Duration.ZERO else shown)
        return seats
    }

    private fun SeatMapEntry.toSeat() = SeatMapSeat(seatId, section, rowNo, seatNo, status == SeatStatus.AVAILABLE)

    companion object {
        private val log = LoggerFactory.getLogger(SeatMapView::class.java)
        const val REQUESTS_METRIC = "seat.seatmap.requests"
        const val AGE_METRIC = "seat.seatmap.age"
        const val REDIS_FAILURES_METRIC = "seat.seatmap.redis.failures"
        const val REDIS_KEY_PREFIX = "seatmap:"
        /** Redis 자체 만료 = TTL + 여유(청소용) — 낡음 판정은 앱 Clock이 한다(앱 시계와 Redis 시계가 어긋나도 TTL 지난 맵을 내지 않게) */
        private val REDIS_EXPIRY_SLACK: Duration = Duration.ofSeconds(60)
    }
}
