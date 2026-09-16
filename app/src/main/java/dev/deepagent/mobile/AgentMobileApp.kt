package dev.deepagent.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.deepagent.mobile.agent.core.AgentCore
import dev.deepagent.mobile.agent.protocol.AgentBridge
import dev.deepagent.mobile.agent.ui.AgentConsoleScreen

@Composable
fun AgentMobileApp() {
    val context = LocalContext.current
    val bridge: AgentBridge = remember { AgentCore(context) }
    AgentConsoleScreen(
        agent = bridge,
        onBack = {},
    )
}
