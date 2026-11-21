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
    private class Scanned(val source: String, var writes: Boolean = false, var publishes: Boolean = false)

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
                            if (isWriteCall(owner, name)) scanned.writes = true
                            if (owner == changesOwner && name in setOf("publish", "publishSeats", "publishAll")) scanned.publishes = true
                        }
                    }
            }, ClassReader.SKIP_FRAMES)
            scanned
        }
    }

    private fun sqlWriters(): Set<String> = Files.walk(sourceRoot).use { paths ->
        paths.filter { it.extension == "kt" && sqlWrite.containsMatchIn(it.readText()) }
            .map { it.relativeTo(sourceRoot).invariantSeparatorsPathString }.toList().toSet()
    }

    @Test
    fun `좌석 상태를 쓰는 파일 목록이 고정 목록과 같다(목록 밖 쓰기 = 실패)`() {
        val scanned = scanClasses()
        val callWriters = scanned.filter { it.writes }.map { it.source }.toSet()
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
}
