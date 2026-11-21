package com.jun.labs.seatreservation.adr019impl

import com.jun.labs.seatreservation.RedisTestcontainersConfiguration
import com.jun.labs.seatreservation.service.impl.seatmap.SeatMapView
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import com.jun.labs.seatreservation.api.USER_ID_HEADER
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.post
import org.testcontainers.containers.GenericContainer
import kotlin.test.assertTrue
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

    @Test
    fun `A — 읽을 수 없는 Redis 값(readAt 형식 틀림)은 get 실패로 세고 DB로 200`() {
        val (a) = seats(1)
        redis.opsForValue().set("${SeatMapView.REDIS_KEY_PREFIX}$s", """{"readAt":"x","seats":[]}""")
        val get0 = meters.find(M_REDIS_FAILURES).tags("op", "get").counter()?.count() ?: 0.0
        assertEquals(mapOf(a to true), map())
        assertEquals(get0 + 1, meters.find(M_REDIS_FAILURES).tags("op", "get").counter()?.count() ?: 0.0)
    }
}

/** G — Redis가 멈춘 뒤 좌석 맵 GET 한 번이 2초 안에 DB로 200(좌석 맵 전용 짧은 제한 시간 · 끊긴 동안 명령을 쌓지 않음). 컨테이너를 멈추므로 컨텍스트를 버린다 */
@Import(RedisTestcontainersConfiguration::class)
@TestPropertySource(properties = [MAP_REDIS])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SeatMapRedisStopTest : SeatMapImplBase() {
    @Autowired lateinit var appCtx: ApplicationContext

    @Test
    fun `G — Redis 멈춤 뒤 GET 한 번이 2초 안에 200 · 실패 지표`() {
        val (a) = seats(1)
        assertEquals(mapOf(a to true), map(), "준비 — Redis 연결")
        (appCtx.getBean("redisContainer") as GenericContainer<*>).stop()
        clock.advance(Duration.ofSeconds(2)) // TTL 지남 — Redis를 다시 읽고 다시 쓴다
        val t0 = System.nanoTime()
        val m = map()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(mapOf(a to true), m)
        assertTrue(ms < 2_000, "GET 한 번이 2초 안(실제 ${ms}ms)")
        assertTrue((meters.find(M_REDIS_FAILURES).tags("op", "get").counter()?.count() ?: 0.0) >= 1.0)
    }
}

/** B — redis 모드 + loadtest 초기화: 커밋 뒤 Redis의 좌석 맵 키도 비운다(초기화 직후 TTL 안 GET이 새 시드) */
@ActiveProfiles("loadtest")
@Import(RedisTestcontainersConfiguration::class)
@TestPropertySource(properties = [MAP_REDIS])
class SeatMapResetRedisTest : SeatMapImplBase() {
    @Autowired lateinit var redis: StringRedisTemplate

    private fun reset() = assertEquals(200, mockMvc.post("/internal/reset?schedules=1&seatsPerSchedule=3").andReturn().response.status)

    @Test
    fun `B — 초기화 뒤 TTL 안 GET이 새 시드를 보인다 · Redis 좌석 맵 키가 지워진다`() {
        reset()
        val r = mockMvc.post("/api/schedules/1/seats/1/hold") { header(USER_ID_HEADER, 100) }.andReturn().response
        assertEquals(201, r.status, r.contentAsString)
        assertEquals(mapOf(1L to false, 2L to true, 3L to true), map(1))
        assertTrue(redis.hasKey("${SeatMapView.REDIS_KEY_PREFIX}1"), "Redis에 적재")
        reset()
        assertEquals(false, redis.hasKey("${SeatMapView.REDIS_KEY_PREFIX}1"), "초기화 커밋 뒤 Redis 좌석 맵 키 비움")
        assertEquals(mapOf(1L to true, 2L to true, 3L to true), map(1), "고정 시계(TTL 안)에서도 새 시드")
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
