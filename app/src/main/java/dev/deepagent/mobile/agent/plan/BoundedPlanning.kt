package dev.deepagent.mobile.agent.plan

import dev.deepagent.mobile.agent.deepseek.DeepSeekRoundResult
import dev.deepagent.mobile.agent.model.ExecutionTarget
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small deterministic planning boundary for the MVP.
 *
 * The plan is intentionally linear and bounded. It is an execution contract
 * for the host, not a model-controlled DAG or a permission escalation path.
 */
enum class BoundedPlanStepKind {
    CONTEXT,
    ANALYZE,
    TOOL,
    VERIFY,
    COMPLETE,
}

data class BoundedPlanStep(
    val id: String,
    val kind: BoundedPlanStepKind,
    val summary: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("kind", kind.name)
        .put("summary", summary)
}

data class BoundedPlan(
    val target: ExecutionTarget,
    val maxToolRounds: Int,
    val steps: List<BoundedPlanStep>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("target", target.name)
        .put("max_tool_rounds", maxToolRounds)
        .put(
            "steps",
            JSONArray().apply {
                steps.take(MAX_STEPS).forEach { put(it.toJson()) }
            },
        )

    companion object {
        const val VERSION = 1
        const val MAX_STEPS = 6
    }
}

object BoundedPlanner {
    fun create(
        target: ExecutionTarget,
        hasWorkspace: Boolean,
        maxToolRounds: Int,
    ): BoundedPlan {
        require(maxToolRounds in 1..8) {
            "maxToolRounds выходит за bounded limit"
        }

        val steps = listOf(
            BoundedPlanStep(
                id = "context",
                kind = BoundedPlanStepKind.CONTEXT,
                summary = "Проверить session, target и границу workspace",
            ),
            BoundedPlanStep(
                id = "analyze",
                kind = BoundedPlanStepKind.ANALYZE,
                summary = if (hasWorkspace) {
                    "Собрать ограниченный контекст через read-only ToolRouter"
                } else {
                    "Работать без workspace tools"
                },
            ),
            BoundedPlanStep(
                id = "tools",
                kind = BoundedPlanStepKind.TOOL,
                summary = "Обработать только allowlisted tool calls в пределах бюджета",
            ),
            BoundedPlanStep(
                id = "verify",
                kind = BoundedPlanStepKind.VERIFY,
                summary = if (target == ExecutionTarget.REMOTE_ACTIONS) {
                    "Проверить commit, run и artifact provenance"
                } else {
                    "Проверить evidence результата и recovery state"
                },
            ),
            BoundedPlanStep(
                id = "complete",
                kind = BoundedPlanStepKind.COMPLETE,
                summary = "Завершить только при подтверждённом результате",
            ),
        )

        return BoundedPlan(
            target = target,
            maxToolRounds = maxToolRounds,
            steps = steps.take(BoundedPlan.MAX_STEPS),
        )
    }
}

/**
 * Evidence gate for a provider round.
 *
 * A transport-level response object is not enough: the provider must have
 * emitted a completed response, must not report a non-completed status, and
 * must not leave an unparsed function call in the output.
 */
enum class CompletionDecision {
    COMPLETED,
    CONTINUE,
    UNKNOWN,
}

data class CompletionEvaluation(
    val decision: CompletionDecision,
    val reason: String,
)

object CompletionEvaluator {
    fun evaluate(
        result: DeepSeekRoundResult,
        nextToolRound: Int,
        maxToolRounds: Int,
    ): CompletionEvaluation {
        if (result.failure != null) {
            return CompletionEvaluation(
                decision = CompletionDecision.UNKNOWN,
                reason = "Provider round завершился с failure",
            )
        }

        val response = result.response
            ?: return CompletionEvaluation(
                decision = CompletionDecision.UNKNOWN,
                reason = "Provider не вернул completed response",
            )

        val status = response.optString("status").trim()
        if (status.isNotBlank() && !status.equals("completed", ignoreCase = true)) {
            return CompletionEvaluation(
                decision = CompletionDecision.UNKNOWN,
                reason = "Статус provider response не подтверждён: " + status,
            )
        }

        val outputContainsFunctionCall = response
            .optJSONArray("output")
            ?.let { output ->
                (0 until output.length()).any { index ->
                    output.optJSONObject(index)
                        ?.optString("type")
                        ?.equals("function_call", ignoreCase = true) == true
                }
            } == true

        if (result.functionCalls.isEmpty() && outputContainsFunctionCall) {
            return CompletionEvaluation(
                decision = CompletionDecision.UNKNOWN,
                reason = "Provider output содержит неразобранный function call",
            )
        }

        if (result.functionCalls.isEmpty()) {
            return CompletionEvaluation(
                decision = CompletionDecision.COMPLETED,
                reason = "completed response без tool calls",
            )
        }

        if (nextToolRound > maxToolRounds) {
            return CompletionEvaluation(
                decision = CompletionDecision.UNKNOWN,
                reason = "Достигнут лимит bounded tool rounds",
            )
        }

        return CompletionEvaluation(
            decision = CompletionDecision.CONTINUE,
            reason = "Есть валидные tool calls; продолжается bounded round",
        )
    }
}
