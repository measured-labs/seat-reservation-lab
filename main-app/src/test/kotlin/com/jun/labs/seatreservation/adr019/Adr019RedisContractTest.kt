package com.jun.labs.seatreservation.adr019

import com.jun.labs.seatreservation.RedisTestcontainersConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.GenericContainer
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * S1 · S2 · S3 — Redis 공유 캐시(`redis`). TTL 키를 주지 않는다 — 기본 1s(test-contract §1).
 * Redis 연결은 기존 spring.data.redis.* + RedisTestcontainersConfiguration(§1).
 */
@Import(RedisTestcontainersConfiguration::class)
@TestPropertySource(properties = [P19_REDIS])
class Adr019RedisContractTest : Adr019TtlCacheContractBase() {
    override val modeValue = "redis"
    override val ttl: Duration = DEFAULT_SEAT_MAP_TTL

    @Autowired lateinit var redis: StringRedisTemplate

    @BeforeEach
    fun flushRedis() {
        redis.connectionFactory!!.connection.use { it.serverCommands().flushDb() }
    }

    @Test
    fun `S9 — redis 캐시는 Redis에 맵을 둔다(GET 뒤 Redis에 키가 생긴다 · 앱 여러 대가 같이 보는 공유 캐시)`() {
        seat()
        availability(why = "적재")
        val keys = redis.connectionFactory!!.connection.use { c -> c.keyCommands().keys("*".toByteArray())?.size ?: 0 }
        assertTrue(keys > 0, "redis 모드의 캐시 미스 조회는 맵을 Redis에 넣는다(설계-2 · S9 — 공유는 redis 후보의 몫)")
    }
}

/*
 * S6 장애 격리 — redis 모드에서 Redis를 읽거나 쓰다 실패하면 DB 조회로 200 · `seat.seatmap.redis.failures`(op get · put) 증가.
 * Redis 컨테이너를 멈춘다 → 이 컨텍스트는 다시 쓰지 않는다(@DirtiesContext). 명령 제한 시간은 시험용으로 짧게(spring.data.redis.timeout —
 * 기존 키 · 계약은 '실패하면 DB'만 정한다. 기본 60초를 기다리지 않게).
 */
@Import(RedisTestcontainersConfiguration::class)
@TestPropertySource(properties = [P19_REDIS, "spring.data.redis.timeout=1s", "spring.data.redis.connect-timeout=1s"])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class Adr019RedisFailureTest : Adr019Base() {

    @Autowired lateinit var appCtx: ApplicationContext

    @Test
    fun `S6 — Redis가 멈추면 GET은 DB로 200(지금 상태) · 실패 지표 증가 · 다시 GET해도 200`() {
        val a = seat()
        val b = seat()
        assertEquals(mapOf(a to true, b to true), availability(why = "Redis 살아 있을 때"))

        jdbcTemplate.update("UPDATE product_seat SET status = 'HELD' WHERE id = ?", a) // 캐시(TTL 안 — 고정 시계)와 DB가 갈림
        (appCtx.getBean("redisContainer") as GenericContainer<*>).stop()

        val failures0 = redisFailures()
        val get0 = redisFailures("get")
        val db0 = requests("db")
        repeat(2) { i ->
            val r = seatMapRaw()
            assertEquals(200, r.status, "Redis 실패가 조회 실패로 번지지 않는다(S6 — ${i + 1}번째): $r")
            assertEquals(
                mapOf(a to false, b to true), parseSeatMap(objectMapper, r.body, "Redis 멈춤 ${i + 1}").availability(),
                "Redis를 못 읽으면 DB 조회로 답한다(지금 상태 — S6)",
            )
        }
        assertTrue(redisFailures("get") >= get0 + 2, "$M19_REDIS_FAILURES{op=get} — Redis 읽기 실패마다 +1: ${redisFailures("get")} (전 $get0)")
        assertTrue(redisFailures() > failures0, "$M19_REDIS_FAILURES 합이 늘었다")
        assertEquals(db0 + 2, requests("db"), "Redis 실패 때는 DB에서 답했다(source=db)")
    }
}
