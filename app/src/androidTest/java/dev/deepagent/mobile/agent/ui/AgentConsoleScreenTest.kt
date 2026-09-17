package dev.deepagent.mobile.agent.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.deepagent.mobile.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentConsoleScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun consoleRootIsDisplayedWithStableContractTag() {
        composeRule
            .onNodeWithTag(AgentUiContract.ROOT)
            .assertIsDisplayed()
    }
}
