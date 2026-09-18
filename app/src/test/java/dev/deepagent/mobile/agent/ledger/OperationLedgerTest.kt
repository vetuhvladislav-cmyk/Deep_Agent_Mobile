package dev.deepagent.mobile.agent.ledger

import java.io.FileOutputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val spec = spec("op-1", LedgerResolution.IDEMPOTENT)

        ledger.execute(spec) {
            effects += 1
        }

        assertEquals(1, effects)
        assertEquals(LedgerPhase.SUCCEEDED, ledger.record("op-1")?.phase)

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
            val file = temporaryFolder.newFile("pending-$index.bin")
            OperationLedger(file).begin(spec("pending-$index", resolution))

            val recovered = OperationLedger(file)
            val snapshot = recovered.snapshot()
            assertEquals(LedgerHealth.RECOVERY_REQUIRED, snapshot.health)
            assertTrue(snapshot.unknownOperationIds.contains("pending-$index"))
            assertEquals(
                when (resolution) {
                    LedgerResolution.QUERYABLE -> LedgerRecoveryAction.RECHECK_REQUIRED
                    LedgerResolution.IDEMPOTENT ->
                        LedgerRecoveryAction.EXPLICIT_RETRY_WITH_SAME_OPERATION_ID
                    LedgerResolution.BLIND -> LedgerRecoveryAction.MANUAL_RECONCILIATION
                },
                snapshot.recoveryActions["pending-$index"],
            )
            assertEquals(LedgerPhase.UNKNOWN, recovered.record("pending-$index")?.phase)
        }
    }

    @Test
    fun explicitRetryUsesSameIdOnlyForIdempotentOperations() {
        LedgerResolution.values().forEachIndexed { index, resolution ->
            val file = temporaryFolder.newFile("retry-$index.bin")
            val spec = spec("retry-$index", resolution)
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
        OperationLedger(file).begin(spec("op-trailing", LedgerResolution.QUERYABLE))
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
        assertEquals(LedgerPhase.UNKNOWN, recovered.record("op-trailing")?.phase)
    }

    @Test
    fun corruptedMiddleBlocksAutomaticContinuation() {
        val file = temporaryFolder.newFile("middle.bin")
        val ledger = OperationLedger(file)
        ledger.execute(spec("op-a", LedgerResolution.QUERYABLE)) {}
        ledger.execute(spec("op-b", LedgerResolution.QUERYABLE)) {}

        val bytes = Files.readAllBytes(file.toPath())
        bytes[30] = (bytes[30].toInt() xor 0x01).toByte()
        Files.write(file.toPath(), bytes)

        val recovered = OperationLedger(file)
        assertEquals(LedgerHealth.CORRUPT, recovered.snapshot().health)
        assertTrue(recovered.snapshot().blocked)
        try {
            recovered.begin(spec("op-c", LedgerResolution.QUERYABLE))
            throw AssertionError("corrupted ledger must reject new side effects")
        } catch (_: LedgerBlockedException) {
            // Expected.
        }
    }

    @Test
    fun corruptedTerminalRecordDoesNotLookSuccessful() {
        val file = temporaryFolder.newFile("terminal.bin")
        val ledger = OperationLedger(file)
        ledger.begin(spec("op-terminal", LedgerResolution.QUERYABLE))
        ledger.terminal("op-terminal", LedgerPhase.SUCCEEDED, "success")

        val bytes = Files.readAllBytes(file.toPath())
        bytes[bytes.lastIndex] = (bytes[bytes.lastIndex].toInt() xor 0x01).toByte()
        Files.write(file.toPath(), bytes)

        val recovered = OperationLedger(file)
        assertEquals(LedgerPhase.UNKNOWN, recovered.record("op-terminal")?.phase)
        assertTrue(recovered.snapshot().unknownOperationIds.contains("op-terminal"))
    }

    @Test
    fun hmacKeyLossBlocksReadInsteadOfDowngradingIntegrity() {
        val file = temporaryFolder.newFile("hmac.bin")
        val key = "ledger-test-key".toByteArray()
        val spec = spec("op-hmac", LedgerResolution.BLIND).copy(
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
        OperationLedger(file).begin(spec("op-downgrade", LedgerResolution.QUERYABLE))

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
            spec("batched-side-effect", LedgerResolution.QUERYABLE).copy(
                durability = LedgerDurability.BATCHED,
            )
            throw AssertionError("side-effect BATCHED must be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("BATCHED"))
        }
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
