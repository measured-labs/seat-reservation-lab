package com.jun.labs.seatreservation.service.impl.seatmap

import com.jun.labs.seatreservation.service.SeatMapMode
import com.jun.labs.seatreservation.service.SeatMapProperties
import io.lettuce.core.ClientOptions
import io.lettuce.core.SocketOptions
import io.lettuce.core.TimeoutOptions
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails
import org.springframework.boot.autoconfigure.data.redis.RedisProperties
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * ADR-019 redis 모드 전용 Redis 클라이언트(설계-4 · S6). 접속 주소는 기존 `spring.data.redis.*`(RedisConnectionDetails)를 쓰지만 연결 · 클라이언트는 따로 만든다 —
 * 명령 · 연결 제한 시간 = `seat.seat-map.redis-timeout`(기본 200ms) · 끊긴 동안 명령을 쌓지 않고 곧바로 거부한다(REJECT_COMMANDS — 재연결은 뒤에서 계속).
 * 그래서 Redis가 멈춰도 좌석 맵 GET은 제한 시간 안에 DB로 답한다. 공용 StringRedisTemplate(ADR-003 Redis 선점 전략 등)의 설정 · 동작은 그대로다.
 * redis 모드가 아니면 아무것도 만들지 않는다(다른 모드의 좌석 맵은 Redis에 닿지 않는다).
 * TLS는 `spring.data.redis.ssl.enabled`(또는 ssl.bundle 지정)를 따라 켠다.
 * ponytail: 단일 노드(standalone) 주소만 쓴다 — sentinel · cluster로 측정하게 되면 RedisConnectionDetails의 그 설정을 여기에 옮긴다.
 *   ssl.bundle의 키 · 신뢰 저장소는 옮기지 않는다(JVM 기본 신뢰 저장소로 검증) — 사설 인증서로 측정하게 되면 SslBundles에서 옮긴다.
 */
@Component
class SeatMapRedis(
    properties: SeatMapProperties,
    connection: ObjectProvider<RedisConnectionDetails>,
    redisProperties: ObjectProvider<RedisProperties>,
) {
    /** 시험이 클라이언트 구성(TLS 등)을 본다 */
    internal val factory: LettuceConnectionFactory? =
        if (properties.mode != SeatMapMode.REDIS) null else build(connection.getObject(), properties.redisTimeout, redisProperties.getIfAvailable()?.ssl?.let { it.isEnabled || it.bundle != null } == true)
    private val template: StringRedisTemplate? = factory?.let { StringRedisTemplate(it) }

    private fun ops(): StringRedisTemplate = checkNotNull(template) { "좌석 맵 Redis는 seat.seat-map.mode=redis에서만 쓴다" }

    /** 맵 키와 세대 키를 한 번에(MGET) — (맵 값, 세대 값) */
    fun getWithEpoch(key: String, epochKey: String): Pair<String?, String?> =
        ops().opsForValue().multiGet(listOf(key, epochKey)).orEmpty().let { it.getOrNull(0) to it.getOrNull(1) }

    fun increment(key: String): Long? = ops().opsForValue().increment(key)

    fun set(key: String, value: String, expiry: Duration) = ops().opsForValue().set(key, value, expiry)

    @PreDestroy
    fun close() {
        factory?.destroy()
    }

    private companion object {
        fun build(details: RedisConnectionDetails, timeout: Duration, ssl: Boolean): LettuceConnectionFactory {
            val node = details.standalone
            val server = RedisStandaloneConfiguration(node.host, node.port).apply {
                database = node.database
                details.username?.let { username = it }
                details.password?.let { setPassword(it) }
            }
            val client = LettuceClientConfiguration.builder()
                .apply { if (ssl) useSsl() }
                .commandTimeout(timeout)
                .clientOptions(
                    ClientOptions.builder()
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .socketOptions(SocketOptions.builder().connectTimeout(timeout).build())
                        .timeoutOptions(TimeoutOptions.enabled(timeout))
                        .build(),
                )
                .build()
            return LettuceConnectionFactory(server, client).apply {
                afterPropertiesSet()
                start()
            }
        }
    }
}
