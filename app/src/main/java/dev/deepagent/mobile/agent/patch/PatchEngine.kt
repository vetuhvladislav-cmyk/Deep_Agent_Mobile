package dev.deepagent.mobile.agent.patch

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

data class PatchApplyResult(
    val preview: PatchPreview,
    val checkpointPath: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("path", preview.path)
        .put("old_sha256", preview.oldSha256)
        .put("new_sha256", preview.newSha256)
        .put("checkpoint", checkpointPath)
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

        checkpointDirectory.mkdirs()
        val operationId = UUID.randomUUID().toString()
        val checkpoint = if (target.isFile) {
            File(checkpointDirectory, operationId + "-" + target.name)
                .also { target.copyTo(it, overwrite = false) }
        } else {
            null
        }
        File(checkpointDirectory, operationId + ".json").writeText(
            JSONObject()
                .put("operation_id", operationId)
                .put("path", preview.path)
                .put("old_sha256", preview.oldSha256)
                .put("new_sha256", preview.newSha256)
                .put("checkpoint", checkpoint?.name)
                .put("created_at", System.currentTimeMillis())
                .toString(),
            Charsets.UTF_8,
        )

        target.parentFile?.mkdirs()
        val temporary = File(
            target.parentFile,
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
            checkpointPath = checkpoint?.canonicalPath,
        )
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
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
        const val MAX_PATCH_CHARS = 2 * 1024 * 1024
        const val MAX_DIFF_CHARS = 32 * 1024
    }
}
