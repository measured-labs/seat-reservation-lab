package com.jun.labs.seatreservation.adr019impl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.jun.labs.seatreservation.api.USER_ID_HEADER
import com.jun.labs.seatreservation.service.ExpireHoldsUseCase
import com.jun.labs.seatreservation.service.impl.seatmap.SeatMapTestHooks
import com.jun.labs.seatreservation.support.IntegrationTest
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ADR-019 구현 시험 공용(구현 워커 몫 — 블라인드 시험은 adr019/). 키 이름은 test-contract.md §1.
const val MAP_EVENT = "seat.seat-map.mode=event"
const val MAP_TTL = "seat.seat-map.mode=ttl"
const val MAP_REDIS = "seat.seat-map.mode=redis"
const val PUSH_ON = "seat.seat-map.push.enabled=true"
const val GROUP_ON = "seat.hold-group.enabled=true"

const val M_REQUESTS = "seat.seatmap.requests"
const val M_AGE = "seat.seatmap.age"
const val M_REDIS_FAILURES = "seat.seatmap.redis.failures"
const val M_CONNECTIONS = "seat.seatmap.push.connections"

/** MockMvc · DB 도우미 — 하위 클래스가 `@TestPropertySource`로 모드를 준다(IntegrationTest의 고정 키는 그대로) */
@AutoConfigureMockMvc
abstract class SeatMapImplBase : IntegrationTest() {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var meters: MeterRegistry
    @Autowired lateinit var expireHolds: ExpireHoldsUseCase

    val s: Long get() = schedule.id!!

    @AfterEach
    fun clearHooks() {
        SeatMapTestHooks.afterDbRead = null
        SeatMapTestHooks.afterSnapshotRead = null
    }

    fun mapRaw(scheduleId: Long = s): MockHttpServletResponse = mockMvc.get("/api/schedules/$scheduleId/seats").andReturn().response

    /** 좌석 맵 → 좌석 id → available. 200 · 필드 다섯 개 · seatId 오름차순도 단언한다 */
    fun map(scheduleId: Long = s): Map<Long, Boolean> {
        val r = mapRaw(scheduleId)
        assertEquals(200, r.status, r.contentAsString)
        val node = objectMapper.readTree(r.contentAsString)
        val ids = node.map { it.get("seatId").asLong() }
        assertEquals(ids.sorted(), ids, "seatId 오름차순")
        node.forEach { assertEquals(setOf("seatId", "section", "rowNo", "seatNo", "available"), it.fieldNames().asSequence().toSet(), "$it") }
        return node.associate { it.get("seatId").asLong() to it.get("available").asBoolean() }
    }

    fun requests(mode: String, source: String): Double = meters.find(M_REQUESTS).tags("mode", mode, "source", source).counter()?.count() ?: 0.0

    fun json(r: MockHttpServletResponse): JsonNode = objectMapper.readTree(r.contentAsString)

    fun holdRaw(seatId: Long, userId: Long): MockHttpServletResponse =
        mockMvc.post("/api/schedules/$s/seats/$seatId/hold") { header(USER_ID_HEADER, userId) }.andReturn().response

    fun hold(seatId: Long, userId: Long): Long = holdRaw(seatId, userId).let {
        assertEquals(201, it.status, "선점 준비: ${it.contentAsString}")
        json(it).get("holdId").asLong()
    }

    fun releaseHold(holdId: Long, userId: Long): MockHttpServletResponse =
        mockMvc.delete("/api/holds/$holdId") { header(USER_ID_HEADER, userId) }.andReturn().response

    fun confirm(holdId: Long, userId: Long): MockHttpServletResponse =
        mockMvc.post("/api/holds/$holdId/confirm") {
            header(USER_ID_HEADER, userId)
            contentType = MediaType.APPLICATION_JSON
            content = """{"paymentUid":"pay-$holdId"}"""
        }.andReturn().response

    fun holdGroup(userId: Long, vararg seatIds: Long): Long =
        mockMvc.post("/api/schedules/$s/hold-groups") {
            header(USER_ID_HEADER, userId)
            contentType = MediaType.APPLICATION_JSON
            content = """{"seatIds": [${seatIds.joinToString(",")}]}"""
        }.andReturn().response.let {
            assertEquals(201, it.status, "묶음 선점 준비: ${it.contentAsString}")
            json(it).get("groupId").asLong()
        }

    fun releaseGroup(groupId: Long, userId: Long): MockHttpServletResponse =
        mockMvc.delete("/api/hold-groups/$groupId") { header(USER_ID_HEADER, userId) }.andReturn().response

    fun seats(n: Int): List<Long> = (1..n).map { createSeat(it).id!! }

    fun assertStatus(expected: Int, r: MockHttpServletResponse, why: String) = assertEquals(expected, r.status, "$why: ${r.contentAsString}")

    fun assertTrueWithin(why: String, millis: Long = 10_000, cond: () -> Boolean) {
        val until = System.nanoTime() + millis * 1_000_000
        while (!cond()) {
            assertTrue(System.nanoTime() < until, why)
            Thread.sleep(50)
        }
    }
}
