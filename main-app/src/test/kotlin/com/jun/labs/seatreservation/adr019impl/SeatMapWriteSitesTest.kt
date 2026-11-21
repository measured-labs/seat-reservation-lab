package com.jun.labs.seatreservation.adr019impl

import com.jun.labs.seatreservation.domain.ProductSeat
import com.jun.labs.seatreservation.domain.repository.ProductSeatRepository
import com.jun.labs.seatreservation.service.impl.seatmap.SeatMapChanges
import org.junit.jupiter.api.Test
import org.springframework.asm.ClassReader
import org.springframework.asm.ClassVisitor
import org.springframework.asm.MethodVisitor
import org.springframework.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 좌석 맵 쓰기 지점 감시(ADR-019 S4 · 분기의 답 '앱 쓰기 지점 + 감시 테스트'). `main-app/src/main` 아래에서 좌석 상태를 쓰는 파일 목록을 고정한다:
 * - 원천 문장: `product_seat`에 쓰는 SQL(UPDATE · INSERT INTO · DELETE FROM · TRUNCATE … product_seat) — 원천 파일 글자에서 찾는다(주석의 같은 글자도 걸린다 — 그때는 목록에 더하거나 글자를 고친다)
 * - 호출: `ProductSeat` 상태 변경 메서드(hold · confirm · expireHolds) · `ProductSeatRepository` 쓰기 메서드(holdIfAvailable · bumpVersion · save* · delete*) ·
 *   좌석 되돌림 도우미(`revertSeats` — LazyHoldProcess.kt) — 컴파일된 클래스의 메서드 호출에서 찾는다(받는 쪽 타입으로 판정)
 * 목록 밖 파일이 쓰면 실패한다(새 경로는 무효화를 붙이고 여기에 더한다). 목록의 각 파일(또는 그 쓰기를 감싼 파일)은 [SeatMapChanges]를 불러야 한다.
 */
class SeatMapWriteSitesTest {

    /** 쓰는 파일 → 그 쓰기의 변경 신호를 부르는 파일(같으면 자기 자신) — 경로는 `src/main/kotlin/com/jun/labs/seatreservation/` 아래 */
    private val expected = mapOf(
        "domain/repository/ProductSeatRepository.kt" to "service/impl/HoldSeatProcess.kt", // holdIfAvailable · bumpVersion은 HoldSeatProcess.hold 안(같은 트랜잭션)에서만 불린다
        "service/impl/hold/DbLockHoldStrategies.kt" to "service/impl/HoldSeatProcess.kt", // 조건부 UPDATE · 낙관락 전략의 콜백 — HoldSeatProcess.hold 안
        "service/impl/HoldSeatProcess.kt" to "service/impl/HoldSeatProcess.kt",
        "service/impl/ConfirmReservationService.kt" to "service/impl/ConfirmReservationService.kt",
        "service/impl/ExpireHoldsService.kt" to "service/impl/ExpireHoldsService.kt",
        "service/impl/AtomicConfirmProcess.kt" to "service/impl/AtomicConfirmProcess.kt",
        "service/impl/expiry/LazyHoldProcess.kt" to "service/impl/expiry/LazyHoldProcess.kt",
        "service/impl/expiry/LazyExpiryBatch.kt" to "service/impl/expiry/LazyExpiryBatch.kt",
        "service/impl/expiry/LazyConfirmProcess.kt" to "service/impl/expiry/LazyConfirmProcess.kt",
        "service/impl/expiry/SweepConditionalBatch.kt" to "service/impl/expiry/SweepConditionalBatch.kt",
        "service/impl/holdgroup/HoldGroupService.kt" to "service/impl/holdgroup/HoldGroupService.kt",
        "service/impl/holdgroup/HoldReleaseService.kt" to "service/impl/holdgroup/HoldReleaseService.kt",
        "service/impl/holdgroup/HoldGroupConfirmService.kt" to "service/impl/holdgroup/HoldGroupConfirmService.kt",
        "service/impl/payment/SubstituteAllocator.kt" to "service/impl/payment/SubstituteAllocator.kt",
        "loadtest/LoadtestDataService.kt" to "loadtest/LoadtestDataService.kt",
    )

    /** 파일 → 쓰기 지점 수(SQL 문장 수 + 쓰기 호출 수) — 아래 마지막 시험이 본다 */
    private val writeSites = mapOf(
        "domain/repository/ProductSeatRepository.kt" to 2, // holdIfAvailable · bumpVersion
        "service/impl/hold/DbLockHoldStrategies.kt" to 2, // holdIfAvailable · bumpVersion 호출
        "service/impl/HoldSeatProcess.kt" to 1, // seat.hold
        "service/impl/ConfirmReservationService.kt" to 1, // seat.confirm
        "service/impl/ExpireHoldsService.kt" to 1, // seat.expireHolds
        "service/impl/AtomicConfirmProcess.kt" to 3, // ① · ② · ③ 전환
        "service/impl/expiry/LazyHoldProcess.kt" to 3, // H10 · revertSeats 본문 · H4b의 revertSeats 호출
        "service/impl/expiry/LazyExpiryBatch.kt" to 1, // C6 revertSeats 호출
        "service/impl/expiry/LazyConfirmProcess.kt" to 1,
        "service/impl/expiry/SweepConditionalBatch.kt" to 1, // B5
        "service/impl/holdgroup/HoldGroupService.kt" to 2, // split · one-shot
        "service/impl/holdgroup/HoldReleaseService.kt" to 1,
        "service/impl/holdgroup/HoldGroupConfirmService.kt" to 1,
        "service/impl/payment/SubstituteAllocator.kt" to 2, // 원래 좌석 · 새 좌석
        "loadtest/LoadtestDataService.kt" to 3, // TRUNCATE · 시드 · 배경 시드
    )

    private val sourceRoot: Path = Paths.get("src/main/kotlin/com/jun/labs/seatreservation").toAbsolutePath()
    private val classRoot: Path = Paths.get(ProductSeat::class.java.protectionDomain.codeSource.location.toURI())

    private val sqlWrite = Regex("""(?is)\b(UPDATE\s+product_seat\b|INSERT\s+INTO\s+product_seat\b|DELETE\s+FROM\s+product_seat\b|TRUNCATE\b[^";]*?\bproduct_seat\b)""")

    private val seatOwner = internal(ProductSeat::class.java)
    private val repoOwner = internal(ProductSeatRepository::class.java)
    private val changesOwner = internal(SeatMapChanges::class.java)
    private val revertOwner = "com/jun/labs/seatreservation/service/impl/expiry/LazyHoldProcessKt"

    private fun internal(c: Class<*>) = c.name.replace('.', '/')

    private fun isWriteCall(owner: String, name: String): Boolean = when (owner) {
        seatOwner -> name in setOf("hold", "confirm", "expireHolds")
        repoOwner -> name in setOf("holdIfAvailable", "bumpVersion") || name.startsWith("save") || name.startsWith("delete")
        revertOwner -> name == "revertSeats"
        else -> false
    }

    /** 컴파일된 클래스마다 (원천 파일, 쓰기 호출 있음, 변경 신호 호출 있음) */
    private class Scanned(val source: String, var writes: Int = 0, var publishes: Boolean = false)

    private fun scanClasses(): List<Scanned> = Files.walk(classRoot).use { paths ->
        paths.filter { it.extension == "class" }.toList().map { file ->
            val reader = ClassReader(Files.readAllBytes(file))
            val pkg = reader.className.substringBeforeLast('/', "").removePrefix("com/jun/labs/seatreservation").removePrefix("/")
            lateinit var scanned: Scanned
            reader.accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitSource(source: String?, debug: String?) {
                    scanned = Scanned(if (pkg.isEmpty()) source.orEmpty() else "$pkg/${source.orEmpty()}")
                }

                override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor =
                    object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String?, isInterface: Boolean) {
                            if (isWriteCall(owner, name)) scanned.writes++
                            if (owner == changesOwner && name in setOf("publish", "publishSeats", "publishAll")) scanned.publishes = true
                        }
                    }
            }, ClassReader.SKIP_FRAMES)
            scanned
        }
    }

    /** 원천 파일 → 그 파일의 `product_seat` 쓰기 SQL 수 */
    private fun sqlWriteCounts(): Map<String, Int> = Files.walk(sourceRoot).use { paths ->
        paths.filter { it.extension == "kt" }.toList()
            .associate { it.relativeTo(sourceRoot).invariantSeparatorsPathString to sqlWrite.findAll(it.readText()).count() }
            .filterValues { it > 0 }
    }

    private fun sqlWriters(): Set<String> = sqlWriteCounts().keys

    /** 파일 → 쓰기 지점 수(SQL 문장 수 + 쓰기 호출 수) */
    private fun writeSiteCounts(): Map<String, Int> {
        val calls = scanClasses().filter { it.writes > 0 }.groupBy { it.source }.mapValues { (_, c) -> c.sumOf { it.writes } }
        val sql = sqlWriteCounts()
        return (sql.keys + calls.keys).associateWith { (sql[it] ?: 0) + (calls[it] ?: 0) }
    }

    @Test
    fun `좌석 상태를 쓰는 파일 목록이 고정 목록과 같다(목록 밖 쓰기 = 실패)`() {
        val scanned = scanClasses()
        val callWriters = scanned.filter { it.writes > 0 }.map { it.source }.toSet()
        val actual = sqlWriters() + callWriters
        assertEquals(expected.keys.sorted(), actual.sorted(), "좌석 상태 쓰기 파일 — 새 경로면 SeatMapChanges 호출을 붙이고 목록에 더한다(SQL: ${sqlWriters().sorted()} · 호출: ${callWriters.sorted()})")
    }

    @Test
    fun `목록의 각 쓰기는 변경 신호(SeatMapChanges)를 부르는 파일과 짝이 있다`() {
        val publishing = scanClasses().filter { it.publishes }.map { it.source }.toSet()
        expected.forEach { (writer, publisher) ->
            assertTrue(publisher in publishing, "$writer 의 쓰기 → $publisher 가 SeatMapChanges.publish* 를 불러야 한다(부르는 파일: ${publishing.sorted()})")
        }
        assertTrue(publishing.all { it in expected.values || it.startsWith("service/impl/seatmap/") }, "목록 밖에서 신호를 부르는 파일: ${publishing.sorted()}")
    }

    /**
     * 파일별 쓰기 지점 수 고정 — 목록에 이미 있는 파일에 새 `product_seat` 쓰기(SQL 문장 · 쓰기 호출)를 더하면 수가 달라져 실패한다
     * (새 쓰기가 신호를 부르는지 확인하고 수를 고친다). 한계: 같은 파일 안에서 쓰기 하나를 빼고 다른 쓰기를 더하면(수 같음) 잡지 못한다 ·
     * 신호 호출이 쓰기와 같은 트랜잭션 경로에 있는지는 보지 않는다(경로마다 '쓰기 → 다음 GET 반영' 시험이 본다).
     */
    @Test
    fun `파일별 쓰기 지점 수가 고정 값과 같다(목록 안 파일에 더한 쓰기 = 실패)`() {
        assertEquals(writeSites.toSortedMap(), writeSiteCounts().toSortedMap(), "파일별 쓰기 지점 수(SQL 문장 + 쓰기 호출) — 늘었으면 새 쓰기가 SeatMapChanges를 부르는지 확인하고 고친다")
    }
}
