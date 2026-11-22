package com.jun.labs.seatreservation.adr019

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals

/*
 * S3 화면 나이 상한(ttl · redis) — TTL 안에는 낡은 값이 나올 수 있고(캐시에서 답한 응답은 캐시에 넣은 맵 그대로), TTL이 지나면(앱 Clock 이동만으로) 새 값.
 * 캐시 TTL은 앱 Clock으로 잰다 — Redis에 넣은 항목도 Redis 자체 만료가 아니라 같은 시계로 판정한다(test-contract §1).
 * 쓰기는 JDBC로 직접 한다(앱 경로를 거치지 않음) — ttl · redis는 쓰기 경로 무효화에 기대지 않으므로(설계-4) 무효화 유무와 상관없이 같은 기대다.
 * 화면 나이 지표 `seat.seatmap.age` = 응답 시각 − 그 맵을 DB에서 읽기 시작한 시각(Clock 기준 — §3).
 */
abstract class Adr019TtlCacheContractBase : Adr019ModeContractBase() {

    /** 이 컨텍스트의 TTL */
    abstract val ttl: Duration

    @Test
    fun `S3 — TTL 안에는 캐시(낡은 값) · TTL이 지나면 DB에서 새 값 · 화면 나이는 TTL을 넘지 않는다`() {
        val a = seat()
        val b = seat()
        val db0 = requests("db")
        val cache0 = requests("cache")
        val age0 = ageStat()

        val first = availability(why = "첫 GET")
        assertEquals(mapOf(a to true, b to true), first)
        assertEquals(db0 + 1, requests("db"), "첫 GET은 DB(source=db)")

        jdbcTemplate.update("UPDATE product_seat SET status = 'HELD' WHERE id = ?", a) // 앱 밖 쓰기
        val half = ttl.dividedBy(2)
        clock.advance(half)
        val stale = availability(why = "TTL 안")
        assertEquals(cache0 + 1, requests("cache"), "TTL 안의 GET은 캐시에서 답한다(source=cache)")
        assertEquals(first, stale, "캐시에서 답한 맵 = 캐시에 넣은 맵(낡은 값이 나올 수 있다 — S3)")

        clock.advance(ttl) // 읽은 시각에서 1.5 TTL
        val fresh = availability(why = "TTL 뒤")
        assertEquals(mapOf(a to false, b to true), fresh, "TTL이 지난 캐시를 내지 않는다(S3) — 앱 시계 이동만으로 새로 읽는다")
        assertEquals(db0 + 2, requests("db"), "TTL 뒤 GET은 DB(source=db)")

        val age = ageStat()
        assertEquals(age0.count + 3, age.count, "$M19_AGE — 응답마다 1")
        assertEquals(
            half.toMillis().toDouble(), age.totalMillis - age0.totalMillis, 1.0,
            "화면 나이 합 = 0(DB) + TTL/2(캐시) + 0(DB) — 고정 시계 기준",
        )
    }

    @Test
    fun `S3 — 캐시에 넣은 맵의 나이는 TTL 안에서만 응답에 쓰인다(TTL 직전까지 캐시 · 넘으면 DB)`() {
        val a = seat()
        availability(why = "적재")
        jdbcTemplate.update("UPDATE product_seat SET status = 'RESERVED' WHERE id = ?", a)
        clock.advance(ttl.minusMillis(1))
        val cache0 = requests("cache")
        assertEquals(mapOf(a to true), availability(why = "TTL − 1ms"), "TTL 직전은 캐시(낡은 값)")
        assertEquals(cache0 + 1, requests("cache"))
        clock.advance(Duration.ofMillis(2))
        assertEquals(mapOf(a to false), availability(why = "TTL + 1ms"), "TTL을 넘긴 캐시는 내지 않는다")
    }

    @Test
    fun `S3 — 회차마다 따로 캐시한다(다른 회차의 GET이 이 회차 캐시를 쓰지 않는다)`() {
        val a = seat()
        val other = productScheduleRepository.save(
            com.jun.labs.seatreservation.domain.ProductSchedule(productId = schedule.productId, startsAt = schedule.startsAt),
        )
        val b = productSeatRepository.save(
            com.jun.labs.seatreservation.domain.ProductSeat(scheduleId = other.id!!, section = "B", rowNo = 2, seatNo = 7),
        ).id!!
        assertEquals(mapOf(a to true), availability(s, "회차 1"))
        assertEquals(mapOf(b to true), availability(other.id!!, "회차 2"))
        assertEquals(listOf(MapSeat(b, "B", 2, 7, true)), seatMap(other.id!!, "회차 2 다시"))
    }
}
