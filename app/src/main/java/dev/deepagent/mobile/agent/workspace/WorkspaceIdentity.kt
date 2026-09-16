package dev.deepagent.mobile.agent.workspace

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Stable snapshot identity used to bind a preview and a later write to the
 * same app-private workspace state.
 *
 * The fingerprint is deterministic over relative paths, entry types, sizes and
 * file content hashes. Symbolic links are rejected so the identity cannot
 * escape the workspace boundary.
 */
data class WorkspaceIdentity(
    val workspaceId: String,
    val rootPath: String,
    val treeSha256: String,
    val fileCount: Int,
    val totalBytes: Long,
) {
    fun toJson() = org.json.JSONObject()
        .put("workspace_id", workspaceId)
        .put("tree_sha256", treeSha256)
        .put("file_count", fileCount)
        .put("total_bytes", totalBytes)

    companion object {
        fun capture(workspaceId: String, root: File): WorkspaceIdentity {
            val canonicalRoot = root.canonicalFile
            require(canonicalRoot.isDirectory) {
                "Workspace root недоступен"
            }

            val entries = mutableListOf<TreeEntry>()
            val budget = CaptureBudget()
            collect(
                root = canonicalRoot,
                directory = canonicalRoot,
                relativeDirectory = "",
                entries = entries,
                budget = budget,
            )
            entries.sortBy { it.path }

            val digest = MessageDigest.getInstance("SHA-256")
            updateField(digest, "deep-agent-workspace-tree-v1")
            entries.forEach { entry ->
                updateField(digest, entry.type)
                updateField(digest, entry.path)
                updateField(digest, entry.size.toString())
                updateField(digest, entry.contentSha256.orEmpty())
            }

            return WorkspaceIdentity(
                workspaceId = workspaceId,
                rootPath = canonicalRoot.path,
                treeSha256 = digest.digest().toHex(),
                fileCount = budget.files,
                totalBytes = budget.bytes,
            )
        }

        private fun collect(
            root: File,
            directory: File,
            relativeDirectory: String,
            entries: MutableList<TreeEntry>,
            budget: CaptureBudget,
        ) {
            val children = directory.listFiles()
                ?.sortedBy { it.name }
                ?: error("Не удалось прочитать каталог workspace")

            for (child in children) {
                require(!Files.isSymbolicLink(child.toPath())) {
                    "Symbolic link запрещён в workspace: " + child.name
                }
                val canonical = child.canonicalFile
                require(
                    canonical.path == root.path ||
                        canonical.path.startsWith(root.path + File.separator),
                ) {
                    "Workspace entry выходит за границы root"
                }
                val relativePath = if (relativeDirectory.isBlank()) {
                    child.name
                } else {
                    relativeDirectory + "/" + child.name
                }
                require(!relativePath.contains('\u0000')) {
                    "Недопустимое имя workspace entry"
                }

                when {
                    child.isDirectory -> {
                        entries += TreeEntry(
                            type = "directory",
                            path = relativePath,
                            size = 0L,
                            contentSha256 = null,
                        )
                        collect(root, child, relativePath, entries, budget)
                    }

                    child.isFile -> {
                        val size = child.length()
                        require(size <= MAX_FILE_BYTES) {
                            "Файл workspace превышает лимит fingerprint"
                        }
                        val contentSha256 = digestFile(child, size)
                        budget.files += 1
                        require(budget.files <= MAX_FILES) {
                            "Workspace превышает лимит fingerprint файлов"
                        }
                        budget.bytes = safeAdd(budget.bytes, size)
                        entries += TreeEntry(
                            type = "file",
                            path = relativePath,
                            size = size,
                            contentSha256 = contentSha256,
                        )
                    }

                    else -> error("Неподдерживаемый тип workspace entry: " + child.name)
                }
            }
        }

        private fun digestFile(file: File, expectedSize: Long): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0L
            file.inputStream().use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    total += count
                    require(total <= MAX_TOTAL_BYTES) {
                        "Workspace превышает лимит fingerprint bytes"
                    }
                }
            }
            require(total == expectedSize && file.length() == expectedSize) {
                "Файл изменился во время fingerprint: " + file.name
            }
            return digest.digest().toHex()
        }

        private fun updateField(digest: MessageDigest, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
            digest.update(':'.code.toByte())
            digest.update(bytes)
        }

        private fun safeAdd(current: Long, delta: Long): Long {
            require(delta >= 0L && current <= MAX_TOTAL_BYTES - delta) {
                "Workspace превышает лимит fingerprint bytes"
            }
            return current + delta
        }

        private data class TreeEntry(
            val type: String,
            val path: String,
            val size: Long,
            val contentSha256: String?,
        )

        private class CaptureBudget(
            var files: Int = 0,
            var bytes: Long = 0L,
        )

        private fun ByteArray.toHex(): String = joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }

        private const val BUFFER_SIZE = 16 * 1024
        private const val MAX_FILES = 20_000
        private const val MAX_FILE_BYTES = 16L * 1024L * 1024L
        private const val MAX_TOTAL_BYTES = 256L * 1024L * 1024L
    }
}
