package io.gatekeeper.util

/**
 * Состояние связи с файловым шаттлом без зависимости от Android SDK: поколение попытки
 * привязки, single-flight, ожидание с обязательным таймаутом и три срока годности --
 * простоя, последней дошедшей до другой стороны отметки и сессии целиком.
 *
 * Вынесено из [FileShuttleConnection] отдельным классом, потому что именно здесь живет
 * структурное требование Фазы 3: ожидания без таймаута нет ни на одном пути, и это
 * проверяется JVM-тестами, без эмулятора.
 */
class ShuttleSession<T : Any>(
    private val idleTimeoutMs: Long,
    private val contactTimeoutMs: Long,
    private val maxSessionMs: Long,
    private val bindTimeoutMs: Long,
    private val clock: () -> Long
) {
    init {
        require(idleTimeoutMs > 0) { "idleTimeoutMs must be positive" }
        require(contactTimeoutMs > 0) { "contactTimeoutMs must be positive" }
        require(maxSessionMs > 0) { "maxSessionMs must be positive" }
        require(bindTimeoutMs > 0) { "bindTimeoutMs must be positive" }
    }

    private val lock = Object()
    private var value: T? = null
    private var generation = 0
    private var bindStartedAt = NO_BIND
    private var lastActivityAt = 0L
    private var lastContactAt = 0L
    private var startedAt = 0L

    /**
     * Годный биндер или null. [alive] обязана быть неблокирующей: она вызывается под локом
     * на пути каждого запроса провайдера. Опрос сессию не продлевает: иначе удерживающие
     * пинги, которые ходят через этот же метод, не дали бы ей истечь никогда.
     */
    fun peek(alive: (T) -> Boolean = { true }): T? = synchronized(lock) {
        val current = value ?: return null
        if (expired(clock()) || !alive(current)) {
            value = null
            return null
        }
        current
    }

    /**
     * Отмечает работу пользователя: сессия живет [idleTimeoutMs] с последней отметки, а не
     * с последнего вызова. Истекшую сессию не воскрешает -- биндер, переживший срок
     * годности, выдавать нельзя.
     */
    fun touch() = synchronized(lock) {
        val now = clock()
        if (expired(now)) {
            value = null
        }
        lastActivityAt = now
    }

    /**
     * Отмечает, что до другой стороны дошло: успешный вызов или отправленный пинг. [at] --
     * момент **отправки**: другая сторона сбрасывает свой таймер в начале обработки, и
     * отметка по ответу сдвигала бы наш срок вперед относительно чужого на всю длительность
     * вызова. Ответ вызова, отправленного раньше, срок назад не отматывает, а опоздавшая
     * отметка сессию не воскрешает -- к этому моменту другая сторона уже могла умереть.
     */
    fun markContact(at: Long) = synchronized(lock) {
        if (expired(clock())) {
            value = null
        } else if (at > lastContactAt) {
            lastContactAt = at
        }
    }

    /**
     * Три независимых срока. [idleTimeoutMs] -- пользователь ушел. [contactTimeoutMs] --
     * зеркало суицидного таймера другой стороны: пока до нее ничего не доходило дольше этого
     * срока, биндер считается негодным, иначе транзакция уйдет в мертвый или замороженный
     * процесс. [maxSessionMs] -- потолок на сессию целиком, чтобы фоновый клиент, дергающий
     * провайдер по расписанию, не удерживал чужой профиль бесконечно.
     */
    private fun expired(now: Long) = now - lastActivityAt > idleTimeoutMs ||
        now - lastContactAt > contactTimeoutMs ||
        now - startedAt > maxSessionMs

    /**
     * Номер поколения новой попытки привязки или [NO_GENERATION], если попытка уже идет
     * и еще не истекла.
     */
    fun beginBind(): Int = synchronized(lock) {
        val now = clock()
        if (bindStartedAt != NO_BIND && now - bindStartedAt < bindTimeoutMs) {
            return NO_GENERATION
        }
        bindStartedAt = now
        ++generation
    }

    /** Попытка не состоялась: снять single-flight, чтобы следующий запрос смог повторить. */
    fun failBind(generation: Int) = synchronized(lock) {
        if (generation == this.generation) {
            bindStartedAt = NO_BIND
            lock.notifyAll()
        }
    }

    /** Принимает биндер только от актуального поколения. */
    fun accept(generation: Int, value: T): Boolean = synchronized(lock) {
        if (generation != this.generation) return false
        val now = clock()
        this.value = value
        this.lastActivityAt = now
        this.lastContactAt = now
        this.startedAt = now
        bindStartedAt = NO_BIND
        lock.notifyAll()
        true
    }

    /** Ожидание биндера. Таймаут обязателен, ложные пробуждения и прерывание учтены. */
    fun await(timeoutMs: Long): T? {
        val deadline = clock() + timeoutMs
        synchronized(lock) {
            while (value == null) {
                val remaining = deadline - clock()
                if (remaining <= 0) return null
                try {
                    lock.wait(remaining)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            return value
        }
    }

    /**
     * Сбрасывает биндер, на котором отказал вызов, и только его: пока вызов висел в
     * таймауте, сессия могла успеть привязаться заново. Поколение не трогается -- попытка
     * привязки, начатая пока старый биндер умирал, обязана довезти свой колбэк.
     */
    fun drop(expected: T) = synchronized(lock) {
        if (value === expected) {
            value = null
        }
    }

    /** Сбрасывает биндер указанного поколения; более свежий не трогает. */
    fun discard(generation: Int) = synchronized(lock) {
        if (generation == this.generation) {
            value = null
            lock.notifyAll()
        }
    }

    /** Сбрасывает биндер и обесценивает колбэки всех начатых попыток. */
    fun release() = synchronized(lock) {
        value = null
        bindStartedAt = NO_BIND
        ++generation
        lock.notifyAll()
    }

    companion object {
        const val NO_GENERATION = -1
        private const val NO_BIND = -1L
    }
}
