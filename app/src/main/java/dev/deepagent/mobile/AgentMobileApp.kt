package dev.deepagent.mobile

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.deepagent.mobile.agent.core.AgentCore
import dev.deepagent.mobile.agent.protocol.AgentBridge
import dev.deepagent.mobile.agent.ui.AgentConsoleScreen

/**
 * Process-scoped owner for one Agent Core session.
 *
 * Activity recreation must reconnect to this bridge instead of constructing a
 * second core or closing the active session during a configuration change.
 */
class DeepAgentApplication : Application() {
    lateinit var agentBridge: AgentBridge
        private set

    override fun onCreate() {
        super.onCreate()
        agentBridge = AgentCore(this)
    }
}

@Composable
fun AgentMobileApp() {
    val application = LocalContext.current.applicationContext
        as? DeepAgentApplication
        ?: error("DeepAgentApplication не зарегистрирован")
    AgentConsoleScreen(
        agent = application.agentBridge,
        onBack = {},
    )
}
