package dev.deepagent.mobile.agent.github

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactZipVerifierTest {

    @Test
    fun verifiesSingleApkChecksumAndProvenance() {
        val payload = "fake-apk-payload".toByteArray()
        val payloadSha = sha256(payload)
        val sourceSha = "a".repeat(40)
        val archive = zipOf(
            "build/deep-agent-mobile.apk" to payload,
            "build/deep-agent-mobile.apk.sha256" to
                "$payloadSha  *build/deep-agent-mobile.apk\n".toByteArray(),
            "build/deep-agent-provenance.json" to
                """{"source_sha":"$sourceSha"}""".toByteArray(),
        )

        val result = GitHubActionsClient().extractPayload(
            archive = archive,
            artifactId = 17L,
            sourceSha = sourceSha,
        )

        assertEquals(17L, result.artifactId)
        assertEquals("deep-agent-mobile.apk", result.fileName)
        assertEquals(sourceSha, result.sourceSha)
        assertEquals(payloadSha, result.checksum)
        assertArrayEquals(payload, result.bytes)
    }

    @Test
    fun rejectsUnsafeZipPathAndChecksumMismatch() {
        val payload = "fake-apk-payload".toByteArray()
        val sourceSha = "b".repeat(40)
        val unsafeArchive = zipOf(
            "../deep-agent-mobile.apk" to payload,
        )
        val unsafeError = failureOf {
            GitHubActionsClient().extractPayload(
                archive = unsafeArchive,
                artifactId = 1L,
                sourceSha = sourceSha,
            )
        }
        assertTrue(unsafeError.contains("небезопасный путь"))

        val mismatchedArchive = zipOf(
            "deep-agent-mobile.apk" to payload,
            "deep-agent-mobile.apk.sha256" to
                "0000000000000000000000000000000000000000000000000000000000000000  *deep-agent-mobile.apk\n".toByteArray(),
            "deep-agent-provenance.json" to
                """{"source_sha":"$sourceSha"}""".toByteArray(),
        )
        val checksumError = failureOf {
            GitHubActionsClient().extractPayload(
                archive = mismatchedArchive,
                artifactId = 2L,
                sourceSha = sourceSha,
            )
        }
        assertTrue(checksumError.contains("Checksum APK/AAB не совпал"))
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun failureOf(block: () -> Unit): String {
        return try {
            block()
            error("Ожидалась ошибка verifier")
        } catch (error: Exception) {
            error.message.orEmpty()
        }
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
