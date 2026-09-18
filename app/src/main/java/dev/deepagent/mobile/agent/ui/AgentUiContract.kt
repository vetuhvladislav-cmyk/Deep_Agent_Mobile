package dev.deepagent.mobile.agent.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import java.security.MessageDigest
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Stable UI contract used by Compose tests and accessibility services.
 *
 * testTag is intentionally kept separate from visible copy so localized text
 * changes do not invalidate regression selectors.
 */
object AgentUiContract {
    const val ROOT = "agent.console"
    const val BACK = "agent.back"
    const val TASK_INPUT = "agent.task.input"
    const val IMPORT_ZIP = "agent.workspace.import_zip"
    const val IMPORT_FOLDER = "agent.workspace.import_folder"
    const val WORKSPACE_REFRESH = "agent.workspace.refresh"
    const val APPROVE_PATCH = "agent.patch.approve"
    const val REJECT_PATCH = "agent.patch.reject"
    const val ROLLBACK_PATCH = "agent.patch.rollback"
    const val RUN_ACTIONS = "agent.actions.run"
    const val DOWNLOAD_ARTIFACT = "agent.actions.download_artifact"
    const val INSPECT_GIT = "agent.git.inspect"
    const val CREATE_BRANCH = "agent.git.create_branch"
    const val COMMIT = "agent.git.commit"
    const val PUSH = "agent.git.push"
    const val CREATE_PR = "agent.git.create_pr"
    const val PERMISSION_MENU = "agent.permission.menu"
    const val IMAGE_PICKER = "agent.image.picker"
    const val SUBMIT = "agent.session.submit"
    const val CANCEL = "agent.session.cancel"
    const val CLEAR_EVENTS = "agent.events.clear"
    const val EXPORT_JOURNAL = "agent.journal.export"
    const val CLEAR_CREDENTIALS = "agent.credentials.clear"
    const val SESSION_STATUS = "agent.session.status"
    const val LEDGER_UNKNOWN = "agent.ledger.unknown"
    const val RECHECK = "agent.session.recheck"
    const val PATCH_PREVIEW = "agent.patch.preview"
    const val PATCH_RECOVERY = "agent.patch.recovery"
    const val RUNTIME_START = "agent.runtime.start"
    const val RUNTIME_STOP = "agent.runtime.stop"
    const val INTERACTIVE_RUN = "agent.interactive.run"
    const val INTERACTIVE_CANCEL = "agent.interactive.cancel"
    const val ACTIONS_STATE = "agent.actions.state"
    const val GIT_STATE = "agent.git.state"

    fun workspace(id: String): String = "agent.workspace." + id
    fun workspaceEntry(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(path.toByteArray(Charsets.UTF_8))
        val stableId = digest.take(8)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "agent.workspace.entry." + stableId
    }
    fun event(sequence: Long): String = "agent.event." + sequence
}

fun Modifier.agentControl(
    testId: String,
    accessibilityLabel: String,
): Modifier = testTag(testId).semantics {
    contentDescription = accessibilityLabel
}
