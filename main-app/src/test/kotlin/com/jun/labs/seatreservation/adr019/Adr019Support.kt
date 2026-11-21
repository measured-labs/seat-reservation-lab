package com.jun.labs.seatreservation.adr019

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.jun.labs.seatreservation.api.USER_ID_HEADER
import com.jun.labs.seatreservation.support.IntegrationTest
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.core.env.Environment
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Modifier
import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/*
 * ADR-019 1단계 task 02 — blind 계약 테스트 공용 장치(구현 미열람 · 기준 4166953b4).
 * 계약 = docs/plans/2026-11-22/adr-019-seat-map-query/requirement-spec.md §2 S1~S9 · S4′ + ADR-019 설계-1~4
 *      + 시험 접점 test-contract.md(§1 설정 키 · §2 HTTP · §3 지표 · §4 시험용 훅 · §5 무효화 경로).
 * 새 기능은 외부 계약으로만 건드린다: 설정 키 문자열 · HTTP(상태 · JSON 필드 · SSE 이벤트) · 지표 이름/태그 · JDBC 행.
 * 아직 없는 구현 심볼(SeatMapTestHooks)은 컴파일 의존 없이 **이름으로 리플렉션**한다(계약 이름 그대로 — test-contract §4).
 */

// ── 설정 키(test-contract §1) ───────────────────────────────────────────────────────────────
const val SEAT_MAP_MODE_KEY = "seat.seat-map.mode"
const val SEAT_MAP_TTL_KEY = "seat.seat-map.ttl"
const val SEAT_MAP_PUSH_KEY = "seat.seat-map.push.enabled"

const val P19_NONE = "$SEAT_MAP_MODE_KEY=none"
const val P19_TTL = "$SEAT_MAP_MODE_KEY=ttl"
const val P19_REDIS = "$SEAT_MAP_MODE_KEY=redis"
const val P19_EVENT = "$SEAT_MAP_MODE_KEY=event"
const val P19_PUSH_ON = "$SEAT_MAP_PUSH_KEY=true"

/** 기본 TTL(test-contract §1 — `seat.seat-map.ttl` 기본 1s) */
val DEFAULT_SEAT_MAP_TTL: Duration = Duration.ofSeconds(1)

// event · push 컨텍스트 공통 키(HoldGroupFlowTest와 같은 고정 키 + 묶음 켬 + event + push) — 두 클래스가 같은 배열을 써서 컨텍스트를 함께 쓴다
const val P19_SCHED_OFF = "seat.hold.expiry-scheduler-enabled=false"
const val P19_COMP_SCHED_OFF = "seat.compensation.scheduler-enabled=false"
const val P19_A_ON = "seat.hold.counter-drift.expiry-repair=true"
const val P19_GROUP_ON = "seat.hold-group.enabled=true"

// ── 지표(test-contract §3) ─────────────────────────────────────────────────────────────────
const val M19_REQUESTS = "seat.seatmap.requests"
const val M19_AGE = "seat.seatmap.age"
const val M19_REDIS_FAILURES = "seat.seatmap.redis.failures"
const val M19_PUSH_CONNECTIONS = "seat.seatmap.push.connections"

// ── 응답(test-contract §2) ─────────────────────────────────────────────────────────────────
val SEAT_MAP_FIELDS = setOf("seatId", "section", "rowNo", "seatNo", "available")
val CHANGE_FIELDS = setOf("seatId", "available")
const val NO_SUCH_SCHEDULE = 987_654_321L

/** 앱 기본 홀드 TTL(application.yml seat.hold.ttl 5m — 테스트가 덮지 않는다) */
val HOLD_TTL: Duration = Duration.ofMinutes(5)

data class MapSeat(val seatId: Long, val section: String, val rowNo: Int, val seatNo: Int, val available: Boolean)

/**
 * 좌석 맵 본문 → 좌석 목록. 계약(test-contract §2)을 함께 단언한다: 배열 · 원소 필드 정확히 다섯 개 · available은 bool · seatId 오름차순.
 */
fun parseSeatMap(om: ObjectMapper, body: String, why: String = "좌석 맵"): List<MapSeat> {
    val node = om.readTree(body)
    assertTrue(node.isArray, "$why — 좌석 맵 응답은 JSON 배열이다: ${body.take(300)}")
    val seats = node.map { e ->
        assertEquals(SEAT_MAP_FIELDS, e.fieldNames().asSequence().toSet(), "$why — 원소 필드는 정확히 다섯 개(S1 · 누구의 홀드인지 내지 않는다): $e")
        assertTrue(e.get("available").isBoolean, "$why — available은 bool이다(S1): $e")
        assertTrue(e.get("seatId").isIntegralNumber, "$why — seatId는 정수: $e")
        MapSeat(e.get("seatId").asLong(), e.get("section").asText(), e.get("rowNo").asInt(), e.get("seatNo").asInt(), e.get("available").asBoolean())
    }
    val ids = seats.map { it.seatId }
    assertEquals(ids.sorted(), ids, "$why — seatId 오름차순(test-contract §2)")
    return seats
}

fun List<MapSeat>.availability(): Map<Long, Boolean> = associate { it.seatId to it.available }

/**
 * 시험용 훅(test-contract §4) — `com.jun.labs.seatreservation.service.impl.seatmap.SeatMapTestHooks`(Kotlin object)의
 * `afterDbRead` · `afterSnapshotRead`(`((Long) -> Unit)?`)를 이름으로 바꾼다. 구현 패키지를 컴파일 의존으로 끌어오지 않는다(blind · 구현 전 컴파일).
 */
object SeatMapHooks {
    const val CLASS_NAME = "com.jun.labs.seatreservation.service.impl.seatmap.SeatMapTestHooks"

    private fun set(property: String, value: ((Long) -> Unit)?) {
        val cls = runCatching { Class.forName(CLASS_NAME) }.getOrElse {
            fail("시험용 훅 $CLASS_NAME 이 없다(test-contract §4): $it")
        }
        val setterName = "set" + property.replaceFirstChar { it.uppercase() }
        val setter = cls.methods.firstOrNull { it.name == setterName && it.parameterCount == 1 }
            ?: fail("$CLASS_NAME.$property(var) 의 setter가 없다(test-contract §4)")
        val target = if (Modifier.isStatic(setter.modifiers)) null else cls.getField("INSTANCE").get(null)
        setter.invoke(target, value)
    }

    fun afterDbRead(hook: ((Long) -> Unit)?) = set("afterDbRead", hook)
    fun afterSnapshotRead(hook: ((Long) -> Unit)?) = set("afterSnapshotRead", hook)

    /** 둘 다 null로(시험이 끝날 때 — test-contract §4). 훅 클래스가 아직 없으면 조용히 넘긴다(정리 단계가 원래 실패를 가리지 않게). */
    fun clear() {
        if (runCatching { Class.forName(CLASS_NAME) }.isFailure) return
        afterDbRead(null)
        afterSnapshotRead(null)
    }
}

/**
 * SSE 클라이언트(test-contract §2 stream) — HttpURLConnection 하나를 자기 스레드에서 열고 이벤트를 큐에 쌓는다.
 * 연결(헤더 수신)이 서버 쪽 훅에서 멈춰도 부르는 쪽은 막히지 않는다. [close] = 소켓을 닫아 끊는다(서버 구독 정리 시험).
 */
class SseClient(url: String) : AutoCloseable {
    data class Event(val name: String, val data: String)

    val events = LinkedBlockingQueue<Event>()
    @Volatile var status: Int? = null
        private set
    @Volatile var error: Throwable? = null
        private set
    @Volatile var ended: Boolean = false
        private set

    private val conn: HttpURLConnection = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
        setRequestProperty("Accept", "text/event-stream")
        connectTimeout = 5_000
        readTimeout = 0
        useCaches = false
    }

    private val reader = Thread({ run() }, "adr019-sse").apply { isDaemon = true; start() }

    private fun run() {
        try {
            val code = conn.responseCode
            status = code
            if (code != 200) return
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { r ->
                var name: String? = null
                val data = StringBuilder()
                while (true) {
                    val line = r.readLine() ?: break
                    when {
                        line.isEmpty() -> {
                            if (name != null || data.isNotEmpty()) events.add(Event(name ?: "message", data.toString()))
                            name = null
                            data.clear()
                        }
                        line.startsWith(":") -> Unit // 주석(하트비트)
                        line.startsWith("event:") -> name = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.removePrefix("data:").removePrefix(" "))
                        }
                        else -> Unit
                    }
                }
            }
        } catch (e: Throwable) {
            error = e
        } finally {
            ended = true
        }
    }

    fun awaitStatus(seconds: Long = 15): Int {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (status == null && error == null && System.nanoTime() < end) Thread.sleep(20)
        return status ?: fail("SSE 응답 상태를 ${seconds}초 안에 받지 못했다(error=$error)")
    }

    fun next(seconds: Long = 10): Event? = events.poll(seconds, TimeUnit.SECONDS)

    override fun close() {
        runCatching { conn.disconnect() }
        reader.join(5_000)
    }
}

/**
 * 공통 기반 — IntegrationTest(+ MockMvc) 위에 좌석 맵 · 선점 · 해제 HTTP · 지표 도우미.
 * 하위 클래스가 모드 키를 `@TestPropertySource` 또는 `@SpringBootTest(properties)`로 준다.
 */
@AutoConfigureMockMvc
abstract class Adr019Base : IntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var meters: MeterRegistry
    @Autowired lateinit var env: Environment

    protected lateinit var pool: ExecutorService

    protected val s: Long get() = schedule.id!!

    private var seatNo = 0

    @BeforeEach
    fun adr019Start() {
        pool = Executors.newCachedThreadPool()
        seatNo = 0
    }

    @AfterEach
    fun adr019Stop() {
        SeatMapHooks.clear()
        pool.shutdownNow()
    }

    fun seat(status: com.jun.labs.seatreservation.domain.SeatStatus = com.jun.labs.seatreservation.domain.SeatStatus.AVAILABLE): Long =
        createSeat(++seatNo, status).id!!

    data class Http(val status: Int, val body: String) {
        val json: JsonNode? = runCatching { ObjectMapper().readTree(body) }.getOrNull()
        val code: String? get() = json?.get("code")?.takeIf { !it.isNull }?.asText()
        override fun toString() = "HTTP $status ${body.take(400)}"
    }

    // ---- 좌석 맵 ----

    fun seatMapRaw(scheduleId: Long = s): Http =
        mockMvc.get("/api/schedules/$scheduleId/seats").andReturn().response.let { Http(it.status, it.contentAsString) }

    fun seatMap(scheduleId: Long = s, why: String = "좌석 맵"): List<MapSeat> {
        val r = seatMapRaw(scheduleId)
        assertEquals(200, r.status, "$why — GET /api/schedules/$scheduleId/seats 200: $r")
        return parseSeatMap(objectMapper, r.body, why)
    }

    fun availability(scheduleId: Long = s, why: String = "좌석 맵"): Map<Long, Boolean> = seatMap(scheduleId, why).availability()

    /** 저장 상태 기준 진실(sweep — 보이는 상태 = 저장 상태): 회차의 좌석마다 status = AVAILABLE ⇔ true */
    fun dbTruth(scheduleId: Long = s): Map<Long, Boolean> =
        jdbcTemplate.queryForList("SELECT id, status FROM product_seat WHERE schedule_id = ? ORDER BY id", scheduleId)
            .associate { (it["id"] as Number).toLong() to (it["status"] == "AVAILABLE") }

    /**
     * 캐시 모드(ttl · redis · event)에서 그 회차 맵을 캐시에 올려 둔다 — GET 두 번, 두 번째가 `source=cache`여야 한다(지표 §3).
     * 무효화 시험이 '캐시가 아예 없어서 통과'하지 않게 하는 준비 단계.
     */
    fun primeCache(scheduleId: Long = s): Map<Long, Boolean> {
        seatMap(scheduleId, "캐시 준비 1")
        val c0 = requests("cache")
        val m = availability(scheduleId, "캐시 준비 2")
        assertEquals(c0 + 1, requests("cache"), "준비 — 두 번째 GET은 캐시에서 답해야 한다($M19_REQUESTS{source=cache} +1)")
        return m
    }

    // ---- 선점 · 해제 · 묶음 ----

    fun holdHttp(seatId: Long, userId: Long, scheduleId: Long = s): Http =
        mockMvc.post("/api/schedules/$scheduleId/seats/$seatId/hold") { header(USER_ID_HEADER, userId) }
            .andReturn().response.let { Http(it.status, it.contentAsString) }

    fun holdOk(seatId: Long, userId: Long, scheduleId: Long = s): Long {
        val r = holdHttp(seatId, userId, scheduleId)
        assertEquals(201, r.status, "준비 — 선점 201: $r")
        return assertNotNull(r.json?.get("holdId"), "선점 응답 holdId: $r").asLong()
    }

    fun releaseHold(holdId: Long, userId: Long): Http =
        mockMvc.delete("/api/holds/$holdId") { header(USER_ID_HEADER, userId) }.andReturn().response.let { Http(it.status, it.contentAsString) }

    fun groupHold(seatIds: List<Long>, userId: Long, scheduleId: Long = s): Http =
        mockMvc.post("/api/schedules/$scheduleId/hold-groups") {
            header(USER_ID_HEADER, userId)
            contentType = MediaType.APPLICATION_JSON
            content = """{"seatIds":[${seatIds.joinToString(",")}]}"""
        }.andReturn().response.let { Http(it.status, it.contentAsString) }

    fun releaseGroup(groupId: Long, userId: Long): Http =
        mockMvc.delete("/api/hold-groups/$groupId") { header(USER_ID_HEADER, userId) }.andReturn().response.let { Http(it.status, it.contentAsString) }

    // ---- 실제 포트(RANDOM_PORT 컨텍스트에서만) ----

    val port: Int get() = assertNotNull(env.getProperty("local.server.port"), "RANDOM_PORT 컨텍스트가 아니다").toInt()

    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    fun realGet(path: String): Http {
        val r = http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).timeout(Duration.ofSeconds(60)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return Http(r.statusCode(), r.body())
    }

    fun realPost(path: String, userId: Long): Http {
        val r = http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).timeout(Duration.ofSeconds(60))
                .header(USER_ID_HEADER, userId.toString()).POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return Http(r.statusCode(), r.body())
    }

    fun streamUrl(scheduleId: Long = s): String = "http://localhost:$port/api/schedules/$scheduleId/seats/stream"

    // ---- 지표(test-contract §3) ----

    fun requests(source: String): Double = meters.find(M19_REQUESTS).tag("source", source).counters().sumOf { it.count() }

    /** 모든 `seat.seatmap.requests` 카운터의 mode 태그 — 그 컨텍스트 모드 하나뿐이어야 한다 */
    fun requestModeTags(): Set<String> = meters.find(M19_REQUESTS).counters().map { it.id.getTag("mode") ?: "<없음>" }.toSet()

    data class TimerStat(val count: Long, val totalMillis: Double)

    fun ageStat(): TimerStat = meters.find(M19_AGE).timers().let { ts ->
        TimerStat(ts.sumOf { it.count() }, ts.sumOf { it.totalTime(TimeUnit.MILLISECONDS) })
    }

    fun ageModeTags(): Set<String> = meters.find(M19_AGE).timers().map { it.id.getTag("mode") ?: "<없음>" }.toSet()

    fun redisFailures(op: String? = null): Double =
        (if (op == null) meters.find(M19_REDIS_FAILURES) else meters.find(M19_REDIS_FAILURES).tag("op", op)).counters().sumOf { it.count() }

    fun pushConnections(): Double = meters.find(M19_PUSH_CONNECTIONS).gauge()?.value() ?: 0.0

    /** [cond]가 참이 될 때까지 기다린다(실제 시간) — 비동기 결과(SSE · 연결 정리) 대기 */
    fun awaitTrue(seconds: Long, why: String, poke: (() -> Unit)? = null, cond: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < end) {
            if (cond()) return
            poke?.invoke()
            Thread.sleep(100)
        }
        if (!cond()) fail("$why — ${seconds}초 안에 조건이 참이 되지 않았다")
    }
}
