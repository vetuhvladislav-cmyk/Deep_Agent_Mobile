package dev.deepagent.mobile.agent.image

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import dev.deepagent.mobile.agent.deepseek.DeepSeekImage
import dev.deepagent.mobile.agent.model.AgentRedactor
import dev.deepagent.mobile.agent.model.ImageAnalysisState
import dev.deepagent.mobile.agent.model.ImageAnalysisStatus
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PreparedImageAsset(
    val assetId: String,
    val mediaType: String,
    val displayName: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val checksum: String,
    internal val file: File,
) {
    fun toState(): ImageAnalysisState = ImageAnalysisState(
        status = ImageAnalysisStatus.READY,
        assetId = assetId,
        displayName = displayName,
        mediaType = mediaType,
        sizeBytes = sizeBytes,
        width = width,
        height = height,
        checksum = checksum,
        summary = "Изображение прошло локальную проверку",
    )
}

class ImageAnalysisPipeline(context: Context) {

    private val directory = File(
        context.applicationContext.filesDir,
        "agent-image-cache",
    )
    private val lock = Any()
    private var activeAsset: PreparedImageAsset? = null

    init {
        check(directory.mkdirs() || directory.isDirectory) {
            "Не удалось создать image cache"
        }
        directory.listFiles()?.forEach { file ->
            if (
                file.isFile &&
                System.currentTimeMillis() - file.lastModified() > CACHE_TTL_MS
            ) {
                file.delete()
            }
        }
    }

    suspend fun prepare(
        resolver: ContentResolver,
        uri: Uri,
        displayName: String? = null,
    ): PreparedImageAsset = withContext(Dispatchers.IO) {
        require(uri.scheme?.isNotBlank() == true) {
            "URI изображения недействителен"
        }
        val assetId = UUID.randomUUID().toString()
        val temporary = File(directory, "." + assetId + ".tmp")
        val target = File(directory, assetId + ".img")
        val digest = MessageDigest.getInstance("SHA-256")
        var sizeBytes = 0L
        try {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        sizeBytes += count
                        require(sizeBytes <= MAX_IMAGE_BYTES) {
                            "Изображение превышает лимит 12 MiB"
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                }
            } ?: error("Не удалось открыть изображение")

            require(sizeBytes > 0L) { "Изображение пустое" }
            val mediaType = detectMediaType(temporary)
            validateDeclaredType(resolver.getType(uri), mediaType)
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            FileInputStream(temporary).use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
            require(
                bounds.outWidth in 1..MAX_IMAGE_WIDTH &&
                    bounds.outHeight in 1..MAX_IMAGE_HEIGHT,
            ) {
                "Изображение имеет недопустимые pixel bounds"
            }
            val pixelCount = bounds.outWidth.toLong() * bounds.outHeight.toLong()
            require(pixelCount <= MAX_IMAGE_PIXELS) {
                "Изображение превышает лимит pixel count"
            }
            check(temporary.renameTo(target)) {
                "Не удалось зафиксировать image asset"
            }
            val asset = PreparedImageAsset(
                assetId = assetId,
                mediaType = mediaType,
                displayName = safeDisplayName(displayName, uri),
                sizeBytes = sizeBytes,
                width = bounds.outWidth,
                height = bounds.outHeight,
                checksum = digest.digest().toHex(),
                file = target,
            )
            val previous = synchronized(lock) {
                val old = activeAsset
                activeAsset = asset
                old
            }
            previous?.file?.delete()
            asset
        } catch (error: Throwable) {
            temporary.delete()
            target.delete()
            throw error
        }
    }

    suspend fun toDeepSeekImage(assetId: String): DeepSeekImage? =
        withContext(Dispatchers.IO) {
            val asset = synchronized(lock) {
                activeAsset?.takeIf { it.assetId == assetId }
            } ?: return@withContext null
            try {
                if (!asset.file.isFile || asset.file.length() != asset.sizeBytes) {
                    return@withContext null
                }
                val bytes = asset.file.readBytes()
                if (bytes.size.toLong() != asset.sizeBytes) return@withContext null
                val checksum = MessageDigest.getInstance("SHA-256")
                    .digest(bytes)
                    .toHex()
                if (!checksum.equals(asset.checksum, ignoreCase = true)) {
                    return@withContext null
                }
                DeepSeekImage(
                    dataUrl = "data:" + asset.mediaType + ";base64," +
                        Base64.encodeToString(bytes, Base64.NO_WRAP),
                    detail = "auto",
                )
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }

    fun clear(assetId: String? = null) {
        val removed = synchronized(lock) {
            val current = activeAsset
            if (assetId != null && current?.assetId != assetId) {
                null
            } else {
                activeAsset = null
                current
            }
        }
        removed?.file?.delete()
    }

    private fun detectMediaType(file: File): String {
        val header = ByteArray(16)
        val count = FileInputStream(file).use { input ->
            input.read(header)
        }
        require(count >= 6) { "Формат изображения не распознан" }
        return when {
            header[0] == 0xff.toByte() &&
                header[1] == 0xd8.toByte() &&
                header[2] == 0xff.toByte() -> "image/jpeg"
            String(header, 0, 6, Charsets.US_ASCII) == "GIF87a" ||
                String(header, 0, 6, Charsets.US_ASCII) == "GIF89a" -> "image/gif"
            count >= 8 &&
                header.copyOfRange(0, 8).contentEquals(
                    byteArrayOf(
                        0x89.toByte(), 0x50, 0x4e, 0x47,
                        0x0d, 0x0a, 0x1a, 0x0a,
                    ),
                ) -> "image/png"
            count >= 12 &&
                String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 12, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> error("Поддерживаются только JPEG, PNG, GIF и WebP")
        }
    }

    private fun validateDeclaredType(
        declaredType: String?,
        detectedType: String,
    ) {
        val normalized = declaredType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: return
        if (
            normalized.startsWith("image/") &&
            normalized !in SUPPORTED_MEDIA_TYPES
        ) {
            error("Тип изображения не поддерживается: " + normalized)
        }
        require(detectedType in SUPPORTED_MEDIA_TYPES) {
            "Сигнатура изображения не поддерживается"
        }
        require(normalized !in SUPPORTED_MEDIA_TYPES || normalized == detectedType) {
            "MIME не совпадает с сигнатурой изображения"
        }
    }

    private fun safeDisplayName(displayName: String?, uri: Uri): String {
        val source = displayName?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment
            ?: "image"
        return AgentRedactor.text(source, MAX_DISPLAY_NAME_CHARS)
            .orEmpty()
            .replace('\n', ' ')
            .replace('\r', ' ')
            .ifBlank { "image" }
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private companion object {
        const val COPY_BUFFER_SIZE = 16 * 1024
        const val MAX_IMAGE_BYTES = 12L * 1024L * 1024L
        const val MAX_IMAGE_WIDTH = 8_192
        const val MAX_IMAGE_HEIGHT = 8_192
        const val MAX_IMAGE_PIXELS = 32L * 1024L * 1024L
        const val MAX_DISPLAY_NAME_CHARS = 200
        const val CACHE_TTL_MS = 60L * 60L * 1_000L
        val SUPPORTED_MEDIA_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp",
        )
    }
}
