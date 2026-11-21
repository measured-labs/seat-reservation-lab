package com.jun.labs.seatreservation.adr019

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.service.ExpireHoldsUseCase
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.PIN_CLEANUP_OFF
import com.jun.labs.seatreservation.support.PIN_C_OFF
import com.jun.labs.seatreservation.support.PIN_FLYWAY_BASE
import com.jun.labs.seatreservation.support.PIN_INC_OFF
import com.jun.labs.seatreservation.support.PIN_METHOD_NONE
import com.jun.labs.seatreservation.support.PIN_SAFETY_OFF
import com.jun.labs.seatreservation.support.PIN_SHAPE_ONE_SHOT
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.context.annotation.Import
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * S1 · S2(event) + S4 · S4′ 무효화 경로(test-contract §5) — event 모드에서 쓰기가 커밋된 뒤 다음 GET이 바뀐 available을 보인다 ·
 * 거부(4xx)된 요청은 맵을 바꾸지 않는다 · DV-1(무효화와 진행 중 적재가 겹쳐도 옛 맵이 캐시에 남지 않는다).
 * 경로마다 먼저 [primeCache]로 캐시에 올린다(두 번째 GET이 source=cache) — 무효화가 없으면 낡은 맵이 나와 실패한다.
 * 컨텍스트 = Adr019PushContractTest와 같은 키(실제 포트 · 묶음 켬 · event · push 켬 — HoldGroupFlowTest와 같은 고정 키).
 * 대체 배정 경로는 Adr019SubstituteInvalidationTest, loadtest 초기화는 Adr019LoadtestResetInvalidationTest.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        P19_SCHED_OFF, P19_COMP_SCHED_OFF, P19_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT,
        PIN_FLYWAY_BASE, P19_GROUP_ON, P19_EVENT, P19_PUSH_ON,
    ],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
class Adr019EventContractTest : Adr019ModeContractBase() {

    override val modeValue = "event"

    @Autowired lateinit var expire: ExpireHoldsUseCase

    private fun assertMap(expected: Map<Long, Boolean>, why: String) {
        assertEquals(expected, availability(why = why), "$why — 커밋 뒤 다음 GET은 바뀐 available을 보인다(S4)")
        assertEquals(dbTruth(), expected, "$why — 기대값 = DB 저장 상태(자기 점검)")
    }

    // ── 1석 선점 · 해제 ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S4 ① 1석 선점 — 커밋 뒤 다음 GET에 false · 거부된 선점(409)은 맵을 바꾸지 않는다`() {
        val a = seat()
        val b = seat()
        assertEquals(mapOf(a to true, b to true), primeCache())
        holdOk(a, 100)
        assertMap(mapOf(a to false, b to true), "1석 선점 뒤")

        primeCache()
        val rejected = holdHttp(a, 200)
        assertEquals(409, rejected.status, "남이 잡은 좌석 선점 → 409: $rejected")
        assertMap(mapOf(a to false, b to true), "거부된 선점 뒤")
    }

    @Test
    fun `S4 ③ 1석 해제 DELETE holds — 커밋 뒤 다음 GET에 true · 남의 해제 거부(4xx)는 맵을 바꾸지 않는다`() {
        val a = seat()
        val h = holdOk(a, 100)
        assertEquals(mapOf(a to false), primeCache())

        val notOwner = releaseHold(h, 200)
        assertTrue(notOwner.status in 400..499, "남의 홀드 해제 → 4xx: $notOwner")
        assertMap(mapOf(a to false), "거부된 해제 뒤")

        primeCache()
        assertEquals(204, releaseHold(h, 100).status, "1석 해제 204")
        assertMap(mapOf(a to true), "1석 해제 뒤")
    }

    // ── 묶음 선점 · 해제(seat.hold-group.enabled=true) ──────────────────────────────────────

    @Test
    fun `S4 ② ③ 묶음 선점 · 묶음 해제 — 두 좌석 모두 반영 · 거부된 묶음(롤백)은 빈 좌석을 바꾸지 않는다`() {
        val (a, b, c, d) = List(4) { seat() }
        assertEquals(mapOf(a to true, b to true, c to true, d to true), primeCache())

        val g = groupHold(listOf(a, b), 100)
        assertEquals(201, g.status, "묶음 선점 201: $g")
        val groupId = g.json!!.get("groupId").asLong()
        assertMap(mapOf(a to false, b to false, c to true, d to true), "묶음 선점 뒤")

        primeCache()
        holdOk(d, 300)
        primeCache()
        val rejected = groupHold(listOf(c, d), 200) // d는 남이 잡음 → 묶음 전체 거부(롤백)
        assertTrue(rejected.status in 400..499, "남이 잡은 좌석이 든 묶음 → 4xx: $rejected")
        assertMap(mapOf(a to false, b to false, c to true, d to false), "거부된 묶음 뒤 — c는 그대로 true")

        primeCache()
        assertEquals(204, releaseGroup(groupId, 100).status, "묶음 해제 204")
        assertMap(mapOf(a to true, b to true, c to true, d to false), "묶음 해제 뒤")
    }

    // ── 만료 배치 ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `S4 ④ 만료 배치 — 시계를 옮긴 뒤 배치가 홀드를 지우면 다음 GET에 true`() {
        val a = seat()
        val b = seat()
        holdOk(a, 100)
        holdOk(b, 200)
        assertEquals(mapOf(a to false, b to false), primeCache())
        clock.advance(HOLD_TTL.plus(Duration.ofSeconds(1)))
        assertEquals(mapOf(a to false, b to false), availability(why = "배치 전"), "배치 전에는 저장 HELD 그대로(sweep)")
        assertEquals(2, expire.expire(), "만료 배치가 두 홀드를 지운다")
        assertMap(mapOf(a to true, b to true), "만료 배치 뒤")
    }

    @Test
    fun `S4 — 앱 밖 직접 쓰기 대조 · 확정 전이(HELD → RESERVED)는 S1상 화면이 바뀌지 않는다`() {
        val a = seat(SeatStatus.HELD)
        assertEquals(mapOf(a to false), primeCache())
        jdbcTemplate.update("UPDATE product_seat SET status = 'RESERVED' WHERE id = ?", a)
        assertEquals(mapOf(a to false), availability(why = "HELD → RESERVED"), "available은 둘 다 false")
    }

    // ── DV-1: 적재 중 무효화 ────────────────────────────────────────────────────────────────

    @Test
    fun `S4′ DV-1 — 캐시 미스 조회가 DB를 읽은 뒤 멈춘 사이 선점이 커밋되면 그 조회는 옛 맵을 캐시에 남기지 않는다(다음 GET false)`() {
        val a = seat()
        val b = seat()
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val once = AtomicBoolean(false)
        SeatMapHooks.afterDbRead { id ->
            if (id == s && once.compareAndSet(false, true)) {
                reached.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        try {
            val slowGet = pool.submit<Http> { realGet("/api/schedules/$s/seats") }
            assertTrue(reached.await(15, TimeUnit.SECONDS), "캐시 미스 조회가 afterDbRead 훅을 부르지 않았다(test-contract §4)")

            // 선점은 따로 돌린다 — 무효화가 진행 중 적재를 기다리는 구현이어도 시험이 멈추지 않게(5초 뒤 훅을 푼다)
            val holding = pool.submit<Http> { realPost("/api/schedules/$s/seats/$a/hold", 100) }
            runCatching { holding.get(5, TimeUnit.SECONDS) }
            release.countDown()
            val held = holding.get(30, TimeUnit.SECONDS)
            assertEquals(201, held.status, "선점 커밋: $held")

            val old = slowGet.get(30, TimeUnit.SECONDS)
            assertEquals(200, old.status, "멈췄던 조회도 200: $old")
        } finally {
            release.countDown()
            SeatMapHooks.afterDbRead(null)
        }
        assertEquals(mapOf(a to false, b to true), availability(why = "DV-1 다음 GET"), "옛 조회 결과가 무효화 뒤 캐시에 남았다(S4′ — 회차별 세대 번호)")
        assertEquals(mapOf(a to false, b to true), availability(why = "DV-1 그다음 GET"), "그다음 GET(캐시)도 새 값")
    }
}
