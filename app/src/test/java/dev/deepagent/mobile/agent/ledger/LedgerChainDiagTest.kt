package dev.deepagent.mobile.agent.ledger

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LedgerChainDiagTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun dumpChainShape() {
        val file = temporaryFolder.newFile("diag-chain.bin")
        val ledger = OperationLedger(file)
        ledger.execute(
            LedgerOperationSpec(
                operationId = "op.x",
                operation = "test_operation",
                sessionId = "session-1",
                workspaceId = "workspace-1",
                targetSha = "target-sha",
                resolution = LedgerResolution.QUERYABLE,
            ),
        ) {}
        val attempts = ledger.attempts("op.x")
        val rendered = attempts.joinToString("; ") {
            "attempt=" + it.attempt +
                " phase=" + it.phase +
                " seq=" + it.sequence +
                " prev=" + (it.previousChainHash?.take(8) ?: "null") +
                " hash=" + (it.chainHash?.take(8) ?: "null")
        }
        throw AssertionError(
            "DIAG count=" + attempts.size +
                " | lastChainSeen=" + (ledger.snapshot().records.size) +
                " | " + rendered,
        )
    }
}
