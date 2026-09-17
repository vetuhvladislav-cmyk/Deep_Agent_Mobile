package dev.deepagent.mobile.agent.preset

import android.content.Context
import android.util.Base64
import dev.deepagent.mobile.agent.model.ExecutionTarget
import dev.deepagent.mobile.agent.model.PermissionMode
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

data class AgentPreset(
    val name: String = "Deep Agent Mobile",
    val deepSeekBaseUrl: String = "https://api.deepseek.com",
    val model: String = "deepseek-flash",
    val repository: String = "",
    val workflow: String = "android.yml",
    val ref: String = "codex/p1-a-controlled-write-git-pr",
    val target: ExecutionTarget = ExecutionTarget.AUTO,
    val permission: PermissionMode = PermissionMode.READ_ONLY,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("name", name)
        .put("deep_seek_base_url", deepSeekBaseUrl)
        .put("model", model)
        .put("repository", repository)
        .put("workflow", workflow)
        .put("ref", ref)
        .put("target", target.name)
        .put("permission", permission.name)

    companion object {
        private const val FORMAT = "deep-agent-preset"
        private const val VERSION = 1

        fun fromJson(root: JSONObject): AgentPreset {
            require(root.optString("format") == FORMAT) {
                "Неподдерживаемый формат пресета"
            }
            require(root.optInt("version", 0) == VERSION) {
                "Неподдерживаемая версия пресета"
            }
            return AgentPreset(
                name = value(root, "name", "Deep Agent Mobile", 120),
                deepSeekBaseUrl = value(
                    root,
                    "deep_seek_base_url",
                    "https://api.deepseek.com",
                    512,
                ),
                model = value(root, "model", "deepseek-flash", 160),
                repository = value(root, "repository", "", 240),
                workflow = value(root, "workflow", "android.yml", 160),
                ref = value(root, "ref", "codex/p1-a-controlled-write-git-pr", 240),
                target = runCatching {
                    ExecutionTarget.valueOf(root.optString("target"))
                }.getOrDefault(ExecutionTarget.AUTO),
                permission = runCatching {
                    PermissionMode.valueOf(root.optString("permission"))
                }.getOrDefault(PermissionMode.READ_ONLY),
            )
        }

        private fun value(
            root: JSONObject,
            key: String,
            fallback: String,
            maxLength: Int,
        ): String {
            return root.optString(key, fallback)
                .trim()
                .take(maxLength)
        }
    }
}

data class AgentPresetPayload(
    val preset: AgentPreset,
    val deepSeekApiKey: String? = null,
    val githubToken: String? = null,
) {
    val credentialsRestored: Boolean
        get() = deepSeekApiKey != null || githubToken != null
}

/**
 * Stores connection presets without putting plaintext credentials into files.
 *
 * The credential fields use an AES-GCM key held by Android Keystore. A preset
 * imported on another device keeps its connection settings, but its keys must
 * be entered again because the original Keystore key is device-local.
 */
class AgentPresetStore(context: Context) {
    private val appContext = context.applicationContext
    private val localFile = File(appContext.filesDir, LOCAL_FILE_NAME)

    fun encode(
        preset: AgentPreset,
        deepSeekApiKey: String?,
        githubToken: String?,
    ): String {
        val root = preset.toJson()
        val credentials = JSONObject()
        normalizeSecret(deepSeekApiKey)?.let {
            credentials.put("deep_seek_api_key", encrypt(it))
        }
        normalizeSecret(githubToken)?.let {
            credentials.put("github_token", encrypt(it))
        }
        root.put("credentials", credentials)
        root.put("credentials_protected", credentials.length() > 0)
        return root.toString(2)
    }

    fun decode(serialized: String): AgentPresetPayload {
        require(serialized.length <= MAX_PRESET_CHARS) {
            "Файл пресета превышает допустимый размер"
        }
        val root = JSONObject(serialized)
        val preset = AgentPreset.fromJson(root)
        val credentials = root.optJSONObject("credentials")
        return AgentPresetPayload(
            preset = preset,
            deepSeekApiKey = decrypt(credentials?.optJSONObject("deep_seek_api_key")),
            githubToken = decrypt(credentials?.optJSONObject("github_token")),
        )
    }

    fun saveLocal(
        preset: AgentPreset,
        deepSeekApiKey: String?,
        githubToken: String?,
    ) {
        val payload = encode(preset, deepSeekApiKey, githubToken)
        val temporary = File(
            localFile.parentFile,
            "." + localFile.name + "." + UUID.randomUUID() + ".tmp",
        )
        check(temporary.parentFile?.let { it.mkdirs() || it.isDirectory } == true) {
            "Не удалось создать каталог локальных пресетов"
        }
        try {
            temporary.writeText(payload, StandardCharsets.UTF_8)
            if (!temporary.renameTo(localFile)) {
                localFile.delete()
                check(temporary.renameTo(localFile)) {
                    "Не удалось сохранить локальный пресет"
                }
            }
        } finally {
            temporary.delete()
        }
    }

    fun loadLocal(): AgentPresetPayload? {
        if (!localFile.isFile) return null
        return runCatching {
            decode(localFile.readText(StandardCharsets.UTF_8))
        }.getOrNull()
    }

    private fun encrypt(value: String): JSONObject {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return JSONObject()
            .put("algorithm", TRANSFORMATION)
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
    }

    private fun decrypt(value: JSONObject?): String? {
        if (value == null) return null
        return runCatching {
            val iv = Base64.decode(value.optString("iv"), Base64.DEFAULT)
            val ciphertext = Base64.decode(
                value.optString("ciphertext"),
                Base64.DEFAULT,
            )
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
                .takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
        }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun normalizeSecret(value: String?): String? {
        return value
            ?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= MAX_SECRET_CHARS }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "deep-agent-preset-aes-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val LOCAL_FILE_NAME = "agent-preset.json"
        const val MAX_PRESET_CHARS = 64 * 1024
        const val MAX_SECRET_CHARS = 4 * 1024
    }
}
