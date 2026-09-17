package dev.deepagent.mobile.agent.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
    const val EXPORT_JOURNAL = "agent.credentials.export_journal"
    const val CLEAR_CREDENTIALS = "agent.credentials.clear"

    fun workspace(id: String): String = "agent.workspace." + id
    fun workspaceEntry(path: String): String = "agent.workspace.entry." + path.hashCode()
    fun event(sequence: Long): String = "agent.event." + sequence
}

fun Modifier.agentControl(
    testId: String,
    accessibilityLabel: String,
): Modifier = testTag(testId).semantics {
    contentDescription = accessibilityLabel
}
