package dev.deepagent.mobile.agent.github

import dev.deepagent.mobile.agent.model.ActionsArtifactRequest
import dev.deepagent.mobile.agent.model.ActionsArtifactState
import dev.deepagent.mobile.agent.model.ActionsJobState
import dev.deepagent.mobile.agent.model.ActionsOperationState
import dev.deepagent.mobile.agent.model.ActionsOperationStatus
import dev.deepagent.mobile.agent.model.ActionsRunRequest
import dev.deepagent.mobile.agent.model.ActionsStepState
import dev.deepagent.mobile.agent.model.AgentRedactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipInputStream

data class GitHubActionsRequest(
    val token: String,
    val repository: String,
    val workflow: String,
    val ref: String = "main",
    val inputs: Map<String, String> = emptyMap(),
)

sealed interface GitHubActionsResult {
    data object Dispatched : GitHubActionsResult
    data class NotConfigured(val message: String) : GitHubActionsResult
    data class Failed(val message: String) : GitHubActionsResult
}

data class VerifiedArtifact(
    val artifactId: Long,
    val fileName: String,
    val sourceSha: String,
    val checksum: String,
    val bytes: ByteArray,
    val contentType: String,
)

sealed interface ArtifactDownloadResult {
    data class Verified(val artifact: VerifiedArtifact) : ArtifactDownloadResult
    data class Failed(val message: String, val errorCode: String) : ArtifactDownloadResult
    data class Unknown(val message: String, val errorCode: String) : ArtifactDownloadResult
}

class GitHubActionsClient {

    suspend fun dispatch(request: GitHubActionsRequest): GitHubActionsResult =
        withContext(Dispatchers.IO) {
            val validation = validateRequest(
                token = request.token,
                repository = request.repository,
                workflow = request.workflow,
                ref = request.ref,
            )
            if (validation != null) {
                return@withContext GitHubActionsResult.NotConfigured(validation)
            }

            try {
                val response = apiRequest(
                    token = request.token,
                    method = "POST",
                    endpoint = dispatchEndpoint(
                        repository = request.repository.trim(),
                        workflow = request.workflow.trim(),
                    ),
                    body = dispatchBody(request.ref.trim(), request.inputs),
                )
                if (response.status == HttpURLConnection.HTTP_NO_CONTENT) {
                    GitHubActionsResult.Dispatched
                } else {
                    GitHubActionsResult.Failed(
                        "GitHub Actions HTTP " + response.status +
                            response.errorSuffix(),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                GitHubActionsResult.Failed(
                    safeError(error.message, "Не удалось запустить workflow"),
                )
            }
        }

    suspend fun observe(
        request: ActionsRunRequest,
        onState: (ActionsOperationState) -> Unit = {},
    ): ActionsOperationState = withContext(Dispatchers.IO) {
        val baseState = ActionsOperationState(
            sessionId = request.sessionId,
            repository = request.repository.trim().takeIf { it.isNotBlank() },
            workflow = request.workflow.trim().takeIf { it.isNotBlank() },
            ref = request.ref.trim().takeIf { it.isNotBlank() },
        )
        val validation = validateRequest(
            token = request.token,
            repository = request.repository,
            workflow = request.workflow,
            ref = request.ref,
        )
        if (validation != null) {
            val failed = baseState.copy(
                status = ActionsOperationStatus.FAILED,
                summary = validation,
                errorCode = "ACTIONS_INVALID_ARGUMENTS",
            )
            onState(failed)
            return@withContext failed
        }

        var current = baseState
        fun publish(next: ActionsOperationState): ActionsOperationState {
            current = next.copy(updatedAt = System.currentTimeMillis())
            try {
                onState(current)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A diagnostic UI callback must not change provider state.
            }
            return current
        }

        try {
            publish(
                current.copy(
                    status = ActionsOperationStatus.DISPATCHING,
                    summary = "Отправка workflow dispatch",
                    errorCode = null,
                ),
            )
            val dispatchedAt = System.currentTimeMillis()
            val dispatchResponse = apiRequest(
                token = request.token,
                method = "POST",
                endpoint = dispatchEndpoint(
                    request.repository.trim(),
                    request.workflow.trim(),
                ),
                body = dispatchBody(
                    request.ref.trim(),
                    request.inputs + mapOf(
                        "agent_session_id" to (request.sessionId ?: "unknown"),
                    ),
                ),
            )
            if (dispatchResponse.status != HttpURLConnection.HTTP_NO_CONTENT) {
                return@withContext publish(
                    current.copy(
                        status = ActionsOperationStatus.FAILED,
                        summary = "GitHub Actions dispatch отклонён" +
                            dispatchResponse.errorSuffix(),
                        errorCode = "ACTIONS_DISPATCH_FAILED",
                    ),
                )
            }

            publish(
                current.copy(
                    status = ActionsOperationStatus.DISCOVERING_RUN,
                    summary = "Поиск workflow run после dispatch",
                ),
            )
            val deadline = System.currentTimeMillis() +
                request.pollTimeoutMs.coerceIn(
                    MIN_POLL_TIMEOUT_MS,
                    MAX_POLL_TIMEOUT_MS,
                )
            var run = discoverRun(request, dispatchedAt, deadline)
                ?: return@withContext publish(
                    current.copy(
                        status = ActionsOperationStatus.UNKNOWN,
                        summary = "Workflow dispatch принят, но run не найден до deadline",
                        errorCode = "ACTIONS_RUN_NOT_FOUND",
                    ),
                )

            publish(run.toState(current, ActionsOperationStatus.RUNNING))

            while (!run.isCompleted) {
                currentCoroutineContext().ensureActive()
                if (System.currentTimeMillis() >= deadline) {
                    return@withContext publish(
                        current.copy(
                            status = ActionsOperationStatus.UNKNOWN,
                            summary = "Workflow run не завершился до deadline",
                            errorCode = "ACTIONS_RUN_TIMEOUT",
                        ),
                    )
                }
                delay(
                    request.pollIntervalMs.coerceIn(
                        MIN_POLL_INTERVAL_MS,
                        MAX_POLL_INTERVAL_MS,
                    ),
                )
                run = fetchRun(
                    request.token,
                    request.repository.trim(),
                    run.id,
                )
                publish(run.toState(current, ActionsOperationStatus.RUNNING))
            }

            val jobs = fetchJobs(
                request.token,
                request.repository.trim(),
                run.id,
            )
            publish(
                current.copy(
                    status = ActionsOperationStatus.RUNNING,
                    jobs = jobs.map { it.toState() },
                    summary = "Jobs и steps получены",
                ),
            )
            val failedJob = jobs.firstOrNull { job ->
                job.conclusion != null && job.conclusion != "success"
            }
            val failedStep = failedJob?.steps
                ?.firstOrNull { step ->
                    step.conclusion != null && step.conclusion != "success"
                }
                ?.let { (failedJob.name + " / " + it.name).take(MAX_FAILED_STEP_CHARS) }
            val logs = failedJob?.let {
                fetchJobLogs(
                    request.token,
                    request.repository.trim(),
                    it.id,
                )
            }
            val artifacts = fetchArtifacts(
                request.token,
                request.repository.trim(),
                run.id,
            )
            val sourceMatches = request.expectedCommitSha?.let { expected ->
                run.headSha.equals(expected.trim(), ignoreCase = true)
            } ?: true
            val hasAndroidArtifact = artifacts.any {
                it.name.lowercase().endsWith(".apk") ||
                    it.name.lowercase().endsWith(".aab") ||
                    it.name.contains("apk", ignoreCase = true) ||
                    it.name.contains("aab", ignoreCase = true)
            }
            val finalStatus = when {
                !sourceMatches -> ActionsOperationStatus.FAILED
                run.conclusion != "success" -> ActionsOperationStatus.FAILED
                !hasAndroidArtifact -> ActionsOperationStatus.FAILED
                else -> ActionsOperationStatus.SUCCEEDED
            }
            val finalSummary = when {
                !sourceMatches -> "Workflow завершён, но source SHA не совпал"
                finalStatus == ActionsOperationStatus.SUCCEEDED ->
                    "Workflow успешно завершён; artifacts доступны для проверки"
                failedStep != null -> "Workflow завершён с ошибкой на шаге " + failedStep
                else -> "Workflow завершён с conclusion=" + (run.conclusion ?: "unknown")
            }
            publish(
                current.copy(
                    status = finalStatus,
                    runId = run.id,
                    runNumber = run.number,
                    headSha = run.headSha,
                    conclusion = run.conclusion,
                    failedStep = failedStep,
                    jobs = jobs.map { it.toState() },
                    artifacts = artifacts.map { it.toState(run.headSha) },
                    redactedLogs = logs,
                    summary = finalSummary,
                    errorCode = when {
                        !sourceMatches -> "ACTIONS_SOURCE_SHA_MISMATCH"
                        finalStatus == ActionsOperationStatus.SUCCEEDED -> null
                        !hasAndroidArtifact -> "ACTIONS_ARTIFACT_NOT_FOUND"
                        else -> "ACTIONS_RUN_FAILED"
                    },
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            publish(
                current.copy(
                    status = ActionsOperationStatus.UNKNOWN,
                    summary = safeError(
                        error.message,
                        "Actions завершился без подтверждённого результата",
                    ),
                    errorCode = if (error is ActionsHttpException) {
                        "ACTIONS_HTTP_UNKNOWN"
                    } else {
                        "ACTIONS_PROVIDER_UNKNOWN"
                    },
                ),
            )
        }
    }

    suspend fun downloadAndVerifyArtifact(
        request: ActionsArtifactRequest,
    ): ArtifactDownloadResult = withContext(Dispatchers.IO) {
        val validation = validateRequest(
            token = request.token,
            repository = request.repository,
            workflow = "artifact",
            ref = "artifact",
        )
        if (validation != null || request.runId <= 0L || request.artifactId <= 0L) {
            return@withContext ArtifactDownloadResult.Failed(
                validation ?: "Некорректный artifact или run id",
                "ARTIFACT_INVALID_ARGUMENTS",
            )
        }
        if (!SHA_PATTERN.matches(request.expectedCommitSha.trim())) {
            return@withContext ArtifactDownloadResult.Failed(
                "Для проверки artifact нужен ожидаемый commit SHA",
                "ARTIFACT_SOURCE_SHA_REQUIRED",
            )
        }

        try {
            val repository = request.repository.trim()
            val metadataResponse = apiRequest(
                request.token,
                "GET",
                artifactEndpoint(repository, request.artifactId),
            )
            if (metadataResponse.status != HttpURLConnection.HTTP_OK) {
                return@withContext ArtifactDownloadResult.Failed(
                    "Не удалось получить metadata artifact" +
                        metadataResponse.errorSuffix(),
                    "ARTIFACT_METADATA_FAILED",
                )
            }
            val metadata = JSONObject(metadataResponse.body.toUtf8())
            val name = metadata.optString("name").trim()
            if (metadata.optBoolean("expired", false)) {
                return@withContext ArtifactDownloadResult.Failed(
                    "Artifact истёк",
                    "ARTIFACT_EXPIRED",
                )
            }
            val sourceSha = metadata.optJSONObject("workflow_run")
                ?.optString("head_sha")
                ?.trim()
                .orEmpty()
            if (!SHA_PATTERN.matches(sourceSha)) {
                return@withContext ArtifactDownloadResult.Unknown(
                    "Artifact metadata не содержит подтверждённый source SHA",
                    "ARTIFACT_SOURCE_SHA_UNKNOWN",
                )
            }
            if (!sourceSha.equals(request.expectedCommitSha.trim(), ignoreCase = true)) {
                return@withContext ArtifactDownloadResult.Failed(
                    "Source SHA artifact не совпал с ожидаемым",
                    "ARTIFACT_SOURCE_SHA_MISMATCH",
                )
            }
            val archive = downloadWithRedirects(
                request.token,
                artifactZipEndpoint(repository, request.artifactId),
                MAX_ARCHIVE_BYTES,
            ).bytes
            ArtifactDownloadResult.Verified(
                extractPayload(
                    archive = archive,
                    artifactId = request.artifactId,
                    sourceSha = sourceSha,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ArtifactVerificationException) {
            ArtifactDownloadResult.Failed(
                safeError(error.message, "Artifact не прошёл проверку"),
                error.errorCode,
            )
        } catch (error: Exception) {
            ArtifactDownloadResult.Unknown(
                safeError(error.message, "Artifact не удалось проверить"),
                "ARTIFACT_VERIFY_UNKNOWN",
            )
        }
    }

    private suspend fun discoverRun(
        request: ActionsRunRequest,
        dispatchedAt: Long,
        deadline: Long,
    ): RunInfo? {
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            val response = apiRequest(
                request.token,
                "GET",
                runsEndpoint(
                    request.repository.trim(),
                    request.workflow.trim(),
                    request.ref.trim(),
                ),
            )
            if (response.status != HttpURLConnection.HTTP_OK) {
                throw ActionsHttpException(
                    response.status,
                    "Не удалось получить список workflow runs" +
                        response.errorSuffix(),
                )
            }
            val candidate = parseRuns(response.body.toUtf8())
                .filter { run ->
                    run.createdAt >= dispatchedAt - DISCOVERY_SKEW_MS &&
                        (request.expectedCommitSha == null ||
                            run.headSha.equals(
                                request.expectedCommitSha.trim(),
                                ignoreCase = true,
                            ))
                }
                .maxByOrNull { it.id }
            if (candidate != null) return candidate
            delay(
                request.pollIntervalMs.coerceIn(
                    MIN_POLL_INTERVAL_MS,
                    MAX_POLL_INTERVAL_MS,
                ),
            )
        }
        return null
    }

    private fun fetchRun(
        token: String,
        repository: String,
        runId: Long,
    ): RunInfo {
        val response = apiRequest(
            token,
            "GET",
            runEndpoint(repository, runId),
        )
        if (response.status != HttpURLConnection.HTTP_OK) {
            throw ActionsHttpException(
                response.status,
                "Не удалось получить workflow run" + response.errorSuffix(),
            )
        }
        return parseRun(JSONObject(response.body.toUtf8()))
    }

    private fun fetchJobs(
        token: String,
        repository: String,
        runId: Long,
    ): List<JobInfo> {
        val response = apiRequest(
            token,
            "GET",
            jobsEndpoint(repository, runId),
        )
        if (response.status != HttpURLConnection.HTTP_OK) {
            throw ActionsHttpException(
                response.status,
                "Не удалось получить jobs workflow" + response.errorSuffix(),
            )
        }
        val json = JSONObject(response.body.toUtf8())
        val array = json.optJSONArray("jobs") ?: JSONArray()
        return buildList {
            for (index in 0 until array.length().coerceAtMost(MAX_JOBS)) {
                array.optJSONObject(index)?.let { add(parseJob(it)) }
            }
        }
    }

    private fun fetchArtifacts(
        token: String,
        repository: String,
        runId: Long,
    ): List<ArtifactInfo> {
        val response = apiRequest(
            token,
            "GET",
            artifactsEndpoint(repository, runId),
        )
        if (response.status != HttpURLConnection.HTTP_OK) {
            throw ActionsHttpException(
                response.status,
                "Не удалось получить artifacts workflow" + response.errorSuffix(),
            )
        }
        val json = JSONObject(response.body.toUtf8())
        val array = json.optJSONArray("artifacts") ?: JSONArray()
        return buildList {
            for (index in 0 until array.length().coerceAtMost(MAX_ARTIFACTS)) {
                array.optJSONObject(index)?.let { add(parseArtifact(it)) }
            }
        }
    }

    private fun fetchJobLogs(
        token: String,
        repository: String,
        jobId: Long,
    ): String {
        val response = downloadWithRedirects(
            token,
            logsEndpoint(repository, jobId),
            MAX_LOG_CHARS * 4,
        )
        return AgentRedactor.text(response.bytes.toUtf8(), MAX_LOG_CHARS).orEmpty()
    }

    private fun extractPayload(
        archive: ByteArray,
        artifactId: Long,
        sourceSha: String,
    ): VerifiedArtifact {
        var payload: Pair<String, ByteArray>? = null
        val checksums = mutableMapOf<String, String>()
        var provenanceSha: String? = null
        var entryCount = 0
        var totalEntryBytes = 0L
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount += 1
                if (entryCount > MAX_ARCHIVE_ENTRIES) {
                    throw ArtifactVerificationException(
                        "Artifact содержит слишком много ZIP entries",
                        "ARTIFACT_ENTRY_COUNT_LIMIT",
                    )
                }
                requireSafeArchivePath(entry.name)
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val bytes = readEntryLimited(zip, MAX_ENTRY_BYTES)
                totalEntryBytes += bytes.size.toLong()
                if (totalEntryBytes > MAX_TOTAL_ENTRY_BYTES) {
                    throw ArtifactVerificationException(
                        "Суммарный распакованный размер artifact превышает лимит",
                        "ARTIFACT_TOTAL_ENTRY_BYTES_LIMIT",
                    )
                }
                val lower = entry.name.lowercase()
                when {
                    lower.endsWith(".apk") || lower.endsWith(".aab") -> {
                        if (payload != null) {
                            throw ArtifactVerificationException(
                                "Artifact содержит несколько APK/AAB",
                                "ARTIFACT_AMBIGUOUS_PAYLOAD",
                            )
                        }
                        if (bytes.isEmpty() || bytes.size > MAX_PAYLOAD_BYTES) {
                            throw ArtifactVerificationException(
                                "Размер APK/AAB вне допустимого диапазона",
                                "ARTIFACT_PAYLOAD_SIZE_INVALID",
                            )
                        }
                        payload = entry.name to bytes
                    }
                    lower.endsWith(".sha256") -> {
                        val match = SHA_LINE_PATTERN.find(bytes.toUtf8().trim())
                            ?: throw ArtifactVerificationException(
                                "Checksum sidecar имеет неверный формат",
                                "ARTIFACT_CHECKSUM_FORMAT_INVALID",
                            )
                        checksums[entry.name.substringBeforeLast(".sha256")] =
                            match.groupValues[1].lowercase()
                    }
                    lower.endsWith("deep-agent-provenance.json") -> {
                        provenanceSha = runCatching {
                            JSONObject(bytes.toUtf8())
                                .optString("source_sha")
                                .trim()
                        }.getOrNull()
                    }
                }
                zip.closeEntry()
            }
        }

        val selected = payload ?: throw ArtifactVerificationException(
            "В artifact нет APK/AAB",
            "ARTIFACT_PAYLOAD_NOT_FOUND",
        )
        val actualChecksum = sha256(selected.second)
        val expectedChecksum = checksums.entries.firstOrNull { (name, _) ->
            name == selected.first ||
                name.substringAfterLast('/') ==
                selected.first.substringAfterLast('/')
        }?.value ?: throw ArtifactVerificationException(
            "Для APK/AAB отсутствует checksum sidecar",
            "ARTIFACT_CHECKSUM_MISSING",
        )
        if (!actualChecksum.equals(expectedChecksum, ignoreCase = true)) {
            throw ArtifactVerificationException(
                "Checksum APK/AAB не совпал",
                "ARTIFACT_CHECKSUM_MISMATCH",
            )
        }
        if (
            provenanceSha.isNullOrBlank() ||
            !provenanceSha.equals(sourceSha, ignoreCase = true)
        ) {
            throw ArtifactVerificationException(
                "Provenance source SHA не совпал",
                "ARTIFACT_PROVENANCE_MISMATCH",
            )
        }
        val payloadName = selected.first.substringAfterLast('/')
        if (!payloadName.matches(FILE_NAME_PATTERN)) {
            throw ArtifactVerificationException(
                "Имя APK/AAB недопустимо",
                "ARTIFACT_FILE_NAME_INVALID",
            )
        }
        return VerifiedArtifact(
            artifactId = artifactId,
            fileName = payloadName,
            sourceSha = sourceSha,
            checksum = actualChecksum,
            bytes = selected.second,
            contentType = if (payloadName.lowercase().endsWith(".aab")) {
                "application/octet-stream"
            } else {
                "application/vnd.android.package-archive"
            },
        )
    }

    private fun dispatchBody(
        ref: String,
        inputs: Map<String, String>,
    ): String = JSONObject()
        .put("ref", ref)
        .put(
            "inputs",
            JSONObject().apply {
                inputs.entries.sortedBy { it.key }.take(MAX_INPUTS).forEach { (key, value) ->
                    put(key.take(MAX_INPUT_KEY_CHARS), value.take(MAX_INPUT_VALUE_CHARS))
                }
            },
        )
        .toString()

    private fun validateRequest(
        token: String,
        repository: String,
        workflow: String,
        ref: String,
    ): String? {
        if (token.isBlank()) return "GitHub token не задан"
        val parts = repository.trim().split('/')
        if (
            parts.size != 2 ||
            parts.any { it.isBlank() } ||
            parts.any { !REPOSITORY_PART_PATTERN.matches(it) }
        ) {
            return "Репозиторий должен иметь формат owner/name"
        }
        val normalizedWorkflow = workflow.trim()
        if (
            normalizedWorkflow.isBlank() ||
            normalizedWorkflow.length > MAX_IDENTIFIER_CHARS ||
            normalizedWorkflow.contains('\u0000') ||
            normalizedWorkflow.split('/').any { part ->
                part.isBlank() ||
                    part == "." ||
                    part == ".." ||
                    !WORKFLOW_PART_PATTERN.matches(part)
            }
        ) {
            return "Нужен корректный workflow id или путь"
        }
        val normalizedRef = ref.trim()
        if (
            normalizedRef.isBlank() ||
            normalizedRef.length > MAX_IDENTIFIER_CHARS ||
            normalizedRef.startsWith('/') ||
            normalizedRef.endsWith('/') ||
            normalizedRef.contains("..") ||
            normalizedRef.contains("//") ||
            normalizedRef.contains("@{") ||
            normalizedRef.any { it.isWhitespace() || it == '\u0000' } ||
            !normalizedRef.all { it.isLetterOrDigit() || it in "._/@-" }
        ) {
            return "Нужен ref"
        }
        return null
    }

    private fun apiRequest(
        token: String,
        method: String,
        endpoint: String,
        body: String? = null,
    ): HttpResponse {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            doInput = true
            doOutput = body != null
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("Authorization", "Bearer " + token)
            if (body != null) setRequestProperty("Content-Type", "application/json")
        }
        try {
            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(body.toByteArray(Charsets.UTF_8))
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            return HttpResponse(
                status = status,
                body = readLimited(stream, MAX_RESPONSE_BYTES),
                location = connection.getHeaderField("Location"),
                contentType = connection.getHeaderField("Content-Type"),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadWithRedirects(
        token: String,
        endpoint: String,
        maxBytes: Int,
    ): DownloadResponse {
        var currentEndpoint = endpoint
        repeat(MAX_REDIRECTS) {
            require(currentEndpoint.startsWith("https://")) {
                "Некорректный download endpoint"
            }
            val url = URL(currentEndpoint)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                doInput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                if (url.host == API_HOST) {
                    setRequestProperty("Authorization", "Bearer " + token)
                }
            }
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    currentEndpoint = connection.getHeaderField("Location")
                        ?: throw IOException("Redirect без Location")
                    return@repeat
                }
                if (status !in 200..299) {
                    val details = readLimited(
                        connection.errorStream,
                        MAX_ERROR_BYTES,
                    ).toUtf8()
                    throw ActionsHttpException(
                        status,
                        "Download HTTP " + status + ": " +
                            AgentRedactor.text(details, MAX_ERROR_CHARS).orEmpty(),
                    )
                }
                return DownloadResponse(
                    bytes = readLimited(connection.inputStream, maxBytes),
                    contentType = connection.getHeaderField("Content-Type"),
                )
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Слишком много redirect при download")
    }

    private fun parseRuns(value: String): List<RunInfo> {
        val json = JSONObject(value)
        val array = json.optJSONArray("workflow_runs") ?: JSONArray()
        return buildList {
            for (index in 0 until array.length().coerceAtMost(MAX_RUNS)) {
                array.optJSONObject(index)?.let { add(parseRun(it)) }
            }
        }
    }

    private fun parseRun(value: JSONObject): RunInfo {
        val id = value.optLong("id", 0L)
        if (id <= 0L) throw IOException("Workflow run id отсутствует")
        val status = value.optString("status").trim()
        return RunInfo(
            id = id,
            number = value.optInt("run_number", 0).takeIf { it > 0 },
            status = status,
            conclusion = value.optString("conclusion").trim()
                .takeIf { it.isNotBlank() && it != "null" },
            headSha = value.optString("head_sha").trim(),
            createdAt = parseTime(value.optString("created_at")),
            isCompleted = status == "completed",
        )
    }

    private fun parseJob(value: JSONObject): JobInfo {
        val id = value.optLong("id", 0L)
        if (id <= 0L) throw IOException("Job id отсутствует")
        val steps = buildList {
            val array = value.optJSONArray("steps") ?: JSONArray()
            for (index in 0 until array.length().coerceAtMost(MAX_STEPS)) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    StepInfo(
                        name = item.optString("name").trim().take(MAX_STEP_NAME_CHARS),
                        status = item.optString("status").trim().take(64),
                        conclusion = item.optString("conclusion").trim()
                            .takeIf { it.isNotBlank() && it != "null" },
                        number = item.optInt("number", 0),
                        durationMs = durationMs(
                            item.optString("started_at"),
                            item.optString("completed_at"),
                        ),
                    ),
                )
            }
        }
        return JobInfo(
            id = id,
            name = value.optString("name").trim().take(MAX_JOB_NAME_CHARS),
            status = value.optString("status").trim().take(64),
            conclusion = value.optString("conclusion").trim()
                .takeIf { it.isNotBlank() && it != "null" },
            durationMs = durationMs(
                value.optString("started_at"),
                value.optString("completed_at"),
            ),
            steps = steps,
        )
    }

    private fun parseArtifact(value: JSONObject): ArtifactInfo {
        val id = value.optLong("id", 0L)
        if (id <= 0L) throw IOException("Artifact id отсутствует")
        return ArtifactInfo(
            id = id,
            name = value.optString("name").trim().take(MAX_ARTIFACT_NAME_CHARS),
            sizeBytes = value.optLong("size_in_bytes", 0L).coerceAtLeast(0L),
            contentType = value.optString("content_type").trim()
                .takeIf { it.isNotBlank() && it != "null" },
            expired = value.optBoolean("expired", false),
            archiveDigest = value.optString("digest").trim()
                .takeIf { it.isNotBlank() && it != "null" },
        )
    }

    private fun parseTime(value: String): Long =
        if (value.isBlank()) 0L else runCatching {
            Instant.parse(value).toEpochMilli()
        }.getOrDefault(0L)

    private fun durationMs(start: String, end: String): Long? {
        val started = parseTime(start)
        val finished = parseTime(end)
        return if (started > 0L && finished >= started) finished - started else null
    }

    private fun RunInfo.toState(
        base: ActionsOperationState,
        sourceStatus: ActionsOperationStatus,
    ): ActionsOperationState = base.copy(
        status = sourceStatus,
        runId = id,
        runNumber = number,
        headSha = headSha.takeIf { it.isNotBlank() },
        conclusion = conclusion,
        summary = "Workflow run: " + (status.ifBlank { "unknown" }),
    )

    private fun JobInfo.toState(): ActionsJobState = ActionsJobState(
        id = id,
        name = name,
        status = status,
        conclusion = conclusion,
        durationMs = durationMs,
        steps = steps.map { it.toState() },
    )

    private fun StepInfo.toState(): ActionsStepState = ActionsStepState(
        name = name,
        status = status,
        conclusion = conclusion,
        number = number,
        durationMs = durationMs,
    )

    private fun ArtifactInfo.toState(sourceSha: String): ActionsArtifactState =
        ActionsArtifactState(
            id = id,
            name = name,
            sizeBytes = sizeBytes,
            contentType = contentType,
            expired = expired,
            archiveDigest = archiveDigest,
            sourceSha = sourceSha,
        )

    private fun apiBase(repository: String): String =
        "https://" + API_HOST + "/repos/" + repository.trim()

    private fun dispatchEndpoint(repository: String, workflow: String): String =
        apiBase(repository) + "/actions/workflows/" +
            encodeWorkflow(workflow) + "/dispatches"

    private fun runsEndpoint(
        repository: String,
        workflow: String,
        ref: String,
    ): String = apiBase(repository) + "/actions/workflows/" +
        encodeWorkflow(workflow) + "/runs?event=workflow_dispatch&branch=" +
        urlEncode(ref) + "&per_page=20"

    private fun runEndpoint(repository: String, runId: Long): String =
        apiBase(repository) + "/actions/runs/" + runId

    private fun jobsEndpoint(repository: String, runId: Long): String =
        runEndpoint(repository, runId) + "/jobs?per_page=100"

    private fun artifactsEndpoint(repository: String, runId: Long): String =
        runEndpoint(repository, runId) + "/artifacts?per_page=100"

    private fun logsEndpoint(repository: String, jobId: Long): String =
        apiBase(repository) + "/actions/jobs/" + jobId + "/logs"

    private fun artifactEndpoint(repository: String, artifactId: Long): String =
        apiBase(repository) + "/actions/artifacts/" + artifactId

    private fun artifactZipEndpoint(repository: String, artifactId: Long): String =
        artifactEndpoint(repository, artifactId) + "/zip"

    private fun encodeWorkflow(workflow: String): String =
        workflow.trim().split('/').joinToString("/") { urlEncode(it) }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun safeError(value: String?, fallback: String): String =
        AgentRedactor.text(value?.take(MAX_ERROR_CHARS), MAX_ERROR_CHARS)
            ?.takeIf { it.isNotBlank() }
            ?: fallback

    private data class HttpResponse(
        val status: Int,
        val body: ByteArray,
        val location: String? = null,
        val contentType: String? = null,
    ) {
        fun errorSuffix(): String =
            if (status in 200..299 || body.isEmpty()) "" else ": " +
                AgentRedactor.text(body.toString(Charsets.UTF_8), MAX_ERROR_CHARS).orEmpty()
    }

    private data class DownloadResponse(
        val bytes: ByteArray,
        val contentType: String?,
    )

    private data class RunInfo(
        val id: Long,
        val number: Int?,
        val status: String,
        val conclusion: String?,
        val headSha: String,
        val createdAt: Long,
        val isCompleted: Boolean,
    )

    private data class JobInfo(
        val id: Long,
        val name: String,
        val status: String,
        val conclusion: String?,
        val durationMs: Long?,
        val steps: List<StepInfo>,
    )

    private data class StepInfo(
        val name: String,
        val status: String,
        val conclusion: String?,
        val number: Int,
        val durationMs: Long?,
    )

    private data class ArtifactInfo(
        val id: Long,
        val name: String,
        val sizeBytes: Long,
        val contentType: String?,
        val expired: Boolean,
        val archiveDigest: String?,
    )

    private class ActionsHttpException(
        val status: Int,
        message: String,
    ) : IOException(message)

    private class ArtifactVerificationException(
        message: String,
        val errorCode: String,
    ) : IOException(message)

    private fun readLimited(input: InputStream?, maxBytes: Int): ByteArray {
        if (input == null) return ByteArray(0)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        input.use { stream ->
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > maxBytes) {
                    throw IOException("Ответ превышает установленный лимит")
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    /**
     * Reads only the current ZIP entry. Unlike readLimited(), this function
     * must not close the supplied ZipInputStream: closing it would make the
     * following checksum/provenance entries unreadable.
     */
    private fun readEntryLimited(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > maxBytes) {
                throw IOException("Размер ZIP entry превышает установленный лимит")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun requireSafeArchivePath(path: String) {
        val normalized = path.trimEnd('/')
        if (
            normalized.isBlank() ||
            normalized.startsWith("/") ||
            normalized.contains('\\') ||
            normalized.split('/').any { it == ".." || it.isBlank() }
        ) {
            throw ArtifactVerificationException(
                "Artifact содержит небезопасный путь",
                "ARTIFACT_PATH_INVALID",
            )
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }

    private fun ByteArray.toUtf8(): String = toString(Charsets.UTF_8)

    private companion object {
        const val API_HOST = "api.github.com"
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 30_000
        const val MIN_POLL_TIMEOUT_MS = 10_000L
        const val MAX_POLL_TIMEOUT_MS = 15 * 60 * 1_000L
        const val MIN_POLL_INTERVAL_MS = 250L
        const val MAX_POLL_INTERVAL_MS = 10_000L
        const val DISCOVERY_SKEW_MS = 30_000L
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        const val MAX_ERROR_BYTES = 32 * 1024
        const val MAX_LOG_CHARS = 32_000
        const val MAX_ARCHIVE_BYTES = 128 * 1024 * 1024
        const val MAX_ENTRY_BYTES = 128 * 1024 * 1024
        const val MAX_TOTAL_ENTRY_BYTES = 128L * 1024L * 1024L
        const val MAX_ARCHIVE_ENTRIES = 256
        const val MAX_PAYLOAD_BYTES = 64 * 1024 * 1024
        const val MAX_INPUTS = 32
        const val MAX_INPUT_KEY_CHARS = 96
        const val MAX_INPUT_VALUE_CHARS = 2_000
        const val MAX_IDENTIFIER_CHARS = 160
        const val MAX_FAILED_STEP_CHARS = 320
        const val MAX_JOBS = 100
        const val MAX_STEPS = 100
        const val MAX_ARTIFACTS = 100
        const val MAX_RUNS = 50
        const val MAX_JOB_NAME_CHARS = 200
        const val MAX_STEP_NAME_CHARS = 200
        const val MAX_ARTIFACT_NAME_CHARS = 200
        const val MAX_ERROR_CHARS = 4_000
        const val MAX_REDIRECTS = 4
        private val REPOSITORY_PART_PATTERN = Regex("[A-Za-z0-9_.-]{1,100}")
        private val WORKFLOW_PART_PATTERN = Regex("[A-Za-z0-9._-]{1,100}")
        private val SHA_PATTERN = Regex("[A-Fa-f0-9]{40,64}")
        private val FILE_NAME_PATTERN =
            Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.(?i:apk|aab)")
        private val SHA_LINE_PATTERN = Regex(
            "(?m)^\\s*([A-Fa-f0-9]{64})\\s+\\*?(.+?)\\s*$",
        )
    }
}
