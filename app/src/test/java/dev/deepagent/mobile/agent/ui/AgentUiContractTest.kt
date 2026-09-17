package dev.deepagent.mobile.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AgentUiContractTest {

    @Test
    fun selectorsAreStableAndIndependentFromVisibleCopy() {
        assertEquals("agent.console", AgentUiContract.ROOT)
        assertEquals(
            AgentUiContract.workspaceEntry("src/Main.kt"),
            AgentUiContract.workspaceEntry("src/Main.kt"),
        )
        assertNotEquals(
            AgentUiContract.workspaceEntry("src/Main.kt"),
            AgentUiContract.workspaceEntry("README.md"),
        )
        assertEquals("agent.event.7", AgentUiContract.event(7L))
    }
}
