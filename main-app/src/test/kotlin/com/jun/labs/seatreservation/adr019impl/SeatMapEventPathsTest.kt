package com.jun.labs.seatreservation.adr019impl

import com.jun.labs.seatreservation.service.impl.seatmap.SeatMapChanges
import com.jun.labs.seatreservation.service.impl.seatmap.SeatMapTestHooks
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * event 모드(S4 · S4′) — 경로마다 '캐시가 찬 맵 → 쓰기(커밋) → 다음 GET에 반영'. 캐시가 실제로 맵을 들고 있음은 '쓰기 없는 직접 DB 변경이 안 보인다'와
 * 적중 지표로 먼저 확인한다(무효화가 없으면 반영 단언이 실패하게). 거부(4xx) · 롤백은 신호를 보내지 않는다(적중이 이어진다).
 */
@TestPropertySource(properties = [MAP_EVENT, GROUP_ON])
class SeatMapEventPathsTest : SeatMapImplBase() {
    @Autowired lateinit var changes: SeatMapChanges
    @Autowired lateinit var tx: TransactionTemplate
    @Autowired lateinit var holds: HoldSeatUseCase

    private fun cacheHits() = requests("event", "cache")

    /** 맵을 캐시에 넣고 두 번째 GET이 적중임을 확인한다 */
    private fun primed(scheduleId: Long = s): Map<Long, Boolean> {
        map(scheduleId)
        val before = cacheHits()
        val m = map(scheduleId)
        assertEquals(before + 1, cacheHits(), "두 번째 GET은 캐시 적중")
        return m
    }

    /** 다음 GET이 DB를 읽었는지(무효화됐는지) */
    private fun mapAfterWrite(): Map<Long, Boolean> {
        val db = requests("event", "db")
        val m = map()
        assertEquals(db + 1, requests("event", "db"), "쓰기 커밋 뒤 첫 GET은 DB를 읽는다(무효화)")
        return m
    }

    @Test
    fun `캐시는 맵을 들고 있다 — 신호 없는 직접 DB 변경은 보이지 않는다(반영 단언이 무효화를 판별한다)`() {
        val (a) = seats(1)
        assertEquals(mapOf(a to true), primed())
        jdbcTemplate.update("UPDATE product_seat SET status = 'HELD' WHERE id = ?", a)
        assertEquals(mapOf(a to true), map(), "무효화 없이는 옛 맵")
    }

    @Test
    fun `1석 선점 → 다음 GET에 false`() {
        val (a, b) = seats(2)
        assertEquals(mapOf(a to true, b to true), primed())
        hold(a, 100)
        assertEquals(mapOf(a to false, b to true), mapAfterWrite())
    }

    @Test
    fun `묶음 선점 → 묶은 좌석 모두 false`() {
        val (a, b, c) = seats(3)
        primed()
        holdGroup(100, a, b)
        assertEquals(mapOf(a to false, b to false, c to true), mapAfterWrite())
    }

    @Test
    fun `해제 — 1석 DELETE holds · 묶음 DELETE hold-groups → true로 돌아온다`() {
        val (a, b, c) = seats(3)
        val h = hold(a, 100)
        val g = holdGroup(200, b, c)
        assertEquals(mapOf(a to false, b to false, c to false), primed())
        assertStatus(204, releaseHold(h, 100), "1석 해제")
        assertEquals(mapOf(a to true, b to false, c to false), mapAfterWrite())
        primed()
        assertStatus(204, releaseGroup(g, 200), "묶음 해제")
        assertEquals(mapOf(a to true, b to true, c to true), mapAfterWrite())
    }

    @Test
    fun `만료 배치(시계를 옮긴 뒤 expire) → true`() {
        val (a, b) = seats(2)
        hold(a, 100)
        assertEquals(mapOf(a to false, b to true), primed())
        clock.advance(Duration.ofMinutes(5).plusSeconds(1))
        assertEquals(1, expireHolds.expire())
        assertEquals(mapOf(a to true, b to true), mapAfterWrite())
    }

    @Test
    fun `확정 — HELD → RESERVED는 available을 바꾸지 않아도 무효화한다(방향 무관)`() {
        val (a) = seats(1)
        val h = hold(a, 100)
        primed()
        assertStatus(200, confirm(h, 100), "확정")
        assertEquals(mapOf(a to false), mapAfterWrite())
        assertEquals("RESERVED", jdbcTemplate.queryForObject("SELECT status FROM product_seat WHERE id = ?", String::class.java, a))
    }

    @Test
    fun `거부된 선점(409)은 신호를 보내지 않는다 — 다음 GET도 적중`() {
        val (a) = seats(1)
        hold(a, 100)
        primed()
        assertStatus(409, holdRaw(a, 200), "이미 잡힌 좌석")
        val hits = cacheHits()
        map()
        assertEquals(hits + 1, cacheHits(), "거부 뒤 GET은 여전히 캐시")
    }

    @Test
    fun `신호는 커밋 뒤에만 — 롤백된 트랜잭션의 신호는 버려진다 · 트랜잭션 밖이면 곧바로`() {
        val (a) = seats(1)
        primed()
        tx.executeWithoutResult { status ->
            jdbcTemplate.update("UPDATE product_seat SET status = 'HELD' WHERE id = ?", a)
            changes.publish(s, listOf(a))
            status.setRollbackOnly()
        }
        val hits = cacheHits()
        assertEquals(mapOf(a to true), map(), "롤백 — 맵 그대로")
        assertEquals(hits + 1, cacheHits(), "롤백 뒤 GET은 적중(신호 없음)")
        changes.publish(s, listOf(a)) // 트랜잭션 밖
        mapAfterWrite()
    }

    @Test
    fun `I — 같은 트랜잭션의 앞선 afterCommit 콜백이 던져도 신호가 빠지지 않는다`() {
        val (a) = seats(1)
        primed()
        val thrown = runCatching {
            tx.execute {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() = throw IllegalStateException("다른 afterCommit 콜백의 예외")
                })
                holds.hold(HoldSeatCommand(s, a, 100))
            }
        }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException, "다른 콜백의 예외는 그대로 올라온다: $thrown")
        assertEquals("HELD", jdbcTemplate.queryForObject("SELECT status FROM product_seat WHERE id = ?", String::class.java, a), "커밋은 됐다")
        assertEquals(mapOf(a to false), mapAfterWrite(), "우리 신호가 먼저 돌았다")
    }

    @Test
    fun `DV-1 — DB를 읽은 뒤 · 적재 전에 선점이 커밋되면 그 옛 맵은 캐시에 남지 않는다`() {
        val (a) = seats(1)
        val read = CountDownLatch(1)
        val release = CountDownLatch(1)
        val once = AtomicBoolean()
        SeatMapTestHooks.afterDbRead = { id ->
            if (id == s && once.compareAndSet(false, true)) {
                read.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val first = pool.submit<Map<Long, Boolean>> { map() }
            assertTrue(read.await(30, TimeUnit.SECONDS), "조회가 DB를 읽고 멈춘다")
            hold(a, 100) // 커밋 + 무효화(세대 +1) — 조회는 아직 적재 전
            release.countDown()
            assertEquals(mapOf(a to true), first.get(30, TimeUnit.SECONDS), "멈춘 조회 자신은 읽은 시점의 맵을 낸다")
        } finally {
            pool.shutdownNow()
        }
        SeatMapTestHooks.afterDbRead = null
        assertEquals(mapOf(a to false), map(), "옛 맵이 캐시에 남지 않았다(세대 번호)")
    }

    @Test
    fun `푸시를 끄면(기본) 스트림 엔드포인트가 없다 — 404`() {
        seats(1)
        val r = mockMvc.perform(MockMvcRequestBuilders.get("/api/schedules/$s/seats/stream").accept("text/event-stream")).andReturn().response
        assertEquals(404, r.status)
    }
}
