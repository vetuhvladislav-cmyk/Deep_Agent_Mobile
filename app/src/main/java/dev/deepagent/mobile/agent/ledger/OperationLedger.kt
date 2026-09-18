package dev.deepagent.mobile.agent.ledger

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.deepagent.mobile.agent.model.AgentRedactor
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.Key
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

enum class LedgerDurability {
    FULL,
    BATCHED,
}

enum class LedgerResolution {
    QUERYABLE,
    IDEMPOTENT,
    BLIND,
}

enum class LedgerPhase {
    PREPARED,
    STARTED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    UNKNOWN,
}

enum class LedgerIntegrityMode {
    CRC32C,
    HMAC_SHA256,
}

enum class LedgerHealth {
    CLEAN,
    RECOVERY_REQUIRED,
    CORRUPT,
    KEY_UNAVAILABLE,
}

enum class LedgerRecoveryAction {
    RECHECK_REQUIRED,
    EXPLICIT_RETRY_WITH_SAME_OPERATION_ID,
    MANUAL_RECONCILIATION,
}

data class LedgerOperationSpec(
    val operationId: String,
    val operation: String,
    val sessionId: String? = null,
    val workspaceId: String? = null,
    val targetSha: String? = null,
    val argumentsSha256: String? = null,
    val resolution: LedgerResolution,
    val durability: LedgerDurability = LedgerDurability.FULL,
    val integrityMode: LedgerIntegrityMode = LedgerIntegrityMode.CRC32C,
    val keyVersion: Int = 0,
    val sideEffect: Boolean = true,
) {
    init {
        require(ID_PATTERN.matches(operationId)) {
            "Недопустимый operation ID ledger"
        }
        require(operation.isNotBlank() && operation.length <= MAX_IDENTIFIER_CHARS) {
            "Недопустимое имя операции ledger"
        }
        require(keyVersion >= 0) { "keyVersion ledger не может быть отрицательным" }
        require(!(sideEffect && durability == LedgerDurability.BATCHED)) {
            "BATCHED durability запрещён для side-effect операции"
        }
    }

    companion object {
        private const val MAX_IDENTIFIER_CHARS = 160
        private val ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,160}")
    }
}

data class LedgerRecord(
    val schemaVersion: Int,
    val sequence: Long,
    val bootId: String,
    val operationId: String,
    val operation: String,
    val sessionId: String?,
    val workspaceId: String?,
    val targetSha: String?,
    val argumentsSha256: String?,
    val resolution: LedgerResolution,
    val durability: LedgerDurability,
    val integrityMode: LedgerIntegrityMode,
    val keyVersion: Int,
    val sideEffect: Boolean,
    val phase: LedgerPhase,
    val createdAt: Long,
    val updatedAt: Long,
    val detail: String? = null,
    /**
     * Номер попытки внутри одной операции. Один `operationId` может иметь
     * несколько попыток: первая, затем explicit retry после UNKNOWN. Раньше
     * повтор удалял предыдущую запись, из-за чего история попыток терялась.
     */
    val attempt: Int = 1,
    /**
     * Хеш предыдущего кадра. Связывает записи в цепочку, поэтому удаление или
     * перестановка любой записи делает цепочку невалидной.
     */
    val previousChainHash: String? = null,
    /** Хеш этого кадра; сам в себя не входит. */
    val chainHash: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schema_version", schemaVersion)
        .put("sequence", sequence)
        .put("boot_id", safe(bootId, 96))
        .put("operation_id", safe(operationId, 160))
        .put("operation", safe(operation, 160))
        .put("session_id", safe(sessionId, 160))
        .put("workspace_id", safe(workspaceId, 160))
        .put("target_sha", safe(targetSha, 128))
        .put("arguments_sha256", safe(argumentsSha256, 128))
        .put("resolution", resolution.name)
        .put("durability", durability.name)
        .put("integrity_mode", integrityMode.name)
        .put("key_version", keyVersion)
        .put("side_effect", sideEffect)
        .put("phase", phase.name)
        .put("created_at", createdAt)
        .put("updated_at", updatedAt)
        .put("detail", safe(detail, MAX_DETAIL_CHARS))
        .put("attempt", attempt)
        .put("previous_chain_hash", safe(previousChainHash, CHAIN_HASH_CHARS))
        .put("chain_hash", safe(chainHash, CHAIN_HASH_CHARS))

    companion object {
        const val SCHEMA_VERSION = 2
        const val MIN_SUPPORTED_SCHEMA_VERSION = 1
        const val MAX_DETAIL_CHARS = 2_000
        const val CHAIN_HASH_CHARS = 64

        fun fromJson(value: JSONObject): LedgerRecord {
            val schemaVersion = value.optInt("schema_version", 0)
            if (
                schemaVersion > SCHEMA_VERSION ||
                schemaVersion < MIN_SUPPORTED_SCHEMA_VERSION
            ) {
                throw LedgerDowngradeException()
            }
            val operationId = value.optString("operation_id").trim()
            require(Regex("[A-Za-z0-9._:-]{1,160}").matches(operationId)) {
                "Недопустимый operation ID ledger"
            }
            val operation = value.optString("operation").trim()
            require(operation.isNotBlank() && operation.length <= 160) {
                "Недопустимое имя операции ledger"
            }
            val phase = runCatching {
                LedgerPhase.valueOf(value.optString("phase"))
            }.getOrElse {
                error("Неизвестная фаза ledger")
            }
            val resolution = runCatching {
                LedgerResolution.valueOf(value.optString("resolution"))
            }.getOrElse {
                error("Неизвестная resolution ledger")
            }
            val durability = runCatching {
                LedgerDurability.valueOf(value.optString("durability"))
            }.getOrElse {
                error("Неизвестная durability ledger")
            }
            val integrityMode = runCatching {
                LedgerIntegrityMode.valueOf(value.optString("integrity_mode"))
            }.getOrElse {
                error("Неизвестный integrity mode ledger")
            }
            val sequence = value.optLong("sequence", 0L)
            val bootId = value.optString("boot_id").trim()
            val keyVersion = value.optInt("key_version", 0)
            val sideEffect = value.optBoolean("side_effect", true)
            val attempt = value.optInt("attempt", 1)
            require(sequence > 0L) { "Недопустимая sequence ledger" }
            require(bootId.isNotBlank()) { "Пустой boot ID ledger" }
            require(keyVersion >= 0) { "Недопустимый keyVersion ledger" }
            require(attempt >= 1) { "Недопустимый номер попытки ledger" }
            require(!(sideEffect && durability == LedgerDurability.BATCHED)) {
                "BATCHED side-effect запись ledger запрещена"
            }
            return LedgerRecord(
                schemaVersion = schemaVersion,
                sequence = sequence,
                bootId = bootId,
                operationId = operationId,
                operation = operation,
                sessionId = safe(value.optString("session_id"), 160),
                workspaceId = safe(value.optString("workspace_id"), 160),
                targetSha = safe(value.optString("target_sha"), 128),
                argumentsSha256 = safe(value.optString("arguments_sha256"), 128),
                resolution = resolution,
                durability = durability,
                integrityMode = integrityMode,
                keyVersion = keyVersion,
                sideEffect = sideEffect,
                phase = phase,
                createdAt = value.optLong("created_at", 0L),
                updatedAt = value.optLong("updated_at", 0L),
                detail = safe(value.optString("detail"), MAX_DETAIL_CHARS),
                attempt = attempt,
                previousChainHash = safe(
                    value.optString("previous_chain_hash"),
                    CHAIN_HASH_CHARS,
                ),
                chainHash = safe(value.optString("chain_hash"), CHAIN_HASH_CHARS),
            )
        }

        private fun safe(value: String?, maxChars: Int): String? {
            return AgentRedactor.text(value, maxChars)
                ?.takeIf { it.isNotBlank() && it != "null" }
        }
    }

    private fun safe(value: String?, maxChars: Int): String? =
        AgentRedactor.text(value, maxChars)?.takeIf { it.isNotBlank() && it != "null" }
}

data class LedgerRecoverySnapshot(
    val health: LedgerHealth,
    val bootId: String,
    val unknownOperationIds: List<String>,
    val recoveryActions: Map<String, LedgerRecoveryAction>,
    val diagnostics: List<String>,
    val truncatedTrailingBytes: Boolean,
    val blocked: Boolean,
    val records: List<LedgerRecord>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("health", health.name)
        .put("boot_id", bootId)
        .put("unknown_operation_ids", unknownOperationIds)
        .put(
            "recovery_actions",
            JSONObject().apply {
                recoveryActions.forEach { (operationId, action) ->
                    put(operationId, action.name)
                }
            },
        )
        .put("diagnostics", diagnostics.take(32))
        .put("truncated_trailing_bytes", truncatedTrailingBytes)
        .put("blocked", blocked)
        .put(
            "records",
            records.takeLast(64).map { record ->
                record.toJson()
            },
        )
}

class LedgerBlockedException(message: String) : IllegalStateException(message)

class LedgerReplayBlockedException(message: String) : IllegalStateException(message)

private class LedgerDowngradeException : IllegalStateException(
    "LEDGER_DOWNGRADE_REQUIRES_EXPORT_IMPORT",
)

interface IntegrityKeyProvider {
    fun key(keyVersion: Int): Key?
}

class StaticHmacKeyProvider(secret: ByteArray) : IntegrityKeyProvider {
    private val key = SecretKeySpec(secret.copyOf(), "HmacSHA256")

    override fun key(keyVersion: Int): Key = key
}

class AndroidKeystoreHmacKeyProvider(
    private val aliasPrefix: String = "deep-agent-ledger",
) : IntegrityKeyProvider {
    override fun key(keyVersion: Int): Key? {
        val alias = aliasPrefix + "-v" + keyVersion
        return try {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = store.getKey(alias, null)
            if (existing != null) {
                existing
            } else {
                val generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
                    "AndroidKeyStore",
                )
                generator.init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                    )
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setUserAuthenticationRequired(false)
                        .build(),
                )
                generator.generateKey()
            }
        } catch (_: Exception) {
            null
        }
    }
}

class OperationLedger(
    private val file: File,
    private val keyProvider: IntegrityKeyProvider? = null,
    private val bootId: String = java.util.UUID.randomUUID().toString(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /** Последняя попытка каждой операции: `operationId -> attempt`. */
    private val latestAttempts = linkedMapOf<String, Int>()

    /** Все попытки: пара (`operationId`, attempt) -> запись. */
    private val recordsByAttempt = linkedMapOf<AttemptKey, LedgerRecord>()

    private var nextSequence = 1L
    private var lastChainHash: String? = null
    private var snapshot = LedgerRecoverySnapshot(
        health = LedgerHealth.CLEAN,
        bootId = bootId,
        unknownOperationIds = emptyList(),
        recoveryActions = emptyMap(),
        diagnostics = emptyList(),
        truncatedTrailingBytes = false,
        blocked = false,
        records = emptyList(),
    )

    init {
        recover()
    }

    @Synchronized
    fun snapshot(): LedgerRecoverySnapshot = snapshot

    /** Последняя попытка операции; null, если операция неизвестна. */
    @Synchronized
    fun record(operationId: String): LedgerRecord? {
        val attempt = latestAttempts[operationId] ?: return null
        return recordsByAttempt[AttemptKey(operationId, attempt)]
    }

    /** Все попытки операции в порядке возрастания номера. */
    @Synchronized
    fun attempts(operationId: String): List<LedgerRecord> {
        return recordsByAttempt.values
            .filter { it.operationId == operationId }
            .sortedBy { it.attempt }
    }

    @Synchronized
    fun attemptCount(operationId: String): Int = attempts(operationId).size

    /**
     * Число кадров в цепочке ledger. Каждый переход фазы добавляет кадр, поэтому
     * значение растёт быстрее числа операций: execute() пишет PREPARED, STARTED
     * и терминальный кадр. Нужно для проверок целостности и диагностики.
     */
    @Synchronized
    fun chainLength(): Long = nextSequence - 1L

    @Synchronized
    fun prepare(spec: LedgerOperationSpec): LedgerRecord {
        ensureWritable()
        if (latestAttempts.containsKey(spec.operationId)) {
            throw LedgerReplayBlockedException(
                "Operation ID уже присутствует в ledger; автоматический replay запрещён",
            )
        }
        return appendAttempt(spec, attempt = 1)
    }

    @Synchronized
    fun start(operationId: String): LedgerRecord {
        ensureWritable()
        val current = record(operationId)
            ?: throw LedgerBlockedException("PREPARED запись не найдена")
        require(current.phase == LedgerPhase.PREPARED) {
            "STARTED допускается только после PREPARED"
        }
        return appendTransition(current.copy(phase = LedgerPhase.STARTED))
    }

    @Synchronized
    fun begin(spec: LedgerOperationSpec): LedgerRecord {
        prepare(spec)
        return start(spec.operationId)
    }

    /**
     * Explicit recovery path for an IDEMPOTENT operation. This is never called
     * by recovery automatically; the caller must have re-checked the target.
     *
     * Предыдущая попытка не удаляется: новая добавляется под тем же
     * `operationId` с увеличенным номером, поэтому история UNKNOWN сохраняется.
     */
    @Synchronized
    fun retryUnknown(spec: LedgerOperationSpec): LedgerRecord {
        ensureWritable()
        require(spec.resolution == LedgerResolution.IDEMPOTENT) {
            "Только IDEMPOTENT операция допускает explicit retry"
        }
        val previous = record(spec.operationId)
            ?: throw LedgerReplayBlockedException("UNKNOWN операция не найдена")
        require(previous.phase == LedgerPhase.UNKNOWN) {
            "Explicit retry допускается только для UNKNOWN"
        }
        require(bindingMatches(previous, spec)) {
            "Binding explicit retry не совпадает с UNKNOWN записью"
        }
        // Повтор после re-check сразу переходит в STARTED: попытка уже
        // подтверждена вызывающей стороной, отдельный PREPARED шаг не нужен.
        return appendTransition(
            appendAttempt(spec, attempt = previous.attempt + 1)
                .copy(phase = LedgerPhase.STARTED),
        )
    }

    @Synchronized
    fun terminal(
        operationId: String,
        phase: LedgerPhase,
        detail: String? = null,
    ): LedgerRecord {
        require(
            phase == LedgerPhase.SUCCEEDED ||
                phase == LedgerPhase.FAILED ||
                phase == LedgerPhase.CANCELLED ||
                phase == LedgerPhase.UNKNOWN,
        ) {
            "Недопустимая terminal phase ledger"
        }
        ensureWritable()
        val current = record(operationId)
            ?: throw LedgerBlockedException("Операция ledger не найдена")
        require(current.phase == LedgerPhase.STARTED) {
            "Terminal запись допускается только после STARTED"
        }
        return appendTransition(current.copy(phase = phase, detail = detail))
    }

    fun <T> execute(
        spec: LedgerOperationSpec,
        effect: () -> T,
    ): T {
        begin(spec)
        return try {
            val result = effect()
            terminal(spec.operationId, LedgerPhase.SUCCEEDED, "effect completed")
            result
        } catch (error: Throwable) {
            runCatching {
                terminal(
                    spec.operationId,
                    LedgerPhase.UNKNOWN,
                    "effect outcome unknown: " + (error.message ?: error::class.java.simpleName),
                )
            }
            throw error
        }
    }

    @Synchronized
    fun recover(): LedgerRecoverySnapshot {
        latestAttempts.clear()
        recordsByAttempt.clear()
        nextSequence = 1L
        lastChainHash = null
        if (!file.exists() || file.length() == 0L) {
            snapshot = snapshot.copy(
                health = LedgerHealth.CLEAN,
                unknownOperationIds = emptyList(),
                recoveryActions = emptyMap(),
                diagnostics = emptyList(),
                truncatedTrailingBytes = false,
                blocked = false,
                records = emptyList(),
            )
            return snapshot
        }

        val bytes = try {
            FileInputStream(file).use { input ->
                BufferedInputStream(input).use { it.readBytes() }
            }
        } catch (error: Exception) {
            snapshot = snapshot.copy(
                health = LedgerHealth.CORRUPT,
                diagnostics = listOf("LEDGER_READ_FAILED"),
                blocked = true,
            )
            return snapshot
        }

        var offset = 0
        var lastValidOffset = 0
        var previousSequence = 0L
        var trailingTruncated = false
        var blocked = false
        var keyUnavailable = false
        var previousChainHash: String? = null
        val diagnostics = mutableListOf<String>()

        while (offset < bytes.size) {
            val frame = readFrameAt(bytes, offset)
            if (frame.valid && frame.record != null) {
                val record = frame.record
                if (
                    record.sequence <= previousSequence ||
                    (previousSequence == 0L && record.sequence != 1L) ||
                    (previousSequence > 0L && record.sequence != previousSequence + 1L)
                ) {
                    blocked = true
                    diagnostics += "LEDGER_SEQUENCE_GAP"
                    break
                }
                if (!chainLinkMatches(record, previousChainHash, chainKeyFor(record))) {
                    blocked = true
                    diagnostics += "LEDGER_CHAIN_HASH_MISMATCH"
                    break
                }
                recordsByAttempt[AttemptKey(record.operationId, record.attempt)] = record
                latestAttempts[record.operationId] = maxOf(
                    latestAttempts[record.operationId] ?: 0,
                    record.attempt,
                )
                previousSequence = record.sequence
                previousChainHash = record.chainHash
                lastChainHash = record.chainHash
                offset = frame.nextOffset
                lastValidOffset = offset
                nextSequence = record.sequence + 1L
                continue
            }

            if (frame.error == ReadError.KEY_UNAVAILABLE) {
                keyUnavailable = true
                diagnostics += "LEDGER_KEY_UNAVAILABLE"
                break
            }

            if (frame.error == ReadError.DOWNGRADE) {
                blocked = true
                diagnostics += "LEDGER_DOWNGRADE_REQUIRES_EXPORT_IMPORT"
                break
            }

            if (hasValidFrameAfter(bytes, offset + 1)) {
                blocked = true
                diagnostics += "LEDGER_MIDDLE_CORRUPTION"
            } else {
                trailingTruncated = true
                diagnostics += "LEDGER_TRAILING_RECORD_TRUNCATED"
                truncate(lastValidOffset)
            }
            break
        }

        if (blocked || keyUnavailable) {
            snapshot = buildSnapshot(
                health = if (keyUnavailable) {
                    LedgerHealth.KEY_UNAVAILABLE
                } else {
                    LedgerHealth.CORRUPT
                },
                diagnostics = diagnostics,
                truncatedTrailing = false,
                blocked = true,
            )
            return snapshot
        }

        val pending = latestRecords()
            .filter {
                it.phase == LedgerPhase.PREPARED || it.phase == LedgerPhase.STARTED
            }
        if (pending.isNotEmpty()) {
            pending.forEach { record ->
                appendTransition(
                    record.copy(
                        phase = LedgerPhase.UNKNOWN,
                        detail = "Interrupted " + record.phase.name +
                            "; automatic continuation prohibited",
                    ),
                )
            }
            diagnostics += "LEDGER_PENDING_OPERATIONS_UNKNOWN"
        }

        snapshot = buildSnapshot(
            health = if (pending.isNotEmpty() || trailingTruncated) {
                LedgerHealth.RECOVERY_REQUIRED
            } else {
                LedgerHealth.CLEAN
            },
            diagnostics = diagnostics,
            truncatedTrailing = trailingTruncated,
            blocked = false,
        )
        return snapshot
    }

    private fun bindingMatches(
        previous: LedgerRecord,
        spec: LedgerOperationSpec,
    ): Boolean {
        return previous.operation == spec.operation &&
            previous.sessionId == spec.sessionId &&
            previous.workspaceId == spec.workspaceId &&
            previous.targetSha == spec.targetSha &&
            previous.argumentsSha256 == spec.argumentsSha256 &&
            previous.resolution == spec.resolution &&
            previous.integrityMode == spec.integrityMode &&
            previous.keyVersion == spec.keyVersion
    }

    private fun appendAttempt(
        spec: LedgerOperationSpec,
        attempt: Int,
    ): LedgerRecord {
        val now = clock()
        return append(
            LedgerRecord(
                schemaVersion = LedgerRecord.SCHEMA_VERSION,
                sequence = 0L,
                bootId = bootId,
                operationId = spec.operationId,
                operation = spec.operation,
                sessionId = spec.sessionId,
                workspaceId = spec.workspaceId,
                targetSha = spec.targetSha,
                argumentsSha256 = spec.argumentsSha256,
                resolution = spec.resolution,
                durability = spec.durability,
                integrityMode = spec.integrityMode,
                keyVersion = spec.keyVersion,
                sideEffect = spec.sideEffect,
                phase = LedgerPhase.PREPARED,
                createdAt = now,
                updatedAt = now,
                attempt = attempt,
            ),
        )
    }

    private fun appendTransition(current: LedgerRecord): LedgerRecord {
        return append(
            current.copy(
                sequence = 0L,
                bootId = bootId,
                updatedAt = clock(),
            ),
        )
    }

    @Synchronized
    private fun append(record: LedgerRecord): LedgerRecord {
        ensureParent()
        val normalized = record.copy(
            sequence = nextSequence,
            schemaVersion = LedgerRecord.SCHEMA_VERSION,
            bootId = record.bootId.takeIf { it.isNotBlank() } ?: bootId,
            detail = AgentRedactor.text(record.detail, LedgerRecord.MAX_DETAIL_CHARS),
            previousChainHash = lastChainHash,
        )
        val sealed = normalized.copy(
            chainHash = chainHashFor(normalized, chainKeyFor(normalized)),
        )
        val payload = sealed.toJson().toString().toByteArray(Charsets.UTF_8)
        require(payload.size <= MAX_PAYLOAD_BYTES) {
            "Ledger payload превышает лимит"
        }
        val checksum = checksum(
            mode = sealed.integrityMode,
            keyVersion = sealed.keyVersion,
            payload = payload,
        )
        FileOutputStream(file, true).use { raw ->
            DataOutputStream(BufferedOutputStream(raw)).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(FORMAT_VERSION)
                output.writeInt(sealed.integrityMode.ordinal)
                output.writeInt(sealed.keyVersion)
                output.writeInt(payload.size)
                output.writeInt(checksum.size)
                output.write(checksum)
                output.write(payload)
                output.flush()
                if (sealed.durability == LedgerDurability.FULL) {
                    raw.fd.sync()
                }
            }
        }
        recordsByAttempt[AttemptKey(sealed.operationId, sealed.attempt)] = sealed
        latestAttempts[sealed.operationId] = maxOf(
            latestAttempts[sealed.operationId] ?: 0,
            sealed.attempt,
        )
        nextSequence = sealed.sequence + 1L
        lastChainHash = sealed.chainHash
        refreshSnapshotRecords()
        return sealed
    }

    private fun buildSnapshot(
        health: LedgerHealth,
        diagnostics: List<String>,
        truncatedTrailing: Boolean,
        blocked: Boolean,
    ): LedgerRecoverySnapshot {
        val metadata = recoveryMetadata()
        return LedgerRecoverySnapshot(
            health = health,
            bootId = bootId,
            unknownOperationIds = metadata.unknownOperationIds,
            recoveryActions = metadata.recoveryActions,
            diagnostics = diagnostics.distinct(),
            truncatedTrailingBytes = truncatedTrailing,
            blocked = blocked,
            records = latestRecords(),
        )
    }

    private fun refreshSnapshotRecords() {
        val metadata = recoveryMetadata()
        snapshot = snapshot.copy(
            bootId = bootId,
            unknownOperationIds = metadata.unknownOperationIds,
            recoveryActions = metadata.recoveryActions,
            records = latestRecords(),
        )
    }

    private fun latestRecords(): List<LedgerRecord> {
        return latestAttempts.entries.mapNotNull { (operationId, attempt) ->
            recordsByAttempt[AttemptKey(operationId, attempt)]
        }
    }

    private fun recoveryMetadata(): RecoveryMetadata {
        val unknown = latestRecords().filter { it.phase == LedgerPhase.UNKNOWN }
        return RecoveryMetadata(
            unknownOperationIds = unknown.map { it.operationId }.distinct(),
            recoveryActions = unknown.associate {
                it.operationId to recoveryAction(it.resolution)
            },
        )
    }

    private data class RecoveryMetadata(
        val unknownOperationIds: List<String>,
        val recoveryActions: Map<String, LedgerRecoveryAction>,
    )

    private fun recoveryAction(resolution: LedgerResolution): LedgerRecoveryAction {
        return when (resolution) {
            LedgerResolution.QUERYABLE -> LedgerRecoveryAction.RECHECK_REQUIRED
            LedgerResolution.IDEMPOTENT ->
                LedgerRecoveryAction.EXPLICIT_RETRY_WITH_SAME_OPERATION_ID
            LedgerResolution.BLIND -> LedgerRecoveryAction.MANUAL_RECONCILIATION
        }
    }

    private fun ensureWritable() {
        if (snapshot.blocked || snapshot.health == LedgerHealth.CORRUPT ||
            snapshot.health == LedgerHealth.KEY_UNAVAILABLE
        ) {
            throw LedgerBlockedException(
                "Ledger заблокирован; автоматическое продолжение запрещено",
            )
        }
    }

    private fun ensureParent() {
        file.parentFile?.mkdirs()
    }

    private fun truncate(length: Int) {
        if (!file.exists()) return
        RandomAccessFile(file, "rw").use {
            it.setLength(length.toLong())
            it.fd.sync()
        }
    }

    /**
     * Проверяет связь кадра с предыдущим и целостность самого chain hash.
     *
     * Одного сравнения ссылок недостаточно: подмена payload не обнаруживается
     * сравнением, если атакующий может пересчитать незашифрованный хеш. Поэтому
     * chain hash пересчитывается. Если доступен ключ, пересчёт идёт через HMAC,
     * и подделать цепочку без ключа нельзя. Записи формата schema 1 не содержат
     * chain hash: они остаются читаемыми, потому что записаны до введения
     * цепочки, и не считаются подделкой.
     */
    private fun chainLinkMatches(
        record: LedgerRecord,
        expectedPrevious: String?,
        expectedKey: ByteArray?,
    ): Boolean {
        // Запись schema 1 была создана до введения цепочки: у неё нет chain
        // hash по построению, и это не подделка.
        if (record.schemaVersion < 2) return true
        // Начиная со schema 2 отсутствие chain hash — это повреждение или
        // подделка: `safe()` превращает отсутствующее поле в null, поэтому
        // доверять такой записи нельзя.
        val declared = record.chainHash ?: return false
        if (chainHashFor(record, expectedKey) != declared) return false
        return record.previousChainHash == expectedPrevious
    }

    private fun chainHashFor(record: LedgerRecord, key: ByteArray?): String {
        val canonical = canonicalChainInput(record)
        val digest = if (key == null) {
            java.security.MessageDigest.getInstance("SHA-256").digest(canonical)
        } else {
            Mac.getInstance("HmacSHA256")
                .apply { init(SecretKeySpec(key, "HmacSHA256")) }
                .doFinal(canonical)
        }
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun canonicalChainInput(record: LedgerRecord): ByteArray {
        return buildString {
            append(record.sequence).append('|')
            append(record.operationId).append('|')
            append(record.attempt).append('|')
            append(record.operation).append('|')
            append(record.phase.name).append('|')
            append(record.sessionId ?: "").append('|')
            append(record.workspaceId ?: "").append('|')
            append(record.targetSha ?: "").append('|')
            append(record.argumentsSha256 ?: "").append('|')
            append(record.resolution.name).append('|')
            append(record.integrityMode.name).append('|')
            append(record.keyVersion).append('|')
            append(record.createdAt).append('|')
            append(record.updatedAt).append('|')
            append(record.detail ?: "").append('|')
            append(record.previousChainHash ?: "")
        }.toByteArray(Charsets.UTF_8)
    }

    /**
     * Ключ цепочки. Берётся тот же провайдер, что и для HMAC-payload, но с
     * префиксом версии, чтобы chain hash не совпадал с payload-HMAC. Для
     * CRC32C-режима ключа нет, поэтому цепочка там обнаруживает случайную
     * порчу, но не подмену автором записи.
     */
    private fun chainKeyFor(record: LedgerRecord): ByteArray? {
        if (record.integrityMode != LedgerIntegrityMode.HMAC_SHA256) return null
        val encoded = keyProvider?.key(record.keyVersion)?.encoded ?: return null
        return CHAIN_KEY_PREFIX.toByteArray(Charsets.UTF_8) + encoded
    }

    private data class FrameRead(
        val valid: Boolean,
        val nextOffset: Int,
        val record: LedgerRecord?,
        val error: ReadError?,
    )

    private enum class ReadError {
        INCOMPLETE,
        INVALID,
        CHECKSUM,
        KEY_UNAVAILABLE,
        DOWNGRADE,
    }

    private fun readFrameAt(bytes: ByteArray, offset: Int): FrameRead {
        if (bytes.size - offset < FRAME_HEADER_BYTES) {
            return FrameRead(false, offset, null, ReadError.INCOMPLETE)
        }
        return try {
            val input = DataInputStream(
                BufferedInputStream(
                    ByteArrayInputStream(bytes, offset, bytes.size - offset),
                ),
            )
            val magic = input.readInt()
            val formatVersion = input.readInt()
            val modeOrdinal = input.readInt()
            val keyVersion = input.readInt()
            val payloadLength = input.readInt()
            val checksumLength = input.readInt()
            val mode = LedgerIntegrityMode.values().getOrNull(modeOrdinal)
                ?: return FrameRead(false, offset, null, ReadError.INVALID)
            if (magic != MAGIC) {
                return FrameRead(false, offset, null, ReadError.INVALID)
            }
            if (formatVersion != FORMAT_VERSION) {
                return FrameRead(
                    false,
                    offset,
                    null,
                    if (formatVersion < FORMAT_VERSION) {
                        ReadError.DOWNGRADE
                    } else {
                        ReadError.INVALID
                    },
                )
            }
            if (
                payloadLength !in 1..MAX_PAYLOAD_BYTES ||
                checksumLength !in 1..MAX_CHECKSUM_BYTES ||
                checksumLength != expectedChecksumLength(mode)
            ) {
                return FrameRead(false, offset, null, ReadError.INVALID)
            }
            val total = FRAME_HEADER_BYTES + checksumLength + payloadLength
            if (bytes.size - offset < total) {
                return FrameRead(false, offset, null, ReadError.INCOMPLETE)
            }
            val checksum = ByteArray(checksumLength)
            input.readFully(checksum)
            val payload = ByteArray(payloadLength)
            input.readFully(payload)
            val expected = try {
                checksum(mode, keyVersion, payload)
            } catch (_: IntegrityKeyUnavailableException) {
                return FrameRead(false, offset, null, ReadError.KEY_UNAVAILABLE)
            }
            if (!checksum.contentEquals(expected)) {
                return FrameRead(false, offset, null, ReadError.CHECKSUM)
            }
            val record = try {
                LedgerRecord.fromJson(JSONObject(String(payload, Charsets.UTF_8)))
            } catch (_: LedgerDowngradeException) {
                return FrameRead(false, offset, null, ReadError.DOWNGRADE)
            }
            if (
                record.integrityMode != mode ||
                record.keyVersion != keyVersion
            ) {
                return FrameRead(false, offset, null, ReadError.INVALID)
            }
            FrameRead(true, offset + total, record, null)
        } catch (_: IntegrityKeyUnavailableException) {
            FrameRead(false, offset, null, ReadError.KEY_UNAVAILABLE)
        } catch (_: Exception) {
            FrameRead(false, offset, null, ReadError.INVALID)
        }
    }

    private fun hasValidFrameAfter(bytes: ByteArray, start: Int): Boolean {
        val magic = ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC)
            .array()
        var index = start
        while (index <= bytes.size - magic.size) {
            if (bytes.copyOfRange(index, index + magic.size).contentEquals(magic)) {
                val frame = readFrameAt(bytes, index)
                if (frame.valid && frame.record != null) return true
            }
            index += 1
        }
        return false
    }

    private fun expectedChecksumLength(mode: LedgerIntegrityMode): Int {
        return when (mode) {
            LedgerIntegrityMode.CRC32C -> CRC32C_BYTES
            LedgerIntegrityMode.HMAC_SHA256 -> HMAC_BYTES
        }
    }

    private fun checksum(
        mode: LedgerIntegrityMode,
        keyVersion: Int,
        payload: ByteArray,
    ): ByteArray {
        return when (mode) {
            LedgerIntegrityMode.CRC32C -> {
                val crc = java.util.zip.CRC32C()
                crc.update(payload)
                ByteBuffer.allocate(CRC32C_BYTES)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putInt(crc.value.toInt())
                    .array()
            }

            LedgerIntegrityMode.HMAC_SHA256 -> {
                val key = keyProvider?.key(keyVersion)
                    ?: throw IntegrityKeyUnavailableException()
                Mac.getInstance("HmacSHA256").apply {
                    init(key)
                }.doFinal(payload)
            }
        }
    }

    private class IntegrityKeyUnavailableException : IllegalStateException()

    private data class AttemptKey(
        val operationId: String,
        val attempt: Int,
    )

    companion object {
        private const val MAGIC = 0x44414C47
        private const val FORMAT_VERSION = 1
        private const val FRAME_HEADER_BYTES = 24
        private const val MAX_PAYLOAD_BYTES = 64 * 1024
        private const val MAX_CHECKSUM_BYTES = 64
        private const val CRC32C_BYTES = 4
        private const val HMAC_BYTES = 32
        private const val CHAIN_KEY_PREFIX = "ledger-chain-v1:"
    }
}
