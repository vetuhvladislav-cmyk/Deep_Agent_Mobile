package dev.deepagent.mobile.agent.ledger

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Диагностический тест: печатает точный класс и сообщение исключения, которое
 * возникает на первом же execute(). Нужен, потому что отчёт CI показывает
 * только IllegalArgumentException без текста.
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
        val outcome = runCatching {
            ledger.execute(spec) {}
        }
        val failure = outcome.exceptionOrNull()
        if (failure != null) {
            val trace = failure.stackTrace.take(8).joinToString(" <- ") {
                it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber
            }
            throw AssertionError(
                "execute failed: " + failure::class.java.name +
                    " message=" + failure.message +
                    " trace=" + trace,
                failure,
            )
        }
    }

    @Test
    fun reportSpecConstructionFailure() {
        val outcome = runCatching {
            LedgerOperationSpec(
                operationId = "op.diag",
                operation = "test_operation",
                sessionId = "session-1",
                workspaceId = "workspace-1",
                targetSha = "target-sha",
                resolution = LedgerResolution.QUERYABLE,
            )
        }
        val failure = outcome.exceptionOrNull()
        if (failure != null) {
            throw AssertionError(
                "spec construction failed: " + failure::class.java.name +
                    " message=" + failure.message,
                failure,
            )
        }
    }
}
