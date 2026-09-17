package dev.deepagent.mobile.agent.artifact

import dev.deepagent.mobile.agent.github.VerifiedArtifact
import dev.deepagent.mobile.agent.model.ActionsArtifactSaveResult
import dev.deepagent.mobile.agent.model.ActionsArtifactSaveStatus
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

class ArtifactManager {

    fun save(
        workspaceRoot: File,
        artifact: VerifiedArtifact,
        requestedName: String? = null,
    ): ActionsArtifactSaveResult {
        return try {
            val root = workspaceRoot.canonicalFile
            require(root.isDirectory) { "Workspace root недоступен" }
            val fileName = requestedName?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: artifact.fileName
            require(FILE_NAME_PATTERN.matches(fileName)) {
                "Имя APK/AAB недопустимо"
            }
            require(
                fileName.substringAfterLast('.')
                    .equals(
                        artifact.fileName.substringAfterLast('.'),
                        ignoreCase = true,
                    ),
            ) {
                "Расширение artifact нельзя изменить"
            }
            val directory = File(root, ".deep-agent-artifacts").canonicalFile
            require(directory.path == root.path ||
                directory.path.startsWith(root.path + File.separator))
            check(directory.mkdirs() || directory.isDirectory) {
                "Не удалось создать каталог artifacts"
            }
            val target = File(directory, fileName).canonicalFile
            require(target.parentFile?.canonicalPath == directory.path) {
                "Путь artifact выходит за границы workspace"
            }

            if (target.exists()) {
                val existingChecksum = sha256(target)
                return if (existingChecksum.equals(artifact.checksum, ignoreCase = true)) {
                    ActionsArtifactSaveResult(
                        artifactId = artifact.artifactId,
                        fileName = fileName,
                        relativePath = ".deep-agent-artifacts/" + fileName,
                        status = ActionsArtifactSaveStatus.VERIFIED_SAVED,
                        summary = "Artifact уже сохранён и checksum совпадает",
                        sourceSha = artifact.sourceSha,
                        checksum = existingChecksum,
                    )
                } else {
                    ActionsArtifactSaveResult(
                        artifactId = artifact.artifactId,
                        fileName = fileName,
                        status = ActionsArtifactSaveStatus.FAILED,
                        summary = "Файл artifact уже существует с другим checksum",
                        sourceSha = artifact.sourceSha,
                        checksum = artifact.checksum,
                        errorCode = "ARTIFACT_TARGET_EXISTS",
                    )
                }
            }

            val temporary = File(
                directory,
                "." + fileName + "." + UUID.randomUUID() + ".tmp",
            )
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(artifact.bytes)
                    output.flush()
                    output.fd.sync()
                }
                moveAtomically(temporary, target)
            } finally {
                temporary.delete()
            }

            val savedChecksum = sha256(target)
            if (
                target.length() != artifact.bytes.size.toLong() ||
                !savedChecksum.equals(artifact.checksum, ignoreCase = true)
            ) {
                return ActionsArtifactSaveResult(
                    artifactId = artifact.artifactId,
                    fileName = fileName,
                    relativePath = ".deep-agent-artifacts/" + fileName,
                    status = ActionsArtifactSaveStatus.UNKNOWN,
                    summary = "Artifact записан, но итоговая проверка не совпала",
                    sourceSha = artifact.sourceSha,
                    checksum = savedChecksum,
                    errorCode = "ARTIFACT_SAVE_UNCONFIRMED",
                )
            }

            ActionsArtifactSaveResult(
                artifactId = artifact.artifactId,
                fileName = fileName,
                relativePath = ".deep-agent-artifacts/" + fileName,
                status = ActionsArtifactSaveStatus.VERIFIED_SAVED,
                summary = "Проверенный artifact сохранён",
                sourceSha = artifact.sourceSha,
                checksum = savedChecksum,
            )
        } catch (error: Exception) {
            ActionsArtifactSaveResult(
                artifactId = artifact.artifactId,
                fileName = artifact.fileName,
                status = ActionsArtifactSaveStatus.FAILED,
                summary = error.message ?: "Не удалось сохранить artifact",
                sourceSha = artifact.sourceSha,
                checksum = artifact.checksum,
                errorCode = "ARTIFACT_SAVE_FAILED",
            )
        }
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            Files.move(source.toPath(), target.toPath())
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private companion object {
        val FILE_NAME_PATTERN =
            Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.(?i:apk|aab)")
    }
}
