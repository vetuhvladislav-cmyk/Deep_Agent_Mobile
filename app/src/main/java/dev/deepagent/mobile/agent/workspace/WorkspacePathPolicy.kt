package dev.deepagent.mobile.agent.workspace

import java.io.File
import java.nio.file.Files

/**
 * Single fail-closed path policy for user/model supplied workspace paths.
 *
 * Directory listing and workspace fingerprinting have their own traversal
 * guards, but direct read/write/git operations must enforce the same rules.
 */
object WorkspacePathPolicy {

    private val SENSITIVE_FILE_NAMES = setOf(
        ".env",
        ".npmrc",
        ".netrc",
        ".p12",
        ".pfx",
        ".jks",
        ".keystore",
        "google-services.json",
        "gradle.properties",
        "local.properties",
        "id_rsa",
        "id_ed25519",
    )

    fun resolve(
        root: File,
        requestedPath: String,
        requireExisting: Boolean,
    ): File {
        val canonicalRoot = root.canonicalFile
        require(canonicalRoot.isDirectory) {
            "Workspace root недоступен"
        }

        val normalized = requestedPath.trim().replace('\\', '/')
        require(!normalized.startsWith("/") && !normalized.contains('\u0000')) {
            "Недопустимый путь"
        }
        require(!isBlockedRelativePath(normalized)) {
            "Путь заблокирован политикой workspace"
        }

        var cursor = canonicalRoot.toPath()
        normalized.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> error("Parent traversal запрещён")
                else -> {
                    cursor = cursor.resolve(part)
                    require(!Files.isSymbolicLink(cursor)) {
                        "Symbolic link запрещён в workspace: " + part
                    }
                }
            }
        }

        val target = File(canonicalRoot, normalized).canonicalFile
        val rootPath = canonicalRoot.path
        require(
            target.path == rootPath ||
                target.path.startsWith(rootPath + File.separator),
        ) {
            "Путь выходит за границы workspace"
        }
        if (requireExisting) {
            require(target.exists()) {
                "Путь не найден: " + requestedPath
            }
        }
        if (target.exists()) {
            require(!Files.isSymbolicLink(target.toPath())) {
                "Symbolic link запрещён в workspace"
            }
            require(!isSensitiveFile(target)) {
                "Чувствительный файл заблокирован политикой workspace"
            }
        }
        return target
    }

    fun isSensitiveFile(file: File): Boolean {
        return isSensitiveName(file.name)
    }

    fun isBlockedRelativePath(path: String): Boolean {
        val parts = path.replace('\\', '/')
            .split('/')
            .filter { it.isNotBlank() && it != "." }
        return parts.any { it.equals(".git", ignoreCase = true) } ||
            parts.any(::isSensitiveName)
    }

    private fun isSensitiveName(rawName: String): Boolean {
        val name = rawName.lowercase()
        return name in SENSITIVE_FILE_NAMES ||
            name.startsWith(".env.") ||
            name.endsWith(".pem") ||
            name.endsWith(".key") ||
            name.endsWith(".der") ||
            name.endsWith(".asc") ||
            name.endsWith(".gpg") ||
            name.contains("credential") ||
            name.contains("secret") ||
            name.contains("password") ||
            name == "id_rsa" ||
            name == "id_ed25519"
    }
}
