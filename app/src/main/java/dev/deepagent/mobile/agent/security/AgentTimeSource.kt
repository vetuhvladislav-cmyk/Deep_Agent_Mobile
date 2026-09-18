package dev.deepagent.mobile.agent.security

/**
 * Источник времени для решений о доступе.
 *
 * TTL approval, reconcile timeout и expiresAt grant не могут опираться на wall
 * clock: перевод системных часов назад продлевает окно approval, а перевод
 * вперёд делает валидный grant «истёкшим» по внешней причине. Поэтому решения
 * принимаются по монотонному источнику, а wall clock остаётся метаданными
 * отображения и журнала.
 *
 * [bootId] меняется при перезапуске процесса. Outstanding-токены и grant-ы
 * привязаны к boot identity: после перезапуска доказать их окно нельзя, поэтому
 * они инвалидируются (fail-closed). Это осознанный выбор, а не побочный эффект.
 */
interface AgentTimeSource {
    /** Монотонное время в миллисекундах; не зависит от перевода часов. */
    fun monotonicMillis(): Long

    /** Wall clock в миллисекундах; только для отображения и метаданных ledger. */
    fun wallClockMillis(): Long

    /** Идентификатор текущего запуска процесса. */
    val bootId: String
}

/**
 * Продакшн-источник для Android: монотонное время считается от старта процесса,
 * поэтому не зависит ни от системных часов, ни от конкретной платформенной
 * реализации. `bootId` фиксируется при инициализации объекта.
 */
class ProcessAgentTimeSource(
    override val bootId: String = java.util.UUID.randomUUID().toString(),
    private val monotonicBase: Long = System.nanoTime() / 1_000_000L,
) : AgentTimeSource {

    override fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L - monotonicBase

    override fun wallClockMillis(): Long = System.currentTimeMillis()

    override fun toString(): String = "ProcessAgentTimeSource(bootId=$bootId)"
}

/**
 * Детерминированный источник для тестов. Wall clock можно двигать назад, чтобы
 * доказать, что решения о доступе от него не зависят.
 */
class FixedAgentTimeSource(
    private var monotonicMillis: Long = 0L,
    private var wallMillis: Long = 0L,
    override var bootId: String = "boot-test",
) : AgentTimeSource {

    override fun monotonicMillis(): Long = monotonicMillis

    override fun wallClockMillis(): Long = wallMillis

    fun advanceMonotonicBy(deltaMs: Long) {
        monotonicMillis += deltaMs
    }

    fun advanceWallClockBy(deltaMs: Long) {
        wallMillis += deltaMs
    }

    fun setWallClock(value: Long) {
        wallMillis = value
    }

    fun reboot(newBootId: String = "boot-test-2") {
        bootId = newBootId
        monotonicMillis = 0L
    }
}
