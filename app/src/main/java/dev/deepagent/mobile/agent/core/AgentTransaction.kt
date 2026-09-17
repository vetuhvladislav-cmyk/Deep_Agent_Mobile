package dev.deepagent.mobile.agent.core

import dev.deepagent.mobile.agent.ledger.LedgerOperationSpec
import dev.deepagent.mobile.agent.ledger.LedgerPhase
import dev.deepagent.mobile.agent.ledger.LedgerRecord
import dev.deepagent.mobile.agent.ledger.LedgerRecoverySnapshot
import dev.deepagent.mobile.agent.ledger.OperationLedger

/**
 * Transaction seam between AgentCore orchestration and the durable ledger.
 *
 * AgentBridge remains unchanged; AgentCore owns lifecycle and this class owns
 * the PREPARED/STARTED/terminal transition boundary.
 */
class AgentTransaction(
    private val ledger: OperationLedger,
) {
    fun snapshot(): LedgerRecoverySnapshot = ledger.snapshot()

    fun begin(spec: LedgerOperationSpec): LedgerRecord = ledger.begin(spec)

    fun finish(
        operationId: String,
        phase: LedgerPhase,
        detail: String? = null,
    ): LedgerRecord = ledger.terminal(operationId, phase, detail)
}
