package dev.deepagent.mobile.agent.ledger

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.CRC32C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OperationLedgerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun writesPreparedStartedTerminalAndPreventsReplay() {
        val file = temporaryFolder.newFile("ledger.bin")
        val ledger = OperationLedger(file)
        var effects = 0
        val spec = spec("op.one", LedgerResolution.IDEMPOTENT)

        ledger.execute(spec) {
            effects += 1
        }

        assertEquals(1, effects)
        assertEquals(LedgerPhase.SUCCEEDED, ledger.record("op.one")?.phase)

        val reopened = OperationLedger(file)
        try {
            reopened.execute(spec) {
                effects += 1
            }
        } catch (_: LedgerReplayBlockedException) {
            // Expected: a terminal operation cannot be replayed implicitly.
        }
        assertEquals(1, effects)
    }

    @Test
    fun interruptedOperationsBecomeUnknownWithDifferentRecoveryPolicies() {
        LedgerResolution.values().forEachIndexed { index, resolution ->
            val operationId = "pending.$index"
            val file = temporaryFolder.newFile("pending-$index.bin")
            OperationLedger(file).begin(spec(operationId, resolution))

            val recovered = OperationLedger(file)
            val snapshot = recovered.snapshot()
            assertEquals(LedgerHealth.RECOVERY_REQUIRED, snapshot.health)
            assertTrue(snapshot.unknownOperationIds.contains(operationId))
            assertEquals(
                when (resolution) {
                    LedgerResolution.QUERYABLE -> LedgerRecoveryAction.RECHECK_REQUIRED
                    LedgerResolution.IDEMPOTENT ->
                        LedgerRecoveryAction.EXPLICIT_RETRY_WITH_SAME_OPERATION_ID
                    LedgerResolution.BLIND -> LedgerRecoveryAction.MANUAL_RECONCILIATION
                },
                snapshot.recoveryActions[operationId],
            )
            assertEquals(LedgerPhase.UNKNOWN, recovered.record(operationId)?.phase)
        }
    }

    @Test
    fun explicitRetryUsesSameIdOnlyForIdempotentOperations() {
        LedgerResolution.values().forEachIndexed { index, resolution ->
            val operationId = "retry.$index"
            val file = temporaryFolder.newFile("retry-$index.bin")
            val spec = spec(operationId, resolution)
            OperationLedger(file).begin(spec)
            val recovered = OperationLedger(file)

            val attempt = runCatching { recovered.retryUnknown(spec) }
            if (resolution == LedgerResolution.IDEMPOTENT) {
                assertTrue(attempt.isSuccess)
                assertEquals(
                    LedgerPhase.STARTED,
                    recovered.record(spec.operationId)?.phase,
                )
                recovered.terminal(spec.operationId, LedgerPhase.SUCCEEDED, "rechecked")
            } else {
                assertFalse(attempt.isSuccess)
                assertEquals(LedgerPhase.UNKNOWN, recovered.record(spec.operationId)?.phase)
            }
        }
    }

    @Test
    fun trailingCorruptionIsTruncatedAndStartedOperationBecomesUnknown() {
        val file = temporaryFolder.newFile("trailing.bin")
        OperationLedger(file).begin(spec("op.trailing", LedgerResolution.QUERYABLE))
        val validLength = file.length()
        FileOutputStream(file, true).use { it.write(byteArrayOf(0x44, 0x41, 0x4d)) }

        val recovered = OperationLedger(file)
        assertTrue(recovered.snapshot().truncatedTrailingBytes)
        val recoveredBytes = Files.readAllBytes(file.toPath())
        val trailingMarkerPresent = recoveredBytes.size.toLong() >= validLength + 3L &&
            recoveredBytes.copyOfRange(
                validLength.toInt(),
                validLength.toInt() + 3,
            ).contentEquals(byteArrayOf(0x44, 0x41, 0x4D))
        assertFalse(trailingMarkerPresent)
        assertEquals(LedgerPhase.UNKNOWN, recovered.record("op.trailing")?.phase)
    }

    @Test
    fun corruptedMiddleBlocksAutomaticContinuation() {
        val file = temporaryFolder.newFile("middle.bin")
        val ledger = OperationLedger(file)
        ledger.execute(spec("op.a", LedgerResolution.QUERYABLE)) {}
        ledger.execute(spec("op.b", LedgerResolution.QUERYABLE)) {}

        val bytes = Files.readAllBytes(file.toPath())
        bytes[30] = (bytes[30].toInt() xor 0x01).toByte()
        Files.write(file.toPath(), bytes)

        val recovered = OperationLedger(file)
        assertEquals(LedgerHealth.CORRUPT, recovered.snapshot().health)
        assertTrue(recovered.snapshot().blocked)
        try {
            recovered.begin(spec("op.c", LedgerResolution.QUERYABLE))
            throw AssertionError("corrupted ledger must reject new side effects")
        } catch (_: LedgerBlockedException) {
            // Expected.
        }
    }

    @Test
    fun corruptedTerminalRecordDoesNotLookSuccessful() {
        val file = temporaryFolder.newFile("terminal.bin")
        val ledger = OperationLedger(file)
        ledger.begin(spec("op.terminal", LedgerResolution.QUERYABLE))
        ledger.terminal("op.terminal", LedgerPhase.SUCCEEDED, "success")

        val bytes = Files.readAllBytes(file.toPath())
        bytes[bytes.lastIndex] = (bytes[bytes.lastIndex].toInt() xor 0x01).toByte()
        Files.write(file.toPath(), bytes)

        val recovered = OperationLedger(file)
        assertEquals(LedgerPhase.UNKNOWN, recovered.record("op.terminal")?.phase)
        assertTrue(recovered.snapshot().unknownOperationIds.contains("op.terminal"))
    }

    @Test
    fun hmacKeyLossBlocksReadInsteadOfDowngradingIntegrity() {
        val file = temporaryFolder.newFile("hmac.bin")
        val key = "ledger-test-key".toByteArray()
        val spec = spec("op.hmac", LedgerResolution.BLIND).copy(
            integrityMode = LedgerIntegrityMode.HMAC_SHA256,
            keyVersion = 1,
        )
        OperationLedger(file, StaticHmacKeyProvider(key)).execute(spec) {}

        val recovered = OperationLedger(file)
        assertEquals(LedgerHealth.KEY_UNAVAILABLE, recovered.snapshot().health)
        assertTrue(recovered.snapshot().blocked)
    }

    @Test
    fun olderFormatRequiresExplicitExportImport() {
        val file = temporaryFolder.newFile("downgrade.bin")
        OperationLedger(file).begin(spec("op.downgrade", LedgerResolution.QUERYABLE))

        val bytes = Files.readAllBytes(file.toPath())
        // Frame format version is the second big-endian int.
        bytes[7] = 0
        Files.write(file.toPath(), bytes)

        val recovered = OperationLedger(file)
        assertEquals(LedgerHealth.CORRUPT, recovered.snapshot().health)
        assertTrue(recovered.snapshot().blocked)
        assertTrue(
            recovered.snapshot().diagnostics.contains(
                "LEDGER_DOWNGRADE_REQUIRES_EXPORT_IMPORT",
            ),
        )
    }

    @Test
    fun batchedDurabilityCannotBeUsedForSideEffects() {
        try {
            spec("batched.side.effect", LedgerResolution.QUERYABLE).copy(
                durability = LedgerDurability.BATCHED,
            )
            throw AssertionError("side-effect BATCHED must be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("BATCHED"))
        }
    }

    /**
     * SEC-03: explicit retry не удаляет предыдущую попытку. UNKNOWN остаётся в
     * истории под тем же operationId, новая попытка получает следующий номер.
     */
    @Test
    fun explicitRetryPreservesUnknownAttemptHistory() {
        val file = temporaryFolder.newFile("attempt-history.bin")
        val spec = spec("op.attempts", LedgerResolution.IDEMPOTENT)
        OperationLedger(file).begin(spec)

        val recovered = OperationLedger(file)
        assertEquals(1, recovered.attemptCount(spec.operationId))
        assertEquals(1, recovered.record(spec.operationId)?.attempt)
        assertEquals(LedgerPhase.UNKNOWN, recovered.record(spec.operationId)?.phase)

        val secondAttempt = recovered.retryUnknown(spec)
        assertEquals(2, secondAttempt.attempt)
        assertEquals(LedgerPhase.STARTED, secondAttempt.phase)
        assertEquals(2, recovered.attemptCount(spec.operationId))

        val history = recovered.attempts(spec.operationId)
        assertEquals(listOf(1, 2), history.map { it.attempt })
        assertEquals(LedgerPhase.UNKNOWN, history[0].phase)
        assertEquals(LedgerPhase.STARTED, history[1].phase)

        recovered.terminal(spec.operationId, LedgerPhase.SUCCEEDED, "rechecked")
        val afterTerminal = recovered.attempts(spec.operationId)
        assertEquals(LedgerPhase.UNKNOWN, afterTerminal[0].phase)
        assertEquals(LedgerPhase.SUCCEEDED, afterTerminal[1].phase)

        // История попыток сохраняется и после перезапуска процесса.
        val reopened = OperationLedger(file)
        assertEquals(2, reopened.attemptCount(spec.operationId))
        assertEquals(LedgerPhase.SUCCEEDED, reopened.record(spec.operationId)?.phase)
        assertEquals(
            listOf(LedgerPhase.UNKNOWN, LedgerPhase.SUCCEEDED),
            reopened.attempts(spec.operationId).map { it.phase },
        )
    }

    /** SEC-03: retry по-прежнему запрещён для не-UNKNOWN и не-IDEMPOTENT. */
    @Test
    fun retryUnknownStillRejectsNonIdempotentAndNonUnknown() {
        val file = temporaryFolder.newFile("retry-guard.bin")
        val idempotent = spec("op.guard", LedgerResolution.IDEMPOTENT)

        val successful = OperationLedger(file)
        successful.execute(idempotent) {}
        val retryAfterSuccess = runCatching { successful.retryUnknown(idempotent) }
        assertFalse(retryAfterSuccess.isSuccess)
        assertEquals(1, successful.attemptCount(idempotent.operationId))

        val otherFile = temporaryFolder.newFile("retry-guard-blind.bin")
        val blind = spec("op.guard.blind", LedgerResolution.BLIND)
        OperationLedger(otherFile).begin(blind)
        val recoveredBlind = OperationLedger(otherFile)
        val retryBlind = runCatching { recoveredBlind.retryUnknown(blind) }
        assertFalse(retryBlind.isSuccess)
        assertEquals(1, recoveredBlind.attemptCount(blind.operationId))
    }

    /**
     * SEC-04: перезапись последней записи обнаруживается. Меняется поле
     * `detail`, которое не входит в checksum-контракт вызывающей стороны, а
     * сама запись остаётся последней — то есть это не trailing-усечение.
     */
    @Test
    fun chainIntegrityDetectsRewrittenRecord() {
        val file = temporaryFolder.newFile("chain-rewrite.bin")
        val ledger = OperationLedger(file)
        ledger.execute(spec("op.ra", LedgerResolution.QUERYABLE)) {}
        ledger.execute(spec("op.rb", LedgerResolution.QUERYABLE)) {}

        val bytes = Files.readAllBytes(file.toPath())
        val detailMarker = "effect completed".toByteArray(Charsets.UTF_8)
        val detailIndex = indexOf(bytes, detailMarker)
        assertTrue("ожидался detail в файле ledger", detailIndex >= 0)
        bytes[detailIndex] = 'E'.code.toByte()
        Files.write(file.toPath(), bytes)

        val recovered = OperationLedger(file)
        assertTrue(
            "повреждённая цепочка должна блокировать ledger; diagnostics=" +
                recovered.snapshot().diagnostics,
            recovered.snapshot().blocked,
        )
        try {
            recovered.begin(spec("op.rd", LedgerResolution.QUERYABLE))
            throw AssertionError("повреждённая цепочка должна блокировать запись")
        } catch (_: LedgerBlockedException) {
            // Expected.
        }
    }

    /**
     * SEC-04: удаление записи из середины цепочки обнаруживается через разрыв
     * sequence.
     */
    @Test
    fun chainIntegrityDetectsRemovedMiddleRecord() {
        val file = temporaryFolder.newFile("chain-removed.bin")
        val ledger = OperationLedger(file)
        ledger.execute(spec("op.m1", LedgerResolution.QUERYABLE)) {}
        ledger.execute(spec("op.m2", LedgerResolution.QUERYABLE)) {}
        ledger.execute(spec("op.m3", LedgerResolution.QUERYABLE)) {}

        val bytes = Files.readAllBytes(file.toPath())
        val magic = byteArrayOf(0x44, 0x41, 0x4C, 0x47)
        val offsets = buildList {
            var index = 0
            while (index <= bytes.size - magic.size) {
                var matched = true
                for (offset in magic.indices) {
                    if (bytes[index + offset] != magic[offset]) {
                        matched = false
                        break
                    }
                }
                if (matched) add(index)
                index += 1
            }
        }
        assertEquals(3, offsets.size)
        val withoutMiddle = bytes.copyOfRange(0, offsets[1]) +
            bytes.copyOfRange(offsets[2], bytes.size)
        Files.write(file.toPath(), withoutMiddle)

        val recovered = OperationLedger(file)
        assertTrue(
            "удаление записи должно блокировать ledger; diagnostics=" +
                recovered.snapshot().diagnostics,
            recovered.snapshot().blocked,
        )
        assertTrue(
            recovered.snapshot().diagnostics.any {
                it == "LEDGER_SEQUENCE_GAP" || it == "LEDGER_CHAIN_HASH_MISMATCH"
            },
        )
    }

    /** SEC-04: каждая запись связана с предыдущей; первая ссылается на null. */
    @Test
    fun chainHashesLinkConsecutiveRecords() {
        val file = temporaryFolder.newFile("chain-links.bin")
        val ledger = OperationLedger(file)
        ledger.execute(spec("op.x", LedgerResolution.QUERYABLE)) {}

        val first = ledger.attempts("op.x").first()
        assertNotNull(first.chainHash)
        assertEquals(null, first.previousChainHash)

        ledger.execute(spec("op.y", LedgerResolution.QUERYABLE)) {}
        val second = ledger.attempts("op.y").first()
        assertEquals(first.chainHash, second.previousChainHash)
        assertNotNull(second.chainHash)
        assertFalse(first.chainHash == second.chainHash)
    }

    /**
     * SEC-04, главный тест: пересобранный кадр с **корректным** CRC32C и
     * корректной sequence. Per-record checksum такую подмену не видит, поэтому
     * обнаружить её может только цепочка. Тест падает, если убрать пересчёт
     * chain hash.
     */
    @Test
    fun chainDetectsReframedRecordWithValidChecksum() {
        val file = temporaryFolder.newFile("chain-forged.bin")
        val ledger = OperationLedger(file)
        ledger.execute(spec("op.real", LedgerResolution.QUERYABLE)) {}

        val original = ledger.attempts("op.real").first()
        // Подменяем поле, не входящее в checksum-контракт вызывающей стороны,
        // и пересчитываем CRC32C — кадр становится формально валидным.
        val forged = original.copy(
            sequence = original.sequence + 1L,
            targetSha = "forged-target",
            previousChainHash = null,
            chainHash = null,
        )
        FileOutputStream(file, true).use { it.write(frameFor(forged)) }

        val recovered = OperationLedger(file)
        assertTrue(
            "подделка с валидным CRC должна блокировать ledger; diagnostics=" +
                recovered.snapshot().diagnostics,
            recovered.snapshot().blocked,
        )
        assertTrue(
            recovered.snapshot().diagnostics.contains("LEDGER_CHAIN_HASH_MISMATCH"),
        )
    }

    /**
     * Собирает кадр ledger так же, как это делает append: JSON payload и
     * корректный CRC32C. Используется только для проверки целостности.
     */
    private fun frameFor(record: LedgerRecord): ByteArray {
        val sealed = record.copy(
            schemaVersion = LedgerRecord.SCHEMA_VERSION,
            integrityMode = LedgerIntegrityMode.CRC32C,
            keyVersion = 0,
        )
        val payload = sealed.toJson().toString().toByteArray(Charsets.UTF_8)
        val crc = CRC32C()
        crc.update(payload)
        val checksum = ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(crc.value.toInt())
            .array()
        return ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(0x44414C47)
                output.writeInt(1)
                output.writeInt(LedgerIntegrityMode.CRC32C.ordinal)
                output.writeInt(0)
                output.writeInt(payload.size)
                output.writeInt(checksum.size)
                output.write(checksum)
                output.write(payload)
            }
            buffer.toByteArray()
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        var index = 0
        while (index <= haystack.size - needle.size) {
            var matched = true
            for (offset in needle.indices) {
                if (haystack[index + offset] != needle[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) return index
            index += 1
        }
        return -1
    }

    private fun spec(
        operationId: String,
        resolution: LedgerResolution,
    ): LedgerOperationSpec {
        return LedgerOperationSpec(
            operationId = operationId,
            operation = "test_operation",
            sessionId = "session-1",
            workspaceId = "workspace-1",
            targetSha = "target-sha",
            resolution = resolution,
        )
    }
}
