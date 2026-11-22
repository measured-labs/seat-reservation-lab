package com.jun.labs.seatreservation.service.impl.seatmap

import com.fasterxml.jackson.databind.ObjectMapper
import com.jun.labs.seatreservation.service.SeatMapProperties
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import jakarta.servlet.AsyncContext
import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncListener
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * ADR-019 상태 푸시(SSE — 설계-2 · 3 셋째 문단). `seat.seat-map.push.enabled=true`일 때만 일한다(끄면 연결도 스레드도 없다).
 * 연결 순서: 구독을 먼저 건다(변경 좌석 id를 쌓는다) → 첫 맵을 DB에서 읽는다 → `snapshot`을 보낸다 → 쌓인 변경을 보낸다.
 * 변경 신호([changed] — 커밋 뒤)는 좌석 id만 싣고, 보낼 때 그 좌석의 지금 보이는 상태를 다시 읽는다 — 같은 좌석이 여러 번 와도 마지막 값이 지금 상태다.
 *
 * 쓰기는 서블릿 비차단 출력(WriteListener)으로만 한다 — 읽지 않는 클라이언트가 있어도 어떤 스레드도 소켓 쓰기에서 기다리지 않는다(스레드 수는 연결 수와 무관:
 * 변경 · 하트비트를 만드는 스레드 하나 + 컨테이너가 부르는 쓰기 가능 알림). 연결마다 보낼 프레임 줄은 최대 하나만 만든다 — 앞 프레임이 다 나가기 전에는
 * 새 change를 만들지 않고 좌석 id만 쌓는다(쌓인 id는 회차 좌석 수가 상한 · 나중에 한 프레임으로 지금 상태를 보낸다). 앞 프레임이 [STALL_MS] 넘게 나가지 못하면
 * 그 연결만 닫는다(클라이언트가 다시 연결해 새 첫 맵부터 받는다). 하트비트(주석 프레임)가 끊긴 연결을 쓰기 오류로 드러낸다.
 * 끊김 · 오류 · 막힘이면 구독과 쌓인 변경을 함께 정리하고, 마지막 구독이 빠진 회차의 키도 지운다. 같은 인스턴스의 변경만 보낸다(설계-4).
 */
@Component
class SeatMapPush(
    properties: SeatMapProperties,
    private val view: SeatMapView,
    private val mapper: ObjectMapper,
    meters: MeterRegistry,
) {
    private val enabled = properties.push.enabled
    private val bySchedule = ConcurrentHashMap<Long, MutableSet<Subscription>>()
    private val open = AtomicInteger()
    private val sender: ScheduledExecutorService? = if (!enabled) null else Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "seatmap-push").apply { isDaemon = true }
    }

    init {
        if (enabled) {
            meters.gauge(CONNECTIONS_METRIC, open)
            sender!!.scheduleWithFixedDelay({ guarded { heartbeat() } }, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** 구독 상태 — 한 방향(여는 중 → 준비됨 → 닫힘 · 여는 중 → 닫힘)으로만 원자적으로 옮긴다 */
    private enum class State { OPENING, READY, CLOSED }

    /**
     * 연결 하나. 수명 주기는 [state] 상태 기계 하나다 — 닫힘은 어느 단계에서 오든(전체 닫기 · 쓰기 오류 · onError · onTimeout · 막힘 상한 · 열기 실패 · 초기화)
     * [close] 한 경로를 지난다. 비동기 컨텍스트는 [attach]로 붙고, 닫힘과 붙음 중 늦게 온 쪽이 완료한다(완료는 [completeOnce]로 한 번만).
     * 쓰기(큐 비우기)는 한 번에 한 스레드만 한다 — 전송 스레드와 컨테이너의 쓰기 가능 알림이 함께 부르므로 잠금 대신 CAS 깃발로 고른다
     * (컨테이너 API를 부르는 동안 우리 잠금을 쥐지 않는다 — 컨테이너 내부 잠금과 교착하지 않게). 진 쪽은 '한 번 더'만 표시하고 돌아간다.
     */
    private inner class Subscription(val scheduleId: Long) : WriteListener, AsyncListener {
        val state = AtomicReference(State.OPENING)
        val ready: Boolean get() = state.get() == State.READY
        val closed: Boolean get() = state.get() == State.CLOSED
        private val completed = AtomicBoolean()
        private val pending = LinkedHashSet<Long>()
        private val frames = ConcurrentLinkedQueue<ByteArray>()
        private val writing = AtomicBoolean()
        private val again = AtomicBoolean()
        /** 쓴 뒤 아직 flush하지 않았다 — 쓰기 스레드(CAS 깃발 쥔 쪽)만 만진다 */
        private var unflushed = false
        /** 큐에 남은 프레임이 나가지 못하고 있는 시작 시각(나노 · 0 = 막힘 없음) */
        @Volatile private var stalledSince = 0L
        @Volatile private var async: AsyncContext? = null
        @Volatile var out: ServletOutputStream? = null

        /** 비동기 컨텍스트를 붙인다 — 이미 닫혔으면 곧바로 완료한다(닫기가 붙기 전에 왔던 경우). 붙었으면 true */
        fun attach(a: AsyncContext): Boolean {
            async = a
            if (closed) {
                completeOnce()
                return false
            }
            return true
        }

        /** 여는 중 → 준비됨. 그 사이 닫혔으면 false */
        fun markReady(): Boolean = state.compareAndSet(State.OPENING, State.READY)

        /** 닫힘으로 옮긴다 — 처음 옮긴 호출만 true */
        fun toClosed(): Boolean = state.getAndSet(State.CLOSED) != State.CLOSED

        fun completeOnce() {
            val a = async ?: return // 아직 없다 — attach가 붙는 순간 완료한다
            if (!completed.compareAndSet(false, true)) return
            runCatching { a.complete() }.onFailure { log.debug("좌석 맵 푸시 — 비동기 컨텍스트 완료 실패(이미 끝남): {}", it.toString()) }
        }

        fun discard() {
            frames.clear()
            drain()
        }

        fun add(seatIds: Collection<Long>) = synchronized(pending) { pending.addAll(seatIds) }

        fun drain(): List<Long> = synchronized(pending) { pending.toList().also { pending.clear() } }

        private fun hasPending(): Boolean = synchronized(pending) { pending.isNotEmpty() }

        /** 보낼 프레임이 없다(앞 프레임이 다 나갔다) — 새 change · 하트비트를 만들어도 된다 */
        fun idle(): Boolean = frames.isEmpty()

        fun stalledTooLong(now: Long): Boolean = stalledSince.let { it != 0L && now - it > TimeUnit.MILLISECONDS.toNanos(STALL_MS) }

        fun enqueue(frame: ByteArray) {
            if (closed) return
            frames.add(frame)
            write()
        }

        /** 큐를 비울 수 있는 만큼 쓴다 — 출력이 준비되지 않으면 멈추고 컨테이너의 [onWritePossible]을 기다린다 */
        fun write() {
            again.set(true)
            while (again.get() && writing.compareAndSet(false, true)) {
                again.set(false)
                val drained = try {
                    writeOnce()
                } finally {
                    writing.set(false)
                }
                // 앞 프레임이 다 나갔다 — 그동안 쌓인 변경을 보낸다
                if (drained && ready && hasPending()) submit { flush(scheduleId) }
            }
        }

        /** 큐가 비었으면 true */
        private fun writeOnce(): Boolean {
            val o = out ?: return false
            if (closed) return false
            try {
                while (frames.isNotEmpty() && o.isReady) {
                    o.write(frames.poll())
                    unflushed = true
                }
                // 프레임을 다 쓴 뒤 내보낸다 — 큰 프레임(첫 맵)을 onWritePossible로 이어 쓴 끝도 여기서 나간다(다음 하트비트를 기다리지 않게).
                // 쓰기 가능하지 않으면 컨테이너가 다시 onWritePossible을 부르고 그때 내보낸다
                if (frames.isEmpty() && unflushed && o.isReady) {
                    unflushed = false
                    o.flush()
                }
            } catch (e: Exception) {
                close(this) // 끊긴 연결 — 닫힘 경로(완료 포함)
                return false
            }
            if (frames.isEmpty()) {
                stalledSince = 0L
                return true
            }
            if (stalledSince == 0L) stalledSince = System.nanoTime()
            return false
        }

        override fun onWritePossible() = write()
        override fun onError(t: Throwable) = close(this)
        override fun onComplete(event: AsyncEvent) {
            completed.set(true) // 컨테이너가 이미 완료했다 — 다시 완료하지 않는다
            close(this)
        }
        override fun onTimeout(event: AsyncEvent) = close(this)
        override fun onError(event: AsyncEvent) = close(this)
        override fun onStartAsync(event: AsyncEvent) {}
    }

    /**
     * 연결 하나를 연다 — 회차가 없으면 [com.jun.labs.seatreservation.domain.SeatReservationException](404)을 스트림을 열기 전에 던진다.
     * 응답은 비동기로 열어 두고 이 메서드는 곧바로 돌아온다(요청 스레드를 쥐지 않는다).
     */
    fun open(scheduleId: Long, request: HttpServletRequest, response: HttpServletResponse) {
        check(enabled) { "seat.seat-map.push.enabled=false인데 푸시 연결을 열려 했다" }
        val sub = Subscription(scheduleId)
        bySchedule.compute(scheduleId) { _, subs -> (subs ?: ConcurrentHashMap.newKeySet()).apply { add(sub) } }
        open.incrementAndGet()
        val snapshot = try {
            view.readDb(scheduleId).also { SeatMapTestHooks.afterSnapshotRead?.invoke(scheduleId) }
        } catch (e: Exception) {
            close(sub)
            throw e
        }
        // 어느 단계가 던져도(비동기 시작 · 출력 준비 · 쓰기 알림 등록) 닫힘 경로로 정리하고 다시 던진다. 단, 그 사이 다른 쪽이 닫아(전체 닫기 · 초기화)
        // 컨테이너 호출이 실패한 것이면 던지지 않는다 — 닫힘 경로가 이미 응답을 끝냈다(attach가 완료). 준비됨은 출력까지 붙은 뒤에만 된다
        try {
            response.status = HttpServletResponse.SC_OK
            response.contentType = "text/event-stream"
            response.characterEncoding = "UTF-8"
            response.setHeader("Cache-Control", "no-cache")
            // 얻는 즉시 붙인다 — 그 뒤 어느 단계가 실패하든(시간 제한 설정 · 리스너 등록 포함) 닫힘 경로가 이 컨텍스트를 정확히 한 번 완료한다
            val async = request.startAsync(request, response)
            if (!sub.attach(async)) return // 여는 중에 닫혔다 — attach가 완료했다(EOF)
            async.timeout = 0 // 시간 제한 없음 — 끊김은 하트비트 쓰기 오류 · 막힘 상한으로 잡는다
            async.addListener(sub)
            sub.enqueue(frame("snapshot", snapshot)) // out이 붙기 전이라 큐에만 들어간다
            val out = response.outputStream
            sub.out = out
            out.setWriteListener(sub) // 컨테이너가 쓰기 가능해지면 onWritePossible → snapshot부터 나간다
            if (!sub.markReady()) return // 여는 중에 닫혔다 — 닫힘 경로가 완료했다
        } catch (e: Exception) {
            val closedMeanwhile = sub.closed
            close(sub)
            if (closedMeanwhile) return
            throw e
        }
        submit { flush(scheduleId) }
    }

    /** 커밋 뒤 변경 신호(SeatMapChanges) — 그 회차 구독마다 좌석 id를 쌓고 전송을 맡긴다. 첫 맵 전인 구독은 쌓기만 한다(첫 맵 뒤에 보낸다) */
    fun changed(scheduleId: Long, seatIds: Collection<Long>) {
        val subs = bySchedule[scheduleId] ?: return
        subs.forEach { it.add(seatIds) }
        submit { flush(scheduleId) }
    }

    /** 전체 비움(loadtest 초기화 커밋 뒤) — 회차가 지워지고 다시 만들어졌으므로 모든 연결을 닫는다(클라이언트가 다시 연결해 새 첫 맵을 받는다) */
    fun closeAll() {
        bySchedule.values.flatMap { it.toList() }.forEach { close(it) }
    }

    /** 앞 프레임이 다 나간 구독만 — 쌓인 좌석의 지금 상태를 한 문장으로 읽어 연결마다 한 프레임 */
    private fun flush(scheduleId: Long) {
        val subs = bySchedule[scheduleId]?.filter { it.ready && it.idle() } ?: return
        val drained = subs.associateWith { it.drain() }.filterValues { it.isNotEmpty() }
        if (drained.isEmpty()) return
        val now = try {
            view.readChanges(scheduleId, drained.values.flatten().toSet()).associateBy { it.seatId }
        } catch (e: Exception) {
            // 다시 읽지 못한 변경을 조용히 버리지 않는다 — 그 연결을 닫아 클라이언트가 새 첫 맵부터 다시 받게 한다
            log.error("좌석 맵 푸시 변경 다시 읽기 실패 — 연결 {}개를 닫는다(회차 {}): {}", drained.size, scheduleId, e.toString())
            drained.keys.forEach { close(it) }
            return
        }
        drained.forEach { (sub, ids) ->
            val changes = ids.mapNotNull { now[it] }
            if (changes.isNotEmpty()) sub.enqueue(frame("change", changes))
        }
    }

    /** 하트비트 + 막힘 상한 — 앞 프레임이 [STALL_MS] 넘게 못 나간 연결은 닫고, 나머지 중 빈 연결에 주석 프레임 */
    private fun heartbeat() {
        val now = System.nanoTime()
        bySchedule.values.flatMap { it.toList() }.filter { it.ready }.forEach { sub ->
            when {
                sub.stalledTooLong(now) -> {
                    log.info("좌석 맵 푸시 — {}ms 넘게 받지 않는 연결을 닫는다(회차 {})", STALL_MS, sub.scheduleId)
                    close(sub)
                }
                sub.idle() -> sub.enqueue(PING)
            }
        }
    }

    private fun frame(name: String, data: Any): ByteArray = "event:$name\ndata:${mapper.writeValueAsString(data)}\n\n".toByteArray(Charsets.UTF_8)

    private fun submit(task: () -> Unit) {
        val executor = sender ?: return
        try {
            executor.execute { guarded(task) }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            log.warn("좌석 맵 푸시 — 종료 중이라 전송을 맡기지 못했다: {}", e.toString())
        }
    }

    private fun guarded(task: () -> Unit) {
        try {
            task()
        } catch (e: Exception) {
            log.error("좌석 맵 푸시 전송 실패", e)
        }
    }

    /**
     * 닫힘 경로(유일) — 처음 닫는 호출만: ① 목록에서 뺀다(마지막 구독이 빠진 회차는 키도 — 같은 키의 새 등록과 compute로 원자적) ② 연결 수를 한 번 줄인다
     * ③ 쌓인 변경 · 프레임을 버린다 ④ 비동기 컨텍스트가 있으면 완료한다(없으면 open이 붙이는 순간 attach가 완료한다). 두 번째부터는 완료만 다시 시도한다(한 번만 된다).
     */
    private fun close(sub: Subscription) {
        if (sub.toClosed()) {
            bySchedule.computeIfPresent(sub.scheduleId) { _, subs -> subs.remove(sub); if (subs.isEmpty()) null else subs }
            open.decrementAndGet()
            sub.discard()
        }
        sub.completeOnce()
    }

    @PreDestroy
    fun shutdown() {
        sender?.shutdownNow()
        closeAll()
    }

    companion object {
        private val log = LoggerFactory.getLogger(SeatMapPush::class.java)
        const val CONNECTIONS_METRIC = "seat.seatmap.push.connections"
        private val PING = ":ping\n\n".toByteArray(Charsets.UTF_8)
        /** ponytail: 하트비트 주기 고정 1초 — 끊긴 연결을 늦어도 2~3초 안에 정리한다. 연결 수만 개에서 쓰기 부하가 보이면 설정 옵션으로 뺀다 */
        private const val HEARTBEAT_MS = 1_000L
        /**
         * ponytail: 막힘 상한 고정 10초 — 보낼 프레임(연결마다 최대 하나 + 하트비트 없음)이 10초 넘게 소켓으로 나가지 못하면 그 연결만 닫는다(1만 석 첫 맵 ≈ 0.7MB도
         * 느린 모바일 회선에서 이 안에 나간다는 가정). 정상 클라이언트가 이 상한에 걸리는 것이 지표에 보이면 설정 옵션으로 빼거나 첫 맵 크기에 비례하게 바꾼다
         */
        private const val STALL_MS = 10_000L
    }
}
