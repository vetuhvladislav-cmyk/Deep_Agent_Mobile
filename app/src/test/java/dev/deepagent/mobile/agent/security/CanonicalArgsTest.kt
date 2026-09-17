package dev.deepagent.mobile.agent.security

import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CanonicalArgsTest {

    @Test
    fun matchesGoldenVectors() {
        val root = JSONObject(resourceText())
        assertEquals(CanonicalArgs.VERSION, root.getInt("version"))
        val vectors = root.getJSONArray("vectors")
        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            val canonical = CanonicalArgs.canonicalize(vector.getString("input"))
            assertEquals(
                vector.getString("canonical_bytes_hex"),
                CanonicalArgs.hex(canonical),
            )
            assertEquals(
                vector.getString("sha256"),
                CanonicalArgs.sha256(vector.getString("input")),
            )
        }
    }

    @Test
    fun rejectsDuplicateKeysAndInvalidNumbers() {
        val cases = JSONObject(resourceText()).getJSONArray("invalid_cases")
        for (index in 0 until cases.length()) {
            val item = cases.getJSONObject(index)
            try {
                CanonicalArgs.canonicalize(item.getString("raw"))
                fail("Expected rejection for " + item.getString("name"))
            } catch (error: CanonicalArgsException) {
                assertEquals(item.getString("expected_code"), error.code)
            }
        }
    }

    @Test
    fun canonicalizationIsIndependentOfObjectOrderAndWhitespace() {
        assertEquals(
            CanonicalArgs.sha256("""{"b":2, "a":1}"""),
            CanonicalArgs.sha256(""" { "a" : 1, "b" : 2 } """),
        )
    }

    private fun resourceText(): String {
        return javaClass.classLoader
            ?.getResourceAsStream("canonical_args_v1_vectors.json")
            ?.bufferedReader(StandardCharsets.UTF_8)
            ?.use { it.readText() }
            ?: error("canonical_args_v1_vectors.json missing")
    }
}
