package dev.deepagent.mobile.agent.workspace

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

data class WorkspaceSummary(
    val id: String,
    val displayName: String,
    val sourceType: String,
    val rootPath: String,
    val fileCount: Int,
    val totalBytes: Long,
    val importedAt: Long,
    val fingerprint: String? = null,
    val repository: String? = null,
    val ref: String? = null,
    val commitSha: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("display_name", displayName)
        .put("source_type", sourceType)
        .put("root_path", rootPath)
        .put("file_count", fileCount)
        .put("total_bytes", totalBytes)
        .put("imported_at", importedAt)
        .put("fingerprint", fingerprint)
        .put("repository", repository)
        .put("ref", ref)
        .put("commit_sha", commitSha)

    companion object {
        fun fromJson(value: JSONObject): WorkspaceSummary? {
            val id = value.optString("id").trim()
            val rootPath = value.optString("root_path").trim()
            if (id.isBlank() || rootPath.isBlank()) return null
            return WorkspaceSummary(
                id = id,
                displayName = value.optString("display_name").ifBlank { id },
                sourceType = value.optString("source_type").ifBlank { "unknown" },
                rootPath = rootPath,
                fileCount = value.optInt("file_count", 0),
                totalBytes = value.optLong("total_bytes", 0L),
                importedAt = value.optLong("imported_at", 0L),
                fingerprint = value.optString("fingerprint").takeIf { it.isNotBlank() },
                repository = value.optString("repository").takeIf { it.isNotBlank() },
                ref = value.optString("ref").takeIf { it.isNotBlank() },
                commitSha = value.optString("commit_sha").takeIf { it.isNotBlank() },
            )
        }
    }
}

/**
 * Owns imported project snapshots inside app-private storage.
 *
 * The manager never exposes the original SAF URI to tools. Imports are copied
 * into a private directory, so read-only operations stay bound to one stable
 * workspace identity even after the document provider changes or disappears.
 */
class WorkspaceManager(context: Context) {

    private val appContext = context.applicationContext
    private val workspacesDirectory = File(appContext.filesDir, "agent-workspaces")
    private val indexFile = File(appContext.filesDir, "agent-workspaces.json")

    private var entries: MutableList<WorkspaceSummary> = loadEntries().toMutableList()
    private var selectedId: String? = loadSelectedId()

    private val _current = MutableStateFlow(findSelected())
    val current: StateFlow<WorkspaceSummary?> = _current.asStateFlow()

    init {
        workspacesDirectory.mkdirs()
        pruneMissingEntries()
    }

    fun list(): List<WorkspaceSummary> = synchronized(this) {
        entries.sortedByDescending { it.importedAt }
    }

    fun select(id: String): WorkspaceSummary = synchronized(this) {
        val entry = entries.firstOrNull { it.id == id }
            ?: error("Workspace не найден: " + id)
        require(resolveRoot(entry) != null) { "Workspace недоступен: " + entry.displayName }
        selectedId = entry.id
        persistIndexLocked()
        _current.value = entry
        entry
    }

    fun resolveRoot(id: String? = current.value?.id): File? = synchronized(this) {
        val entry = entries.firstOrNull { it.id == id } ?: return@synchronized null
        resolveRoot(entry)
    }

    fun captureIdentity(id: String? = current.value?.id): WorkspaceIdentity? {
        val entry = synchronized(this) {
            entries.firstOrNull { it.id == id }
        } ?: return null
        val root = resolveRoot(entry) ?: return null
        return WorkspaceIdentity.capture(entry.id, root)
    }

    fun checkpointDirectory(id: String? = current.value?.id): File? = synchronized(this) {
        val entry = entries.firstOrNull { it.id == id } ?: return@synchronized null
        if (!entry.id.matches(WORKSPACE_ID_PATTERN)) return@synchronized null
        if (resolveRoot(entry) == null) return@synchronized null
        File(appContext.filesDir, "agent-checkpoints/" + entry.id)
    }


    fun refresh(id: String? = current.value?.id): WorkspaceSummary = synchronized(this) {
        val entry = entries.firstOrNull { it.id == id }
            ?: error("Workspace не найден")
        val root = resolveRoot(entry)
            ?: error("Workspace недоступен: " + entry.displayName)
        val identity = WorkspaceIdentity.capture(entry.id, root)
        val refreshed = entry.copy(
            fileCount = identity.fileCount,
            totalBytes = identity.totalBytes,
            fingerprint = identity.treeSha256,
        )
        entries[entries.indexOf(entry)] = refreshed
        persistIndexLocked()
        if (selectedId == refreshed.id) {
            _current.value = refreshed
        }
        refreshed
    }

    fun treePage(
        id: String? = current.value?.id,
        prefix: String = "",
        maxDepth: Int = 6,
        maxEntries: Int = 300,
    ): WorkspaceTreePage = synchronized(this) {
        val entry = entries.firstOrNull { it.id == id }
            ?: return@synchronized WorkspaceTreePage()
        val root = resolveRoot(entry)
            ?: error("Workspace недоступен: " + entry.displayName)
        val normalizedPrefix = normalizeTreePrefix(prefix)
        val base = if (normalizedPrefix.isBlank()) {
            root
        } else {
            resolveChild(root, normalizedPrefix).also {
                require(it.isDirectory) { "Tree prefix не является директорией" }
            }
        }
        val entries = mutableListOf<WorkspaceFileEntry>()
        var truncated = false
        val boundedDepth = maxDepth.coerceIn(0, MAX_TREE_DEPTH)
        val boundedEntries = maxEntries.coerceIn(1, MAX_TREE_ENTRIES)

        fun visit(directory: File, depth: Int) {
            if (entries.size >= boundedEntries) {
                truncated = true
                return
            }
            val children = directory.listFiles()
                ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name })
                ?: error("Не удалось прочитать каталог workspace")
            for (child in children) {
                if (entries.size >= boundedEntries) {
                    truncated = true
                    return
                }
                if (child.name in IGNORED_TREE_DIRECTORIES) continue
                require(!Files.isSymbolicLink(child.toPath())) {
                    "Symbolic link запрещён в workspace tree: " + child.name
                }
                val canonical = child.canonicalFile
                require(
                    canonical.path == root.canonicalPath ||
                        canonical.path.startsWith(root.canonicalPath + File.separator),
                ) {
                    "Workspace tree entry выходит за границы root"
                }
                val relative = root.toPath()
                    .relativize(child.toPath())
                    .toString()
                    .replace(File.separatorChar, '/')
                entries += WorkspaceFileEntry(
                    path = relative,
                    type = if (child.isDirectory) "directory" else "file",
                    sizeBytes = if (child.isFile) child.length() else 0L,
                    depth = depth,
                )
                if (child.isDirectory && depth < boundedDepth) {
                    visit(child, depth + 1)
                }
            }
        }
        visit(base, 0)
        WorkspaceTreePage(entries = entries, truncated = truncated)
    }

    private fun normalizeTreePrefix(value: String): String {
        val normalized = value.replace('\\', '/').trim('/')
        if (normalized.isBlank()) return ""
        val parts = normalized.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) {
            "Tree prefix недействителен"
        }
        return parts.joinToString("/")
    }

    suspend fun importUri(
        resolver: ContentResolver,
        uri: Uri,
        displayName: String? = null,
    ): WorkspaceSummary = withContext(Dispatchers.IO) {
        requireNotNull(resolver) { "ContentResolver не задан" }

        val workspaceId = UUID.randomUUID().toString()
        val temporaryDirectory = File(workspacesDirectory, "." + workspaceId + ".import")
        val finalDirectory = File(workspacesDirectory, workspaceId)
        val sourceName = displayName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: queryDisplayName(resolver, uri)
            ?: uri.lastPathSegment
            ?: "Imported workspace"
        val budget = ImportBudget()

        workspacesDirectory.mkdirs()
        check(temporaryDirectory.mkdirs()) {
            "Не удалось создать временную директорию workspace"
        }

        try {
            if (DocumentsContract.isTreeUri(uri)) {
                copyDocumentTree(
                    resolver = resolver,
                    treeUri = uri,
                    documentId = DocumentsContract.getTreeDocumentId(uri),
                    destination = temporaryDirectory,
                    relativeParent = "",
                    budget = budget,
                )
            } else {
                resolver.openInputStream(uri)?.use { input ->
                    copyZip(
                        input = input,
                        destination = temporaryDirectory,
                        budget = budget,
                    )
                } ?: error("Не удалось открыть выбранный документ")
            }

            val snapshot = summarizeTree(temporaryDirectory)
            check(snapshot.first > 0) { "Workspace не содержит файлов" }
            check(temporaryDirectory.renameTo(finalDirectory)) {
                "Не удалось зафиксировать импортированный workspace"
            }

            val summary = WorkspaceSummary(
                id = workspaceId,
                displayName = sourceName
                    .removeSuffix(".zip")
                    .removeSuffix(".ZIP")
                    .ifBlank { "Workspace " + workspaceId.take(8) },
                sourceType = if (DocumentsContract.isTreeUri(uri)) "folder" else "zip",
                rootPath = finalDirectory.canonicalPath,
                fileCount = snapshot.first,
                totalBytes = snapshot.second,
                importedAt = System.currentTimeMillis(),
            )

            synchronized(this@WorkspaceManager) {
                entries.removeAll { it.id == summary.id }
                entries.add(summary)
                selectedId = summary.id
                persistIndexLocked()
                _current.value = summary
            }
            summary
        } catch (error: Throwable) {
            temporaryDirectory.deleteRecursively()
            finalDirectory.deleteRecursively()
            throw error
        }
    }

    private fun copyZip(
        input: InputStream,
        destination: File,
        budget: ImportBudget,
    ) {
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val relative = normalizeRelativePath(entry.name)
                if (relative == null) {
                    zip.closeEntry()
                    continue
                }

                val target = resolveChild(destination, relative)
                if (entry.isDirectory) {
                    check(target.mkdirs() || target.isDirectory) {
                        "Не удалось создать директорию архива"
                    }
                } else {
                    check(target.parentFile?.mkdirs() != false) {
                        "Не удалось создать родительскую директорию архива"
                    }
                    budget.beginFile()
                    FileOutputStream(target).use { output ->
                        copyBounded(zip, BufferedOutputStream(output), budget)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun copyDocumentTree(
        resolver: ContentResolver,
        treeUri: Uri,
        documentId: String,
        destination: File,
        relativeParent: String,
        budget: ImportBudget,
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            documentId,
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )

        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            )
            val nameIndex = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            )
            val mimeIndex = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )

            while (cursor.moveToNext()) {
                val childId = cursor.getString(idIndex)
                val childName = cursor.getString(nameIndex).orEmpty()
                val relativeName = normalizeRelativePath(
                    if (relativeParent.isBlank()) {
                        childName
                    } else {
                        relativeParent + "/" + childName
                    },
                ) ?: continue
                val target = resolveChild(destination, relativeName)
                val mimeType = cursor.getString(mimeIndex).orEmpty()

                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    check(target.mkdirs() || target.isDirectory) {
                        "Не удалось создать директорию workspace"
                    }
                    copyDocumentTree(
                        resolver = resolver,
                        treeUri = treeUri,
                        documentId = childId,
                        destination = destination,
                        relativeParent = relativeName,
                        budget = budget,
                    )
                } else {
                    check(target.parentFile?.mkdirs() != false) {
                        "Не удалось создать родительскую директорию workspace"
                    }
                    resolver.openInputStream(
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, childId),
                    )?.use { input ->
                        budget.beginFile()
                        FileOutputStream(target).use { output ->
                            copyBounded(
                                input = BufferedInputStream(input),
                                output = BufferedOutputStream(output),
                                budget = budget,
                            )
                        }
                    } ?: error("Не удалось прочитать файл workspace: " + childName)
                }
            }
        } ?: error("Провайдер документов не вернул содержимое директории")
    }

    private fun copyBounded(
        input: InputStream,
        output: BufferedOutputStream,
        budget: ImportBudget,
    ) {
        val source = input
        output.use { target ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            var fileBytes = 0L
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                fileBytes += count
                budget.addBytes(count.toLong(), fileBytes)
                target.write(buffer, 0, count)
            }
        }
    }

    private fun resolveChild(root: File, relative: String): File {
        val target = File(root, relative).canonicalFile
        val rootPath = root.canonicalFile.path
        check(target.path == rootPath || target.path.startsWith(rootPath + File.separator)) {
            "Путь выходит за границы workspace"
        }
        return target
    }

    private fun normalizeRelativePath(value: String): String? {
        val normalized = value.replace('\\', '/')
        if (normalized.startsWith("/") || normalized.contains('\u0000')) return null
        val parts = normalized.split('/')
            .filter { it.isNotBlank() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    private fun summarizeTree(root: File): Pair<Int, Long> {
        var files = 0
        var bytes = 0L
        root.walkTopDown().forEach { file ->
            if (file.isFile) {
                files += 1
                bytes += file.length()
            }
        }
        return files to bytes
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)
            } else {
                null
            }
        }
    }

    private fun loadEntries(): List<WorkspaceSummary> {
        if (!indexFile.isFile) return emptyList()
        val root = runCatching { JSONObject(indexFile.readText()) }.getOrNull()
            ?: return emptyList()
        val array = root.optJSONArray("workspaces") ?: JSONArray()
        return buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)
                    ?.let { WorkspaceSummary.fromJson(it) }
                    ?.let(::add)
            }
        }
    }

    private fun loadSelectedId(): String? {
        if (!indexFile.isFile) return null
        return runCatching {
            JSONObject(indexFile.readText())
                .optString("selected_id")
                .takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun findSelected(): WorkspaceSummary? {
        val id = selectedId ?: return null
        return entries.firstOrNull { it.id == id && resolveRoot(it) != null }
    }

    private fun resolveRoot(entry: WorkspaceSummary): File? {
        val root = runCatching { File(entry.rootPath).canonicalFile }.getOrNull()
            ?: return null
        val base = runCatching { workspacesDirectory.canonicalFile }.getOrNull()
            ?: return null
        if (root.path != base.path &&
            !root.path.startsWith(base.path + File.separator)
        ) {
            return null
        }
        return root.takeIf { it.isDirectory }
    }

    private fun pruneMissingEntries() = synchronized(this) {
        val before = entries.size
        entries.removeAll { resolveRoot(it) == null }
        if (selectedId !in entries.map { it.id }) {
            selectedId = entries.maxByOrNull { it.importedAt }?.id
        }
        if (before != entries.size) persistIndexLocked()
        _current.value = findSelected()
    }

    private fun persistIndexLocked() {
        val payload = JSONObject()
            .put("version", INDEX_VERSION)
            .put("selected_id", selectedId)
            .put(
                "workspaces",
                JSONArray().apply {
                    entries.sortedByDescending { it.importedAt }
                        .forEach { put(it.toJson()) }
                },
            )
        val temporary = File(indexFile.parentFile, indexFile.name + ".tmp")
        temporary.writeText(payload.toString(), Charsets.UTF_8)
        check(temporary.renameTo(indexFile)) {
            "Не удалось сохранить индекс workspace"
        }
    }

    private class ImportBudget {
        private var files = 0
        private var bytes = 0L

        fun beginFile() {
            files += 1
            check(files <= MAX_FILES) {
                "Workspace превышает лимит количества файлов"
            }
        }

        fun addBytes(delta: Long, fileBytes: Long) {
            check(fileBytes <= MAX_FILE_BYTES) {
                "Файл workspace превышает лимит размера"
            }
            bytes += delta
            check(bytes <= MAX_TOTAL_BYTES) {
                "Workspace превышает лимит общего размера"
            }
        }
    }

    private companion object {
        const val INDEX_VERSION = 2
        const val MAX_TREE_DEPTH = 8
        const val MAX_TREE_ENTRIES = 500
        val IGNORED_TREE_DIRECTORIES = setOf(".git", ".gradle", "build", "node_modules")
        const val COPY_BUFFER_SIZE = 16 * 1024
        const val MAX_FILES = 20_000
        const val MAX_FILE_BYTES = 16L * 1024L * 1024L
        const val MAX_TOTAL_BYTES = 256L * 1024L * 1024L
        val WORKSPACE_ID_PATTERN = Regex("[A-Za-z0-9-]{8,80}")
    }
}
