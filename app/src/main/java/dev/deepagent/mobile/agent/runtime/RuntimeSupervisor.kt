package dev.deepagent.mobile.agent.runtime

import dev.deepagent.mobile.agent.model.RuntimeState
import dev.deepagent.mobile.agent.model.RuntimeStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class RuntimeManifest(
    val version: String,
    val abi: String,
    val checksum: String,
) {
    companion object {
        /**
         * Safe reference manifest. It is deliberately a loopback adapter, not
         * a downloadable DSH/Node bundle.
         */
        fun loopback(): RuntimeManifest = RuntimeManifest(
            version = "loopback-v1",
            abi = "android-loopback",
            checksum = "0000000000000000000000000000000000000000000000000000000000000000",
        )
    }
}

data class RuntimeProviderResult(
    val ok: Boolean,
    val summary: String,
    val errorCode: String? = null,
)

data class RuntimeProbe(
    val ready: Boolean,
    val summary: String,
    val errorCode: String? = null,
)

interface HeadlessRuntimeProvider {
    suspend fun install(manifest: RuntimeManifest): RuntimeProviderResult
    suspend fun start(manifest: RuntimeManifest): RuntimeProviderResult
    suspend fun probe(): RuntimeProbe
    suspend fun stop(): RuntimeProviderResult
}

/**
 * Reference provider used until DP-02 selects a real, signed ARM64 bundle.
 * It exercises the supervisor state machine without starting a shell or
 * shipping a second APK.
 */
class LoopbackHeadlessRuntimeProvider : HeadlessRuntimeProvider {
    private var activeManifest: RuntimeManifest? = null

    override suspend fun install(manifest: RuntimeManifest): RuntimeProviderResult =
        RuntimeProviderResult(
            ok = manifest == RuntimeManifest.loopback(),
            summary = if (manifest == RuntimeManifest.loopback()) {
                "Loopback runtime manifest принят; внешний runtime не устанавливается"
            } else {
                "Runtime manifest не разрешён политикой приложения"
            },
            errorCode = if (manifest == RuntimeManifest.loopback()) {
                null
            } else {
                "RUNTIME_MANIFEST_NOT_ALLOWED"
            },
        )

    override suspend fun start(manifest: RuntimeManifest): RuntimeProviderResult {
        if (manifest != RuntimeManifest.loopback()) {
            return RuntimeProviderResult(
                ok = false,
                summary = "Доступен только согласованный loopback manifest",
                errorCode = "RUNTIME_MANIFEST_NOT_ALLOWED",
            )
        }
        activeManifest = manifest
        return RuntimeProviderResult(
            ok = true,
            summary = "Loopback runtime запущен внутри Agent Core",
        )
    }

    override suspend fun probe(): RuntimeProbe {
        val ready = activeManifest != null
        return RuntimeProbe(
            ready = ready,
            summary = if (ready) {
                "Readiness probe подтверждён"
            } else {
                "Runtime не активен"
            },
            errorCode = if (ready) null else "RUNTIME_NOT_ACTIVE",
        )
    }

    override suspend fun stop(): RuntimeProviderResult {
        activeManifest = null
        return RuntimeProviderResult(
            ok = true,
            summary = "Runtime остановлен контролируемо",
        )
    }
}

/**
 * Owns lifecycle and rollback. The provider cannot escape this boundary and
 * never receives GitHub, merge/release or user credential permissions.
 */
class RuntimeSupervisor(
    private val provider: HeadlessRuntimeProvider = LoopbackHeadlessRuntimeProvider(),
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    suspend fun start(sessionId: String?): RuntimeState = withContext(Dispatchers.IO) {
        mutex.withLock {
            val manifest = RuntimeManifest.loopback()
            val current = _state.value
            if (
                current.status == RuntimeStatus.READY &&
                current.version == manifest.version &&
                current.checksum == manifest.checksum
            ) {
                return@withLock current.copy(
                    sessionId = sessionId ?: current.sessionId,
                    heartbeatAt = System.currentTimeMillis(),
                    summary = "Runtime уже готов; readiness повторно подтверждён",
                    errorCode = null,
                    updatedAt = System.currentTimeMillis(),
                ).also { _state.value = it }
            }

            publish(
                RuntimeState(
                    status = RuntimeStatus.INSTALLING,
                    sessionId = sessionId,
                    version = manifest.version,
                    abi = manifest.abi,
                    checksum = manifest.checksum,
                    summary = "Проверка runtime manifest",
                ),
            )

            try {
                val installed = withTimeout(INSTALL_TIMEOUT_MS) {
                    provider.install(manifest)
                }
                if (!installed.ok) {
                    return@withLock rollback(
                        sessionId = sessionId,
                        manifest = manifest,
                        summary = installed.summary,
                        errorCode = installed.errorCode ?: "RUNTIME_INSTALL_FAILED",
                    )
                }

                publish(
                    _state.value.copy(
                        status = RuntimeStatus.STARTING,
                        summary = "Запуск headless runtime",
                        errorCode = null,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                val started = withTimeout(START_TIMEOUT_MS) {
                    provider.start(manifest)
                }
                if (!started.ok) {
                    return@withLock rollback(
                        sessionId = sessionId,
                        manifest = manifest,
                        summary = started.summary,
                        errorCode = started.errorCode ?: "RUNTIME_START_FAILED",
                    )
                }

                val probe = withTimeout(PROBE_TIMEOUT_MS) {
                    provider.probe()
                }
                if (!probe.ready) {
                    return@withLock rollback(
                        sessionId = sessionId,
                        manifest = manifest,
                        summary = probe.summary,
                        errorCode = probe.errorCode ?: "RUNTIME_NOT_READY",
                    )
                }

                publish(
                    _state.value.copy(
                        status = RuntimeStatus.READY,
                        heartbeatAt = System.currentTimeMillis(),
                        summary = probe.summary,
                        errorCode = null,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                _state.value
            } catch (timeout: TimeoutCancellationException) {
                rollback(
                    sessionId = sessionId,
                    manifest = manifest,
                    summary = "Запуск runtime превысил timeout",
                    errorCode = "RUNTIME_START_TIMEOUT",
                )
            } catch (cancelled: CancellationException) {
                runCatching {
                    withContext(NonCancellable) {
                        withTimeout(STOP_TIMEOUT_MS) { provider.stop() }
                    }
                }
                publish(
                    RuntimeState(
                        status = RuntimeStatus.EMPTY,
                        sessionId = sessionId,
                        summary = "Запуск runtime отменён; временное состояние удалено",
                        errorCode = "RUNTIME_START_CANCELLED",
                    ),
                )
                throw cancelled
            } catch (error: Exception) {
                rollback(
                    sessionId = sessionId,
                    manifest = manifest,
                    summary = error.message ?: "Runtime завершился без подтверждения",
                    errorCode = "RUNTIME_START_UNKNOWN",
                )
            }
        }
    }

    suspend fun probe(sessionId: String? = null): RuntimeState = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (_state.value.status != RuntimeStatus.READY) return@withLock _state.value
            return@withLock try {
                val probe = withTimeout(PROBE_TIMEOUT_MS) { provider.probe() }
                if (!probe.ready) {
                    rollback(
                        sessionId = sessionId ?: _state.value.sessionId,
                        manifest = RuntimeManifest.loopback(),
                        summary = probe.summary,
                        errorCode = probe.errorCode ?: "RUNTIME_HEALTH_FAILED",
                    )
                } else {
                    _state.value.copy(
                        sessionId = sessionId ?: _state.value.sessionId,
                        heartbeatAt = System.currentTimeMillis(),
                        summary = probe.summary,
                        updatedAt = System.currentTimeMillis(),
                    ).also { _state.value = it }
                }
            } catch (timeout: TimeoutCancellationException) {
                rollback(
                    sessionId = sessionId ?: _state.value.sessionId,
                    manifest = RuntimeManifest.loopback(),
                    summary = "Readiness probe превысил timeout",
                    errorCode = "RUNTIME_HEALTH_TIMEOUT",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                rollback(
                    sessionId = sessionId ?: _state.value.sessionId,
                    manifest = RuntimeManifest.loopback(),
                    summary = error.message ?: "Readiness probe неизвестен",
                    errorCode = "RUNTIME_HEALTH_UNKNOWN",
                )
            }
        }
    }

    suspend fun stop(sessionId: String? = null): RuntimeState = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (_state.value.status == RuntimeStatus.EMPTY) {
                return@withLock _state.value
            }
            publish(
                _state.value.copy(
                    status = RuntimeStatus.STOPPING,
                    sessionId = sessionId ?: _state.value.sessionId,
                    summary = "Остановка runtime",
                    errorCode = null,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            return@withLock try {
                val stopped = withTimeout(STOP_TIMEOUT_MS) { provider.stop() }
                if (!stopped.ok) {
                    publish(
                        _state.value.copy(
                            status = RuntimeStatus.FAILED,
                            summary = stopped.summary,
                            errorCode = stopped.errorCode ?: "RUNTIME_STOP_FAILED",
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    _state.value
                } else {
                    publish(
                        RuntimeState(
                            status = RuntimeStatus.EMPTY,
                            sessionId = sessionId ?: _state.value.sessionId,
                            summary = stopped.summary,
                        ),
                    )
                    _state.value
                }
            } catch (timeout: TimeoutCancellationException) {
                publish(
                    _state.value.copy(
                        status = RuntimeStatus.FAILED,
                        summary = "Остановка runtime превысила timeout",
                        errorCode = "RUNTIME_STOP_TIMEOUT",
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                _state.value
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                publish(
                    _state.value.copy(
                        status = RuntimeStatus.FAILED,
                        summary = error.message ?: "Runtime stop неизвестен",
                        errorCode = "RUNTIME_STOP_UNKNOWN",
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                _state.value
            }
        }
    }

    fun close() {
        _state.value = RuntimeState(
            status = RuntimeStatus.EMPTY,
            summary = "Runtime supervisor закрыт",
        )
    }

    private suspend fun rollback(
        sessionId: String?,
        manifest: RuntimeManifest,
        summary: String,
        errorCode: String,
    ): RuntimeState {
        publish(
            RuntimeState(
                status = RuntimeStatus.ROLLBACK,
                sessionId = sessionId,
                version = manifest.version,
                abi = manifest.abi,
                checksum = manifest.checksum,
                summary = summary,
                errorCode = errorCode,
            ),
        )
        try {
            withTimeout(STOP_TIMEOUT_MS) { provider.stop() }
        } catch (_: TimeoutCancellationException) {
            // The supervisor still moves to EMPTY; a later start must perform
            // a fresh install/start/probe sequence instead of trusting state.
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        return RuntimeState(
            status = RuntimeStatus.EMPTY,
            sessionId = sessionId,
            version = manifest.version,
            abi = manifest.abi,
            checksum = manifest.checksum,
            summary = "Runtime откатан до EMPTY",
            errorCode = errorCode,
        ).also { _state.value = it }
    }

    private fun publish(state: RuntimeState): RuntimeState =
        state.copy(updatedAt = System.currentTimeMillis()).also { _state.value = it }

    private companion object {
        const val INSTALL_TIMEOUT_MS = 10_000L
        const val START_TIMEOUT_MS = 10_000L
        const val PROBE_TIMEOUT_MS = 5_000L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
