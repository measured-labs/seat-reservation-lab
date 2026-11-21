package com.jun.labs.seatreservation.adr019impl

import com.jun.labs.seatreservation.incremental.BootTestBase
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** S5 — event · 푸시는 sweep 만료 방식에서만. 다른 만료 방식과 함께면 migrate 앞에서 거부하고 메시지에 키 이름 · sweep · 되돌릴 설정을 적는다 */
class SeatMapStartupTest : BootTestBase() {

    private fun messages(t: Throwable): String = generateSequence(t) { it.cause }.joinToString(" | ") { it.message.orEmpty() }

    @Test
    fun `event · 푸시 + lazy · lazy-sweep · derived → 기동 거부(migrate 앞 · 안내)`() {
        val asks = listOf(mapOf("seat.seat-map.mode" to "event"), mapOf("seat.seat-map.push.enabled" to "true"))
        for (expiry in listOf("lazy", "lazy-sweep", "derived")) {
            for (ask in asks) {
                val url = freshDbUrl()
                val why = "$expiry + $ask"
                val failure = assertNotNull(bootFailure(url, ask + mapOf("seat.hold.expiry-mode" to expiry)), "$why — 기동이 실패해야 한다")
                val m = messages(failure)
                assertTrue("seat.seat-map" in m && "sweep" in m, "$why — 메시지에 seat.seat-map · sweep: $m")
                assertTrue("seat.seat-map.mode=none" in m, "$why — 되돌릴 설정 안내: $m")
                assertEquals(false, jdbcFor(url).queryForObject("SELECT to_regclass('flyway_schema_history') IS NOT NULL", Boolean::class.java), "$why — migrate 앞에서 거부")
            }
        }
    }

    @Test
    fun `sweep(기본)이면 event와 푸시가 함께 뜬다 · ttl 0은 거부`() {
        assertNull(bootFailure(freshDbUrl(), mapOf("seat.seat-map.mode" to "event", "seat.seat-map.push.enabled" to "true")))
        val failure = assertNotNull(bootFailure(freshDbUrl(), mapOf("seat.seat-map.mode" to "ttl", "seat.seat-map.ttl" to "0s")))
        assertTrue("seat.seat-map.ttl" in messages(failure), messages(failure))
    }
}
