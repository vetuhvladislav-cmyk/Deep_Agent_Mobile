package dev.deepagent.mobile.agent.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionModeTest {

    @Test
    fun localAndGithubCapabilitiesAreIndependent() {
        assertTrue(PermissionMode.LOCAL_WRITE.allows(PermissionMode.LOCAL_WRITE))
        assertFalse(PermissionMode.LOCAL_WRITE.allows(PermissionMode.GITHUB_WRITE))
        assertFalse(PermissionMode.GITHUB_WRITE.allows(PermissionMode.LOCAL_WRITE))
        assertTrue(PermissionMode.PR_CREATE.allows(PermissionMode.GITHUB_WRITE))
        assertTrue(PermissionMode.MERGE_RELEASE.allows(PermissionMode.LOCAL_WRITE))
        assertTrue(PermissionMode.MERGE_RELEASE.allows(PermissionMode.PR_CREATE))
    }
}
