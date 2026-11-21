package com.jun.labs.seatreservation.adr019impl

import com.jun.labs.seatreservation.RedisTestcontainersConfiguration
import com.jun.labs.seatreservation.service.impl.seatmap.SeatMapView
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * ttl · redis 공통(S3 · S6): TTL은 맵을 DB에서 읽기 시작한 시각부터 앱 Clock으로 잰다(정각 = 지남) · TTL 안에서는 쓰기가 있어도 옛 맵(쓰기 무효화 없음) ·
 * 화면 나이 지표 = 응답 시각 − 읽기 시작 시각.
 */
abstract class SeatMapTtlCases(private val mode: String) : SeatMapImplBase() {

    private fun ageTotalMs() = meters.find(M_AGE).tags("mode", mode).timer()?.totalTime(TimeUnit.MILLISECONDS) ?: 0.0

    @Test
    fun `TTL 경계 — 999ms는 캐시 · 1s 정각은 DB · TTL 안의 선점은 보이지 않는다 · 나이 지표`() {
        val (a, b) = seats(2)
        assertEquals(mapOf(a to true, b to true), map())
        hold(a, 100) // 쓰기 무효화 없음
        val hits = requests(mode, "cache")
        val age0 = ageTotalMs()
        clock.advance(Duration.ofMillis(999))
        assertEquals(mapOf(a to true, b to true), map(), "TTL 안 — 옛 맵")
        assertEquals(hits + 1, requests(mode, "cache"))
        assertEquals(999.0, ageTotalMs() - age0, 0.5, "적중의 나이 = 응답 시각 − 읽기 시작 시각")
        clock.advance(Duration.ofMillis(1))
        val db = requests(mode, "db")
        assertEquals(mapOf(a to false, b to true), map(), "TTL 정각 — 지난 캐시를 내지 않는다")
        assertEquals(db + 1, requests(mode, "db"))
    }
}

/** ttl 모드는 Redis에 닿지 않는다 — Redis 주소를 닫힌 포트로 줘도 실패 지표가 없다 */
@TestPropertySource(properties = [MAP_TTL, "spring.data.redis.port=1"])
class SeatMapTtlTest : SeatMapTtlCases("ttl") {
    @Test
    fun `ttl 모드는 Redis에 닿지 않는다`() {
        seats(1)
        map()
        map()
        assertNull(meters.find(M_REDIS_FAILURES).counter(), "Redis 실패 지표 없음")
    }
}

@Import(RedisTestcontainersConfiguration::class)
@TestPropertySource(properties = [MAP_REDIS])
class SeatMapRedisTest : SeatMapTtlCases("redis") {
    @Autowired lateinit var redis: StringRedisTemplate

    @Test
    fun `redis 모드는 Redis 키에 읽기 시작 시각과 맵을 둔다`() {
        val (a) = seats(1)
        map()
        val raw = assertNotNull(redis.opsForValue().get("${SeatMapView.REDIS_KEY_PREFIX}$s"))
        val node = objectMapper.readTree(raw)
        assertEquals(clock.instant().toString(), node.get("readAt").asText())
        assertEquals(a, node.get("seats").first().get("seatId").asLong())
        assertNull(meters.find(M_REDIS_FAILURES).counter())
    }
}

/** S6 — Redis를 읽거나 쓰다 실패하면 DB로 200 · 지표 `seat.seatmap.redis.failures{op}` */
@TestPropertySource(properties = [MAP_REDIS, "spring.data.redis.port=1", "spring.data.redis.timeout=2s"])
class SeatMapRedisFailureTest : SeatMapImplBase() {
    private fun failures(op: String) = meters.find(M_REDIS_FAILURES).tags("op", op).counter()?.count() ?: 0.0

    @Test
    fun `Redis 실패 — get · put 실패를 세고 DB 맵으로 200`() {
        val (a, b) = seats(2)
        hold(a, 100)
        val get0 = failures("get")
        val put0 = failures("put")
        val db0 = requests("redis", "db")
        assertEquals(mapOf(a to false, b to true), map())
        assertEquals(mapOf(a to false, b to true), map())
        assertEquals(get0 + 2, failures("get"))
        assertEquals(put0 + 2, failures("put"))
        assertEquals(db0 + 2, requests("redis", "db"))
        assertEquals(404, mapRaw(987_654_321).status, "회차 없음은 그대로 404")
    }
}
