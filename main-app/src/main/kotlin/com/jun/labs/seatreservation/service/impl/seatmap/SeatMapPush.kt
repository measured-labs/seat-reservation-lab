package com.jun.labs.seatreservation.service.impl.seatmap

import com.jun.labs.seatreservation.service.SeatMapProperties
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADR-019 상태 푸시(SSE — 설계-2 · 3 셋째 문단). `seat.seat-map.push.enabled=true`일 때만 일한다(끄면 연결도 스레드도 없다).
 * 연결 순서: 구독을 먼저 건다(변경 좌석 id를 쌓는다) → 첫 맵을 DB에서 읽어 `snapshot`으로 보낸다 → 쌓인 변경을 보낸다.
 * 변경 신호([changed] — 커밋 뒤)는 좌석 id만 싣고, 보낼 때 그 좌석의 지금 보이는 상태를 다시 읽는다 — 같은 좌석이 여러 번 와도 마지막 값이 지금 상태다.
 * 첫 맵 뒤의 전송(변경 · 하트비트)은 스레드 하나가 한다(연결마다 보내는 순서가 섞이지 않는다). 회차마다 바뀐 좌석을 한 문장으로 다시 읽는다.
 * 끊김 · 타임아웃 · 오류 · 전송 실패면 구독과 쌓인 변경을 함께 정리한다. 하트비트(주석 이벤트)가 끊긴 연결을 전송 실패로 드러낸다.
 * 같은 인스턴스의 변경만 보낸다(다중 인스턴스 전파는 범위 밖 — 설계-4).
 */
@Component
class SeatMapPush(
    properties: SeatMapProperties,
    private val view: SeatMapView,
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
            sender!!.scheduleWithFixedDelay(::heartbeat, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS)
        }
    }

    private class Subscription(val scheduleId: Long, val emitter: SseEmitter) {
        /** 첫 맵을 보냈다 — 그 뒤의 전송은 [sender] 스레드만 한다 */
        @Volatile var ready = false
        val closed = AtomicBoolean()
        private val pending = LinkedHashSet<Long>()

        fun add(seatIds: Collection<Long>) = synchronized(pending) { pending.addAll(seatIds) }

        fun drain(): List<Long> = synchronized(pending) { pending.toList().also { pending.clear() } }
    }

    /** 연결 하나를 연다 — 회차가 없으면 [com.jun.labs.seatreservation.domain.SeatReservationException](404)을 스트림을 열기 전에 던진다 */
    fun open(scheduleId: Long): SseEmitter {
        check(enabled) { "seat.seat-map.push.enabled=false인데 푸시 연결을 열려 했다" }
        val emitter = SseEmitter(0L) // 시간 제한 없음 — 끊김은 하트비트 전송 실패로 잡는다
        val sub = Subscription(scheduleId, emitter)
        bySchedule.computeIfAbsent(scheduleId) { ConcurrentHashMap.newKeySet() }.add(sub)
        open.incrementAndGet()
        emitter.onCompletion { close(sub) }
        emitter.onTimeout { close(sub) }
        emitter.onError { close(sub) }
        try {
            val snapshot = view.readDb(scheduleId)
            SeatMapTestHooks.afterSnapshotRead?.invoke(scheduleId)
            emitter.send(SseEmitter.event().name("snapshot").data(snapshot, MediaType.APPLICATION_JSON))
        } catch (e: Exception) {
            close(sub)
            throw e
        }
        sub.ready = true
        submit { flush(scheduleId) }
        return emitter
    }

    /** 커밋 뒤 변경 신호(SeatMapChanges) — 그 회차 구독마다 좌석 id를 쌓고 전송을 맡긴다. 첫 맵 전인 구독은 쌓기만 한다(첫 맵 뒤에 보낸다) */
    fun changed(scheduleId: Long, seatIds: Collection<Long>) {
        val subs = bySchedule[scheduleId] ?: return
        if (subs.isEmpty()) return
        subs.forEach { it.add(seatIds) }
        submit { flush(scheduleId) }
    }

    /** 전체 비움(loadtest 초기화 커밋 뒤) — 회차가 지워지고 다시 만들어졌으므로 모든 연결을 닫는다(클라이언트가 다시 연결해 새 첫 맵을 받는다) */
    fun closeAll() {
        bySchedule.values.flatMap { it.toList() }.forEach { finish(it) }
    }

    private fun flush(scheduleId: Long) {
        val subs = bySchedule[scheduleId]?.filter { it.ready && !it.closed.get() } ?: return
        val drained = subs.associateWith { it.drain() }.filterValues { it.isNotEmpty() }
        if (drained.isEmpty()) return
        val now = try {
            view.readChanges(scheduleId, drained.values.flatten().toSet()).associateBy { it.seatId }
        } catch (e: Exception) {
            // 다시 읽지 못한 변경을 조용히 버리지 않는다 — 그 연결을 닫아 클라이언트가 새 첫 맵부터 다시 받게 한다
            log.error("좌석 맵 푸시 변경 다시 읽기 실패 — 연결 {}개를 닫는다(회차 {}): {}", drained.size, scheduleId, e.toString())
            drained.keys.forEach { finish(it) }
            return
        }
        drained.forEach { (sub, ids) ->
            val changes = ids.mapNotNull { now[it] }
            if (changes.isNotEmpty()) send(sub) { it.send(SseEmitter.event().name("change").data(changes, MediaType.APPLICATION_JSON)) }
        }
    }

    private fun heartbeat() {
        bySchedule.values.forEach { subs -> subs.filter { it.ready }.forEach { sub -> send(sub) { it.send(SseEmitter.event().comment("ping")) } } }
    }

    private fun send(sub: Subscription, block: (SseEmitter) -> Unit) {
        if (sub.closed.get()) return
        try {
            block(sub.emitter)
        } catch (e: IOException) {
            close(sub) // 끊긴 연결 — 정리만(오류 아님)
            runCatching { sub.emitter.completeWithError(e) }
        } catch (e: IllegalStateException) {
            close(sub) // 이미 끝난 emitter
        }
    }

    private fun submit(task: () -> Unit) {
        val executor = sender ?: return
        try {
            executor.execute {
                try {
                    task()
                } catch (e: Exception) {
                    log.error("좌석 맵 푸시 전송 실패", e)
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            log.warn("좌석 맵 푸시 — 종료 중이라 전송을 맡기지 못했다: {}", e.toString())
        }
    }

    private fun finish(sub: Subscription) {
        close(sub)
        runCatching { sub.emitter.complete() }
    }

    /** 구독과 쌓인 변경을 함께 정리한다(한 번만 센다) */
    private fun close(sub: Subscription) {
        if (!sub.closed.compareAndSet(false, true)) return
        // 빈 집합은 지우지 않는다 — 지우는 사이 같은 회차의 새 연결이 그 집합에 들어가면 구독을 잃는다(회차당 빈 집합 하나)
        bySchedule[sub.scheduleId]?.remove(sub)
        sub.drain()
        open.decrementAndGet()
    }

    @PreDestroy
    fun shutdown() {
        sender?.shutdownNow()
        closeAll()
    }

    companion object {
        private val log = LoggerFactory.getLogger(SeatMapPush::class.java)
        const val CONNECTIONS_METRIC = "seat.seatmap.push.connections"
        /** ponytail: 하트비트 주기 고정 1초 — 끊긴 연결을 늦어도 2~3초 안에 정리한다. 연결 수만 개에서 쓰기 부하가 보이면 설정 옵션으로 뺀다 */
        private const val HEARTBEAT_MS = 1_000L
    }
}
