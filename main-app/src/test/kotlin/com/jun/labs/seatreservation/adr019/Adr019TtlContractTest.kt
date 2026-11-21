package com.jun.labs.seatreservation.adr019

import org.springframework.test.context.TestPropertySource
import java.time.Duration

/** S1 · S2 · S3 — 앱 안 TTL 캐시(`ttl`). TTL 키를 기본(1s)이 아닌 2s로 주어 키 바인딩도 본다(test-contract §1). */
@TestPropertySource(properties = [P19_TTL, "$SEAT_MAP_TTL_KEY=2s"])
class Adr019TtlContractTest : Adr019TtlCacheContractBase() {
    override val modeValue = "ttl"
    override val ttl: Duration = Duration.ofSeconds(2)
}
