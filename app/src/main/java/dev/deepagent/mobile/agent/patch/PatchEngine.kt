package dev.deepagent.mobile.agent.patch

import dev.deepagent.mobile.agent.model.AgentRedactor
import dev.deepagent.mobile.agent.model.PatchRollbackResult
import dev.deepagent.mobile.agent.model.PatchRollbackStatus
import dev.deepagent.mobile.agent.workspace.WorkspaceIdentity
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

data class PatchPreview(
    val path: String,
    val workspaceFingerprint: String,
    val oldSha256: String?,
    val newSha256: String,
    val unifiedDiff: String,
    val before: String,
    val after: String,
    val createsFile: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("path", path)
        .put("workspace_fingerprint", workspaceFingerprint)
        .put("old_sha256", oldSha256)
        .put("new_sha256", newSha256)
        .put("unified_diff", unifiedDiff)
        .put("creates_file", createsFile)
}


enum class PatchCheckpointStatus {
    PREPARED,
    APPLIED,
    UNKNOWN,
    ROLLING_BACK,
    ROLLED_BACK,
    ROLLED_BACK_UNVERIFIED,
    FAILED,
}

data class PatchCheckpoint(
    val operationId: String,
    val path: String,
    val oldSha256: String?,
    val newSha256: String,
    val checkpointFileName: String?,
    val workspaceFingerprintBefore: String,
    val workspaceFingerprintAfter: String?,
    val workspaceFingerprintRolledBack: String?,
    val status: PatchCheckpointStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val errorCode: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("operation_id", operationId)
        .put("path", path)
        .put("old_sha256", oldSha256)
        .put("new_sha256", newSha256)
        .put("checkpoint_file", checkpointFileName)
        .put("workspace_fingerprint_before", workspaceFingerprintBefore)
        .put("workspace_fingerprint_after", workspaceFingerprintAfter)
        .put("workspace_fingerprint_rolled_back", workspaceFingerprintRolledBack)
        .put("status", status.name)
        .put("created_at", createdAt)
        .put("updated_at", updatedAt)
        .put("error_code", errorCode)

    companion object {
        private val OPERATION_ID_PATTERN = Regex("[A-Fa-f0-9-]{36}")
        private val SHA256_PATTERN = Regex("[A-Fa-f0-9]{64}")

        fun fromJson(value: JSONObject): PatchCheckpoint? {
            val operationId = value.optString("operation_id").trim()
            val path = value.optString("path").trim()
            val oldSha = value.optString("old_sha256")
                .trim()
                .takeIf { it.isNotBlank() && it != "null" }
            val newSha = value.optString("new_sha256").trim()
            val beforeFingerprint = value.optString("workspace_fingerprint_before")
                .trim()
            val afterFingerprint = value.optString("workspace_fingerprint_after")
                .trim()
                .takeIf { it.isNotBlank() && it != "null" }
            val rolledBackFingerprint = value.optString("workspace_fingerprint_rolled_back")
                .trim()
                .takeIf { it.isNotBlank() && it != "null" }
            val checkpointFile = value.optString("checkpoint_file")
                .trim()
                .takeIf { it.isNotBlank() && it != "null" }
            if (
                !OPERATION_ID_PATTERN.matches(operationId) ||
                path.isBlank() ||
                path.length > 512 ||
                path.contains('\u0000') ||
                !SHA256_PATTERN.matches(newSha) ||
                (oldSha != null && !SHA256_PATTERN.matches(oldSha)) ||
                !SHA256_PATTERN.matches(beforeFingerprint) ||
                (afterFingerprint != null && !SHA256_PATTERN.matches(afterFingerprint)) ||
                (rolledBackFingerprint != null &&
                    !SHA256_PATTERN.matches(rolledBackFingerprint)) ||
                (checkpointFile != null &&
                    (checkpointFile.length > 512 ||
                        checkpointFile.contains('/') ||
                        checkpointFile.contains('\\') ||
                        checkpointFile.contains('\u0000')))
            ) {
                return null
            }
            val status = runCatching {
                PatchCheckpointStatus.valueOf(value.optString("status"))
            }.getOrDefault(PatchCheckpointStatus.UNKNOWN)
            return PatchCheckpoint(
                operationId = operationId,
                path = path,
                oldSha256 = oldSha,
                newSha256 = newSha,
                checkpointFileName = checkpointFile,
                workspaceFingerprintBefore = beforeFingerprint,
                workspaceFingerprintAfter = afterFingerprint,
                workspaceFingerprintRolledBack = rolledBackFingerprint,
                status = status,
                createdAt = value.optLong("created_at", 0L),
                updatedAt = value.optLong("updated_at", 0L),
                errorCode = value.optString("error_code")
                    .trim()
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?.take(160),
            )
        }
    }
}

data class PatchApplyResult(
    val preview: PatchPreview,
    val operationId: String,
    val checkpointFileName: String?,
    val checkpointPath: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("operation_id", operationId)
        .put("path", preview.path)
        .put("old_sha256", preview.oldSha256)
        .put("new_sha256", preview.newSha256)
        .put("checkpoint", checkpointFileName)
        .put("workspace_fingerprint", preview.workspaceFingerprint)
        .put("unified_diff", preview.unifiedDiff)
}

/**
 * Parses and applies bounded text patches inside one workspace.
 *
 * Preview is side-effect free. apply() requires a checkpoint directory supplied
 * by the host and rechecks the old hash immediately before the atomic move.
 */
class PatchEngine(
    private val checkpointDirectory: File,
) {

    fun preview(
        workspaceRoot: File,
        arguments: JSONObject,
        workspaceFingerprint: String? = null,
    ): PatchPreview {
        val actualWorkspaceFingerprint = WorkspaceIdentity.capture(
            workspaceId = "patch",
            root = workspaceRoot,
        ).treeSha256
        require(
            workspaceFingerprint == null || workspaceFingerprint == actualWorkspaceFingerprint,
        ) {
            "Workspace fingerprint изменился; повторите preview"
        }
        val path = arguments.optString("path").trim()
        require(path.isNotBlank()) { "Для apply_patch нужен path" }
        val target = resolvePath(workspaceRoot, path)
        require(!isSensitiveFile(target)) {
            "Запись чувствительного файла запрещена политикой redaction"
        }

        val existing = target.isFile
        if (target.exists() && !existing) {
            error("Целевой путь не является обычным файлом")
        }
        if (!target.exists()) {
            require(target.parentFile?.isDirectory == true) {
                "Для нового patch-файла parent directory должна существовать"
            }
        }
        val beforeBytes = if (existing) {
            target.readBytes().also {
                require(it.size <= MAX_FILE_BYTES) {
                    "Файл превышает лимит apply_patch"
                }
            }
        } else {
            ByteArray(0)
        }
        require(!beforeBytes.contains(0.toByte())) {
            "Бинарный файл не принимается apply_patch"
        }

        val oldSha = if (existing) sha256(beforeBytes) else null
        val expectedSha = arguments.optString("expected_sha256").trim()
        if (existing) {
            require(expectedSha.isNotBlank()) {
                "Для существующего файла нужен expected_sha256"
            }
            require(expectedSha.equals(oldSha, ignoreCase = true)) {
                "Base content hash mismatch для " + path
            }
        } else {
            require(expectedSha.isBlank() || expectedSha == "0") {
                "Для нового файла expected_sha256 должен быть пустым"
            }
            require(arguments.optBoolean("create", false)) {
                "Для создания файла нужен create=true"
            }
        }

        val before = beforeBytes.toString(Charsets.UTF_8)
        val replacement = arguments.optString("replacement")
            .takeIf { arguments.has("replacement") }
        val patchText = arguments.optString("patch")
        require((replacement != null) xor patchText.isNotBlank()) {
            "Нужно указать ровно один из replacement или patch"
        }
        require((replacement ?: patchText).length <= MAX_PATCH_CHARS) {
            "Patch превышает лимит размера"
        }

        val after = replacement ?: applyUnifiedPatch(before, patchText)
        require(after.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) {
            "Результат patch превышает лимит размера"
        }
        val newSha = sha256(after.toByteArray(Charsets.UTF_8))
        require(existing || !target.exists()) {
            "Целевой файл появился во время preview"
        }

        return PatchPreview(
            path = path,
            workspaceFingerprint = actualWorkspaceFingerprint,
            oldSha256 = oldSha,
            newSha256 = newSha,
            unifiedDiff = buildUnifiedDiff(path, before, after),
            before = before,
            after = after,
            createsFile = !existing,
        )
    }

    fun apply(
        workspaceRoot: File,
        arguments: JSONObject,
        expectedWorkspaceFingerprint: String? = null,
    ): PatchApplyResult {
        if (expectedWorkspaceFingerprint != null) {
            val actualWorkspaceFingerprint = WorkspaceIdentity.capture(
                workspaceId = "patch",
                root = workspaceRoot,
            ).treeSha256
            require(actualWorkspaceFingerprint == expectedWorkspaceFingerprint) {
                "Workspace fingerprint изменился после preview; повторите анализ"
            }
        }
        val preview = preview(
            workspaceRoot = workspaceRoot,
            arguments = arguments,
            workspaceFingerprint = expectedWorkspaceFingerprint,
        )
        val target = resolvePath(workspaceRoot, preview.path)
        val currentBytes = if (target.isFile) {
            require(target.length() <= MAX_FILE_BYTES) {
                "Файл превышает лимит apply_patch"
            }
            target.readBytes()
        } else {
            ByteArray(0)
        }
        val currentSha = if (target.isFile) sha256(currentBytes) else null
        require(currentSha == preview.oldSha256) {
            "Workspace изменился после preview; повторите анализ"
        }

        check(checkpointDirectory.mkdirs() || checkpointDirectory.isDirectory) {
            "Не удалось создать checkpoint directory"
        }
        val operationId = UUID.randomUUID().toString()
        val checkpoint = if (target.isFile) {
            File(checkpointDirectory, operationId + "-" + target.name)
                .also { target.copyTo(it, overwrite = false) }
        } else {
            null
        }
        val now = System.currentTimeMillis()
        writeCheckpoint(
            PatchCheckpoint(
                operationId = operationId,
                path = preview.path,
                oldSha256 = preview.oldSha256,
                newSha256 = preview.newSha256,
                checkpointFileName = checkpoint?.name,
                workspaceFingerprintBefore = preview.workspaceFingerprint,
                workspaceFingerprintAfter = null,
                workspaceFingerprintRolledBack = null,
                status = PatchCheckpointStatus.PREPARED,
                createdAt = now,
                updatedAt = now,
            ),
        )

        val parent = target.parentFile ?: error("У patch target нет parent directory")
        check(parent.mkdirs() || parent.isDirectory) {
            "Не удалось создать parent directory patch target"
        }
        val temporary = File(
            parent,
            "." + target.name + "." + operationId + ".tmp",
        )
        try {
            temporary.writeText(preview.after, Charsets.UTF_8)
            moveAtomically(temporary, target)
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }

        return PatchApplyResult(
            preview = preview,
            operationId = operationId,
            checkpointFileName = checkpoint?.name,
            checkpointPath = checkpoint?.canonicalPath,
        )
    }


    fun markCheckpointApplied(
        operationId: String,
        workspaceFingerprintAfter: String,
    ): PatchCheckpoint {
        require(SHA256_PATTERN.matches(workspaceFingerprintAfter)) {
            "Некорректный post-write workspace fingerprint"
        }
        val current = readCheckpoint(operationId)
            ?: error("Checkpoint не найден")
        if (current.status == PatchCheckpointStatus.APPLIED) {
            require(current.workspaceFingerprintAfter == workspaceFingerprintAfter) {
                "Checkpoint уже привязан к другому workspace fingerprint"
            }
            return current
        }
        require(current.status == PatchCheckpointStatus.PREPARED) {
            "Checkpoint нельзя подтвердить из состояния " + current.status.name
        }
        val updated = current.copy(
            workspaceFingerprintAfter = workspaceFingerprintAfter,
            status = PatchCheckpointStatus.APPLIED,
            updatedAt = System.currentTimeMillis(),
            errorCode = null,
        )
        writeCheckpoint(updated)
        return updated
    }

    fun markCheckpointUnknown(
        operationId: String,
        errorCode: String,
    ): PatchCheckpoint? {
        val current = readCheckpoint(operationId) ?: return null
        if (current.status == PatchCheckpointStatus.ROLLED_BACK) return current
        val updated = current.copy(
            status = PatchCheckpointStatus.UNKNOWN,
            updatedAt = System.currentTimeMillis(),
            errorCode = errorCode.take(160),
        )
        writeCheckpoint(updated)
        return updated
    }

    fun rollback(
        workspaceRoot: File,
        operationId: String,
        expectedWorkspaceFingerprint: String,
    ): PatchRollbackResult {
        val checkpoint = readCheckpoint(operationId)
            ?: return PatchRollbackResult(
                operationId = operationId,
                status = PatchRollbackStatus.FAILED,
                summary = "Checkpoint не найден",
                errorCode = "PATCH_CHECKPOINT_NOT_FOUND",
            )
        when (checkpoint.status) {
            PatchCheckpointStatus.ROLLED_BACK -> {
                return PatchRollbackResult(
                    operationId = operationId,
                    path = checkpoint.path,
                    status = PatchRollbackStatus.SUCCEEDED,
                    summary = "Patch уже был откатан",
                    workspaceFingerprintBefore = checkpoint.workspaceFingerprintAfter,
                    workspaceFingerprintAfter = checkpoint.workspaceFingerprintRolledBack,
                )
            }

            PatchCheckpointStatus.PREPARED,
            PatchCheckpointStatus.UNKNOWN,
            PatchCheckpointStatus.ROLLING_BACK,
            PatchCheckpointStatus.ROLLED_BACK_UNVERIFIED,
            -> {
                return PatchRollbackResult(
                    operationId = operationId,
                    path = checkpoint.path,
                    status = PatchRollbackStatus.UNKNOWN,
                    summary = "Checkpoint не подтверждён; сначала выполните re-check",
                    workspaceFingerprintBefore = checkpoint.workspaceFingerprintAfter,
                    errorCode = "PATCH_ROLLBACK_RECHECK_REQUIRED",
                )
            }

            PatchCheckpointStatus.FAILED -> {
                return PatchRollbackResult(
                    operationId = operationId,
                    path = checkpoint.path,
                    status = PatchRollbackStatus.FAILED,
                    summary = "Checkpoint помечен как неуспешный",
                    errorCode = checkpoint.errorCode ?: "PATCH_CHECKPOINT_FAILED",
                )
            }

            PatchCheckpointStatus.APPLIED -> Unit
        }

        val expectedAfter = checkpoint.workspaceFingerprintAfter
        if (
            expectedAfter.isNullOrBlank() ||
            expectedAfter != expectedWorkspaceFingerprint
        ) {
            return PatchRollbackResult(
                operationId = operationId,
                path = checkpoint.path,
                status = PatchRollbackStatus.UNKNOWN,
                summary = "Fingerprint patch не совпал; rollback остановлен",
                workspaceFingerprintBefore = expectedAfter,
                errorCode = "PATCH_ROLLBACK_RECHECK_REQUIRED",
            )
        }

        val identityBefore = runCatching {
            WorkspaceIdentity.capture("patch", workspaceRoot)
        }.getOrElse {
            return PatchRollbackResult(
                operationId = operationId,
                path = checkpoint.path,
                status = PatchRollbackStatus.UNKNOWN,
                summary = "Workspace identity недоступна; rollback не подтверждён",
                workspaceFingerprintBefore = expectedAfter,
                errorCode = "PATCH_ROLLBACK_RECHECK_REQUIRED",
            )
        }
        if (identityBefore.treeSha256 != expectedAfter) {
            return PatchRollbackResult(
                operationId = operationId,
                path = checkpoint.path,
                status = PatchRollbackStatus.UNKNOWN,
                summary = "Workspace изменился перед rollback; нужен новый re-check",
                workspaceFingerprintBefore = identityBefore.treeSha256,
                errorCode = "PATCH_ROLLBACK_RECHECK_REQUIRED",
            )
        }

        val target = runCatching {
            resolvePath(workspaceRoot, checkpoint.path)
        }.getOrElse {
            return PatchRollbackResult(
                operationId = operationId,
                path = checkpoint.path,
                status = PatchRollbackStatus.FAILED,
                workspaceFingerprintBefore = identityBefore.treeSha256,
                summary = AgentRedactor.text(
                    it.message ?: "Patch path недоступен",
                    MAX_ERROR_CHARS,
                ).orEmpty(),
                errorCode = "PATCH_ROLLBACK_INVALID_PATH",
            )
        }
        if (!target.isFile) {
            return PatchRollbackResult(
                operationId = operationId,
                path = checkpoint.path,
                status = PatchRollbackStatus.UNKNOWN,
                workspaceFingerprintBefore = identityBefore.treeSha256,
                summary = "Целевой файл изменён или удалён; rollback остановлен",
                errorCode = "PATCH_ROLLBACK_RECHECK_REQUIRED",
            )
        }
        if (isSensitiveFile(target)) {
            return PatchRollbackResult(
                operationId = operationId,
                path = checkpoint.path,
                status = PatchRollbackStatus.FAILED,
                workspaceFingerprintBefore = identityBefore.treeSha256,
                summary = "Rollback чувствительного файла запрещён политикой",
                errorCode = "PATCH_ROLLBACK_SENSITIVE_FILE",
            )
        }
        val currentBytes = runCatching {
            target.readBytes().also {
                require(it.size <= MAX_FILE_BYTES) {
                    "Целевой файл превышает лимит rollback"
                }
            }
        }.getOrElse {
            return PatchRollbackResult(
                operationId = operationId,
                path = checkpoint.path,
                status = PatchRollbackStatus.UNKNOWN,
                workspaceFingerprintBefore = identityBefore.treeSha256,
                summary = "Не удалось прочитать целевой файл; rollback не подтверждён",
                errorCode = "PATCH_ROLLBACK_RECHECK_REQUIRED",
            )
        }
        if (sha256(currentBytes) != checkpoint.newSha256) {
            return PatchRollbackResult(
                operationId = operationId,
                path = checkpoint.path,
                status = PatchRollbackStatus.UNKNOWN,
                workspaceFingerprintBefore = identityBefore.treeSha256,
                summary = "Целевой файл изменён после patch; rollback остановлен",
                errorCode = "PATCH_ROLLBACK_RECHECK_REQUIRED",
            )
        }

        val rollingBack = checkpoint.copy(
            status = PatchCheckpointStatus.ROLLING_BACK,
            updatedAt = System.currentTimeMillis(),
            errorCode = null,
        )
        runCatching { writeCheckpoint(rollingBack) }.getOrElse {
            return PatchRollbackResult(
                operationId = operationId,
                path = checkpoint.path,
                status = PatchRollbackStatus.UNKNOWN,
                workspaceFingerprintBefore = identityBefore.treeSha256,
                summary = "Не удалось зафиксировать начало rollback",
                errorCode = "PATCH_ROLLBACK_UNKNOWN",
            )
        }

        return try {
            if (checkpoint.oldSha256 == null) {
                check(target.delete()) {
                    "Не удалось удалить созданный patch-файл"
                }
            } else {
                val checkpointName = checkpoint.checkpointFileName
                    ?: error("Checkpoint content не найден")
                val backup = resolveCheckpointFile(checkpointName)
                check(backup.isFile) {
                    "Checkpoint content недоступен"
                }
                val oldBytes = backup.readBytes()
                require(oldBytes.size <= MAX_FILE_BYTES) {
                    "Checkpoint content превышает лимит"
                }
                require(sha256(oldBytes) == checkpoint.oldSha256) {
                    "Checkpoint content hash mismatch"
                }
                val parent = target.parentFile ?: error("У target нет parent directory")
                val temporary = File(
                    parent,
                    "." + target.name + "." + operationId + ".rollback.tmp",
                )
                try {
                    temporary.writeBytes(oldBytes)
                    moveAtomically(temporary, target)
                } finally {
                    temporary.delete()
                }
            }

            val restored = if (checkpoint.oldSha256 == null) {
                !target.exists()
            } else {
                target.isFile &&
                    sha256(target.readBytes()) == checkpoint.oldSha256
            }
            check(restored) {
                "Восстановленное содержимое не прошло hash check"
            }
            val afterIdentity = WorkspaceIdentity.capture("patch", workspaceRoot)
            val finalCheckpoint = rollingBack.copy(
                status = PatchCheckpointStatus.ROLLED_BACK,
                workspaceFingerprintRolledBack = afterIdentity.treeSha256,
                updatedAt = System.currentTimeMillis(),
                errorCode = null,
            )
            writeCheckpoint(finalCheckpoint)
            PatchRollbackResult(
                operationId = operationId,
                path = checkpoint.path,
                status = PatchRollbackStatus.SUCCEEDED,
                summary = "Patch откатан после повторной проверки fingerprint",
                workspaceFingerprintBefore = identityBefore.treeSha256,
                workspaceFingerprintAfter = afterIdentity.treeSha256,
            )
        } catch (error: Throwable) {
            runCatching {
                writeCheckpoint(
                    rollingBack.copy(
                        status = PatchCheckpointStatus.ROLLED_BACK_UNVERIFIED,
                        updatedAt = System.currentTimeMillis(),
                        errorCode = "PATCH_ROLLBACK_UNKNOWN",
                    ),
                )
            }
            PatchRollbackResult(
                operationId = operationId,
                path = checkpoint.path,
                status = PatchRollbackStatus.UNKNOWN,
                summary = AgentRedactor.text(
                    error.message ?: "Rollback завершился без подтверждения",
                    MAX_ERROR_CHARS,
                ).orEmpty(),
                workspaceFingerprintBefore = identityBefore.treeSha256,
                errorCode = "PATCH_ROLLBACK_UNKNOWN",
            )
        }
    }

    private fun moveAtomically(source: File, target: File) {
        runCatching {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.recoverCatching {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse { throw it }
    }


    private fun readCheckpoint(operationId: String): PatchCheckpoint? {
        val manifest = runCatching { manifestFile(operationId) }.getOrNull()
            ?: return null
        if (
            !manifest.isFile ||
            manifest.length() <= 0L ||
            manifest.length() > MAX_CHECKPOINT_BYTES
        ) {
            return null
        }
        return runCatching {
            PatchCheckpoint.fromJson(
                JSONObject(manifest.readText(Charsets.UTF_8)),
            )
        }.getOrNull()
    }

    private fun writeCheckpoint(checkpoint: PatchCheckpoint) {
        check(checkpointDirectory.mkdirs() || checkpointDirectory.isDirectory) {
            "Не удалось создать checkpoint directory"
        }
        val target = manifestFile(checkpoint.operationId)
        val temporary = File(
            checkpointDirectory,
            "." + checkpoint.operationId + "." + UUID.randomUUID() + ".tmp",
        )
        try {
            temporary.writeText(checkpoint.toJson().toString(), Charsets.UTF_8)
            moveAtomically(temporary, target)
        } finally {
            temporary.delete()
        }
    }

    private fun manifestFile(operationId: String): File {
        require(OPERATION_ID_PATTERN.matches(operationId)) {
            "Некорректный checkpoint operation id"
        }
        val directory = checkpointDirectory.canonicalFile
        val target = File(directory, operationId + ".json").canonicalFile
        require(target.parentFile?.canonicalFile == directory) {
            "Checkpoint manifest выходит за границы каталога"
        }
        return target
    }

    private fun resolveCheckpointFile(fileName: String): File {
        require(
            fileName.isNotBlank() &&
                !fileName.contains('/') &&
                !fileName.contains('\\') &&
                !fileName.contains('\u0000'),
        ) {
            "Некорректный checkpoint filename"
        }
        val directory = checkpointDirectory.canonicalFile
        val target = File(directory, fileName).canonicalFile
        require(target.parentFile?.canonicalFile == directory) {
            "Checkpoint content выходит за границы каталога"
        }
        return target
    }

    private fun applyUnifiedPatch(
        original: String,
        patch: String,
    ): String {
        val originalNormalized = normalizeLineEndings(original)
        val hadTrailingNewline = originalNormalized.endsWith("\n")
        val originalBody = if (hadTrailingNewline) {
            originalNormalized.dropLast(1)
        } else {
            originalNormalized
        }
        val originalLines = if (originalBody.isEmpty()) {
            mutableListOf()
        } else {
            originalBody.split('\n').toMutableList()
        }

        val patchLines = normalizeLineEndings(patch)
            .split('\n')
        val hunks = parseHunks(patchLines)
        require(hunks.isNotEmpty()) {
            "Unified patch не содержит hunks"
        }

        val output = mutableListOf<String>()
        var cursor = 0
        for (hunk in hunks) {
            var index = if (hunk.oldStart == 0) 0 else hunk.oldStart - 1
            if (index < cursor || index > originalLines.size) {
                index = findHunkStart(originalLines, cursor, hunk.oldLines)
            }
            require(index >= 0) {
                "Patch conflict: контекст не совпал для " + hunk.header
            }

            output += originalLines.subList(cursor, index)
            var sourceIndex = index
            hunk.lines.forEach { line ->
                when (line.firstOrNull()) {
                    ' ' -> {
                        require(sourceIndex < originalLines.size &&
                            originalLines[sourceIndex] == line.drop(1)
                        ) {
                            "Patch conflict в контекстной строке"
                        }
                        output += originalLines[sourceIndex]
                        sourceIndex += 1
                    }

                    '-' -> {
                        require(sourceIndex < originalLines.size &&
                            originalLines[sourceIndex] == line.drop(1)
                        ) {
                            "Patch conflict в удаляемой строке"
                        }
                        sourceIndex += 1
                    }

                    '+' -> output += line.drop(1)
                    '\\' -> Unit
                    else -> error("Некорректная строка unified patch")
                }
            }
            cursor = sourceIndex
        }
        output += originalLines.subList(cursor, originalLines.size)
        val result = output.joinToString("\n")
        return if (hadTrailingNewline) result + "\n" else result
    }

    private fun parseHunks(lines: List<String>): List<Hunk> {
        val result = mutableListOf<Hunk>()
        var currentHeader: String? = null
        var currentLines = mutableListOf<String>()

        fun flush() {
            val header = currentHeader ?: return
            val match = HUNK_PATTERN.matchEntire(header)
                ?: error("Некорректный hunk header: " + header)
            result += Hunk(
                header = header,
                oldStart = match.groupValues[1].toInt(),
                oldLines = currentLines
                    .filter { it.startsWith(" ") || it.startsWith("-") }
                    .map { it.drop(1) },
                lines = currentLines.toList(),
            )
            currentLines = mutableListOf()
        }

        lines.forEach { line ->
            when {
                line.startsWith("@@") -> {
                    flush()
                    currentHeader = line
                }

                currentHeader != null &&
                    (line.startsWith(" ") ||
                        line.startsWith("+") ||
                        line.startsWith("-") ||
                        line.startsWith("\\")) -> currentLines += line

                else -> Unit
            }
        }
        flush()
        return result
    }

    private fun findHunkStart(
        lines: List<String>,
        from: Int,
        expected: List<String>,
    ): Int {
        if (expected.isEmpty()) return from
        if (expected.size > lines.size - from) return -1
        for (index in from..(lines.size - expected.size)) {
            if (lines.subList(index, index + expected.size) == expected) return index
        }
        return -1
    }

    private fun buildUnifiedDiff(
        path: String,
        before: String,
        after: String,
    ): String {
        if (before == after) return "No changes"
        val oldLines = splitBodyLines(before)
        val newLines = splitBodyLines(after)
        var prefix = 0
        while (
            prefix < oldLines.size &&
            prefix < newLines.size &&
            oldLines[prefix] == newLines[prefix]
        ) {
            prefix += 1
        }
        var suffix = 0
        while (
            suffix < oldLines.size - prefix &&
            suffix < newLines.size - prefix &&
            oldLines[oldLines.size - 1 - suffix] ==
            newLines[newLines.size - 1 - suffix]
        ) {
            suffix += 1
        }

        val oldEnd = oldLines.size - suffix
        val newEnd = newLines.size - suffix
        val output = StringBuilder()
            .append("--- ").append(path).append("\n")
            .append("+++ ").append(path).append("\n")
            .append("@@ -").append(prefix + 1)
            .append(",").append(oldEnd - prefix)
            .append(" +").append(prefix + 1)
            .append(",").append(newEnd - prefix)
            .append(" @@\n")
        oldLines.subList(prefix, oldEnd).forEach {
            output.append("-").append(it).append("\n")
        }
        newLines.subList(prefix, newEnd).forEach {
            output.append("+").append(it).append("\n")
        }
        return output.toString().take(MAX_DIFF_CHARS)
    }

    private fun splitBodyLines(value: String): List<String> {
        val normalized = normalizeLineEndings(value)
        val body = if (normalized.endsWith("\n")) {
            normalized.dropLast(1)
        } else {
            normalized
        }
        return if (body.isEmpty()) emptyList() else body.split('\n')
    }

    private fun resolvePath(root: File, path: String): File {
        require(!path.startsWith("/") && !path.contains('\u0000')) {
            "Недопустимый patch path"
        }
        val target = File(root, path).canonicalFile
        val rootPath = root.canonicalFile.path
        require(
            target.path == rootPath ||
                target.path.startsWith(rootPath + File.separator),
        ) {
            "Patch path выходит за границы workspace"
        }
        return target
    }

    private fun isSensitiveFile(file: File): Boolean {
        val name = file.name.lowercase()
        return name == ".env" ||
            name.startsWith(".env.") ||
            name.endsWith(".pem") ||
            name.endsWith(".key") ||
            name.endsWith(".p12") ||
            name.endsWith(".jks") ||
            name == "google-services.json" ||
            name.contains("credential") ||
            name.contains("secret") ||
            name == "id_rsa"
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun normalizeLineEndings(value: String): String {
        return value.replace("\r\n", "\n").replace('\r', '\n')
    }

    private data class Hunk(
        val header: String,
        val oldStart: Int,
        val oldLines: List<String>,
        val lines: List<String>,
    )

    private companion object {
        val HUNK_PATTERN = Regex(
            "@@ -([0-9]+)(?:,[0-9]+)? \\+[0-9]+(?:,[0-9]+)? @@.*",
        )
        val OPERATION_ID_PATTERN = Regex("[A-Fa-f0-9-]{36}")
        val SHA256_PATTERN = Regex("[A-Fa-f0-9]{64}")
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
        const val MAX_CHECKPOINT_BYTES = 64 * 1024
        const val MAX_ERROR_CHARS = 4_000
        const val MAX_PATCH_CHARS = 2 * 1024 * 1024
        const val MAX_DIFF_CHARS = 32 * 1024
    }
}
