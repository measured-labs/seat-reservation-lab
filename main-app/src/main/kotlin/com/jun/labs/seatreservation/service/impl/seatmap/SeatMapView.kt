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
 * - redis: 키 `seatmap:{회차}`에 읽기 시작 시각과 맵을 둔다 — 판정은 앱 Clock(Redis 자체 만료는 청소용). 읽기 · 쓰기 실패(읽을 수 없는 값 포함)는 DB 응답 + 지표(설계-4).
 *   Redis 접근은 좌석 맵 전용 클라이언트([SeatMapRedis] — 짧은 제한 시간)로만 한다
 * 세대 번호(설계-3 첫째 순서): 무효화가 회차 세대를 올린다. 적재는 DB를 읽기 전에 본 세대가 그대로일 때만 하고, 꺼낼 때도 세대가 같은 항목만 쓴다 —
 * DB를 읽은 뒤 · 적재 전에 무효화가 끼어든 옛 맵은 나가지 않는다. 전체 비움(loadtest 초기화)은 모든 회차의 세대를 함께 올린다(epoch).
 */
@Component
class SeatMapView(
    private val query: SeatMapQuery,
    properties: SeatMapProperties,
    private val clock: Clock,
    private val meters: MeterRegistry,
    private val redis: SeatMapRedis,
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

    /** Redis 값 — 읽기 시작 시각(ISO-8601) · DB를 읽기 전에 본 초기화 세대([REDIS_EPOCH_KEY]) · 맵 */
    private data class RedisEntry(val readAt: String, val epoch: Long, val seats: List<SeatMapSeat>)

    /** 초기화 세대 올림이 실패한 인스턴스가 Redis를 건너뛰는 끝 시각(앱 Clock) — 그때까지 DB로 답한다(R2-B) */
    @Volatile private var redisBypassUntil: Instant = Instant.MIN

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

    /**
     * 전체 비움(loadtest 초기화 커밋 뒤 — 지워진 회차의 맵이 남지 않게). 앱 메모리 + redis 모드면 Redis의 초기화 세대([REDIS_EPOCH_KEY])를 올린다 —
     * 세대가 다른 Redis 맵은 어느 인스턴스도 꺼내 쓰지 않는다(초기화 전에 DB를 읽고 뒤에 적재한 맵 포함 — 앱 여러 대 공통). 옛 키는 Redis 만료가 치운다.
     * 세대 올림이 실패하면 지표(op=clear)로 세고 초기화를 막지 않으며, 이 인스턴스는 TTL 동안 Redis를 건너뛰고 DB로 답한다(그 사이 옛 맵은 TTL이 지난다).
     * ponytail: 세대 올림 실패를 다른 인스턴스는 모른다 — 그 인스턴스들은 TTL 동안 초기화 전 맵을 낼 수 있다(초기화는 측정 도구 · 실패는 지표로 드러난다).
     * 다른 모드는 Redis에 닿지 않는다.
     */
    fun clearAll() {
        epoch.incrementAndGet()
        local.clear()
        if (mode != SeatMapMode.REDIS) return
        try {
            redis.increment(REDIS_EPOCH_KEY)
        } catch (e: RuntimeException) {
            redisBypassUntil = clock.instant().plus(ttl)
            meters.counter(REDIS_FAILURES_METRIC, "op", "clear").increment()
            log.warn("좌석 맵 Redis 초기화 세대 올림 실패 — 이 인스턴스는 {}까지 Redis를 건너뛰고 DB로 답한다: {}", redisBypassUntil, e.toString())
        }
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

    /**
     * 값과 초기화 세대를 한 번에(MGET) 읽는다. 꺼내 쓰는 조건 = 세대가 지금 세대와 같고 · 읽기 시작 + TTL 전. 적재는 DB를 읽기 전에 본 세대를 싣는다 —
     * 그 사이 초기화가 세대를 올렸으면 그 적재는 아무도 꺼내 쓰지 않는다. 세대를 모르면(읽기 실패) 적재하지 않는다.
     */
    private fun redis(scheduleId: Long): List<SeatMapSeat> {
        val readAt = clock.instant()
        if (readAt.isBefore(redisBypassUntil)) return respond(readDb(scheduleId).also { SeatMapTestHooks.afterDbRead?.invoke(scheduleId) }, readAt, fromDb)
        val key = "$REDIS_KEY_PREFIX$scheduleId"
        var current: Long? = null
        try {
            val (raw, epochRaw) = redis.getWithEpoch(key, REDIS_EPOCH_KEY)
            current = epochRaw?.toLong() ?: 0L
            // 읽을 수 없는 값(JSON · 시각 형식) — 없는 것으로 보고 DB로(조용히 넘기지 않는다: get 실패로 센다)
            val entry = raw?.let { mapper.readValue<RedisEntry>(it).let { e -> Triple(Instant.parse(e.readAt), e.epoch, e.seats) } }
            if (entry != null && entry.second == current && readAt.isBefore(entry.first.plus(ttl))) return respond(entry.third, entry.first, fromCache)
        } catch (e: Exception) {
            redisFailure("get", scheduleId, e)
        }
        val seats = readDb(scheduleId)
        SeatMapTestHooks.afterDbRead?.invoke(scheduleId)
        if (current != null) {
            try {
                redis.set(key, mapper.writeValueAsString(RedisEntry(readAt.toString(), current, seats)), ttl.plus(REDIS_EXPIRY_SLACK))
            } catch (e: RuntimeException) {
                redisFailure("put", scheduleId, e)
            }
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
        /** 초기화 세대(정수 · 없으면 0) — 초기화가 INCR한다. 맵 키 접두사와 겹치지 않는 이름 */
        const val REDIS_EPOCH_KEY = "seatmap-epoch"
        /** Redis 자체 만료 = TTL + 여유(청소용) — 낡음 판정은 앱 Clock이 한다(앱 시계와 Redis 시계가 어긋나도 TTL 지난 맵을 내지 않게) */
        private val REDIS_EXPIRY_SLACK: Duration = Duration.ofSeconds(60)
    }
}
