package dev.deepagent.mobile.agent.ledger

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Диагностический тест: падает с полным описанием причины, чтобы отчёт CI
 * показал класс, сообщение и стек исключения.
 */
class LedgerDiagnosticsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun reportFirstExecuteFailure() {
        val file = temporaryFolder.newFile("diag.bin")
        val spec = LedgerOperationSpec(
            operationId = "op.diag",
            operation = "test_operation",
            sessionId = "session-1",
            workspaceId = "workspace-1",
            targetSha = "target-sha",
            resolution = LedgerResolution.QUERYABLE,
        )
        val ledger = OperationLedger(file)
        try {
            ledger.execute(spec) {}
        } catch (error: Throwable) {
            val trace = error.stackTrace.take(12).joinToString(" <- ") {
                it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber
            }
            throw AssertionError(
                "DIAG class=" + error::class.java.name +
                    " | message=" + error.message +
                    " | cause=" + (error.cause?.let { it::class.java.name + ": " + it.message }) +
                    " | trace=" + trace,
                error,
            )
        }
    }

    @Test
    fun reportRecoverySnapshot() {
        val file = temporaryFolder.newFile("diag-empty.bin")
        val ledger = OperationLedger(file)
        val snapshot = ledger.snapshot()
        if (snapshot.blocked) {
            throw AssertionError(
                "DIAG empty ledger blocked: health=" + snapshot.health +
                    " diagnostics=" + snapshot.diagnostics,
            )
        }
    }
}
