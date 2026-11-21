package com.jun.labs.seatreservation.adr019

import com.jun.labs.seatreservation.expiry.EXPIRY_MODE
import com.jun.labs.seatreservation.expiry.LAZY_CLEANUP_KEY
import com.jun.labs.seatreservation.incremental.BootTestBase
import com.jun.labs.seatreservation.incremental.CONFIRM_CURRENT
import com.jun.labs.seatreservation.support.causesOf
import org.junit.jupiter.api.Test
import kotlin.test.fail

/*
 * S5 시간 만료 — event 모드와 푸시는 sweep 만료 방식에서만 뜬다. `seat.seat-map.mode=event` 또는 `seat.seat-map.push.enabled=true`인데
 * `seat.hold.expiry-mode`가 lazy · lazy-sweep · derived면 기동 실패 · 원인 사슬 중 한 메시지에 `seat.seat-map`과 `sweep`이 함께(test-contract §1).
 * 기동 = BootTestBase(실제 SpringApplication · 부팅마다 새 DB). lazy 계열은 기본 확정 모드 밖이라 confirm current를 명시하고(Adr014StartupContractTest와 같음),
 * lazy는 정리 ①(cleanup.mode=schedule)이 있어야 뜬다(CR14-1) — 거부 이유가 seat-map 하나뿐이게 그 둘을 맞춘다.
 */
class Adr019StartupContractTest : BootTestBase() {

    private val lazyModes = listOf("lazy", "lazy-sweep", "derived")

    private fun lazyBase(mode: String): Map<String, String?> =
        CONFIRM_CURRENT + (EXPIRY_MODE to mode) + (if (mode == "lazy") mapOf<String, String?>(LAZY_CLEANUP_KEY to "schedule") else emptyMap())

    private val knobs = mapOf(
        "event" to mapOf<String, String?>(SEAT_MAP_MODE_KEY to "event"),
        "push" to mapOf<String, String?>(SEAT_MAP_PUSH_KEY to "true"),
    )

    @Test
    fun `S5 — event · push 켬 + lazy · lazy-sweep · derived는 기동 실패(메시지에 seat_seat-map과 sweep)`() {
        val failures = mutableListOf<String>()
        lazyModes.forEach { mode ->
            knobs.forEach { (knob, props) ->
                val why = "$knob + expiry-mode=$mode"
                val err = bootFailure(freshDbUrl(), lazyBase(mode) + props)
                if (err == null) {
                    failures += "$why: 떴다(거부해야 한다 — S5)"
                } else {
                    val msgs = causesOf(err).mapNotNull { it.message }
                    if (msgs.none { it.contains("seat.seat-map") && it.contains("sweep") }) {
                        failures += "$why: 메시지에 seat.seat-map · sweep이 함께 없다: ${msgs.map { it.take(250) }}"
                    }
                }
            }
        }
        if (failures.isNotEmpty()) fail("${failures.size}건:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `대조 — 같은 lazy 계열에서 ttl 모드는 뜬다(위 거부가 event · push 때문임을 보인다)`() {
        val failures = mutableListOf<String>()
        lazyModes.forEach { mode ->
            bootFailure(freshDbUrl(), lazyBase(mode) + (SEAT_MAP_MODE_KEY to "ttl"))
                ?.let { failures += "ttl + $mode: ${causesOf(it).map { e -> e.message?.take(200) }}" }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun `대조 — sweep(기본)에서는 event · push 켬이 뜬다`() {
        val failures = mutableListOf<String>()
        knobs.forEach { (knob, props) ->
            bootFailure(freshDbUrl(), props)?.let { failures += "$knob + sweep: ${causesOf(it).map { e -> e.message?.take(200) }}" }
        }
        bootFailure(freshDbUrl(), mapOf(SEAT_MAP_MODE_KEY to "event", SEAT_MAP_PUSH_KEY to "true"))
            ?.let { failures += "event + push + sweep: ${causesOf(it).map { e -> e.message?.take(200) }}" }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }
}
