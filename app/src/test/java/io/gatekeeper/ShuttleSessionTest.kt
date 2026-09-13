package io.gatekeeper

import io.gatekeeper.util.ShuttleSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ShuttleSessionTest {
    private class FakeClock(var now: Long = 1_000L) : () -> Long {
        override fun invoke(): Long = now
    }

    private fun session(
        idleMs: Long = 5_000L,
        contactMs: Long = 600_000L,
        maxMs: Long = 1_000_000L,
        bindMs: Long = 5_000L,
        clock: () -> Long = FakeClock()
    ) = ShuttleSession<String>(idleMs, contactMs, maxMs, bindMs, clock)

    @Test
    fun singleFlight_onlyOneAttemptWinsAcrossThreads() {
        val session = session()
        val threads = 16
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val granted = AtomicInteger()
        repeat(threads) {
            Thread {
                start.await()
                if (session.beginBind() != ShuttleSession.NO_GENERATION) {
                    granted.incrementAndGet()
                }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(1, granted.get())
    }

    @Test
    fun staleGenerationCallbackIsIgnored() {
        val session = session()
        val stale = session.beginBind()
        session.release()
        val current = session.beginBind()

        assertFalse(session.accept(stale, "stale"))
        assertNull(session.peek())
        assertTrue(session.accept(current, "fresh"))
        assertEquals("fresh", session.peek())
    }

    /**
     * Ключевое структурное требование Фазы 3: ожидание биндера обязано завершаться.
     * Провайдер SAF на любом пути должен вернуть управление, даже если другой профиль
     * не отвечает вовсе.
     */
    @Test
    fun awaitReturnsWithinTimeout_whenBinderNeverArrives() {
        val session = ShuttleSession<String>(5_000L, 5_000L, 60_000L, 5_000L) { System.nanoTime() / 1_000_000 }
        session.beginBind()
        val startedAt = System.nanoTime()
        assertNull(session.await(200L))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue("ожидание заняло $elapsedMs мс", elapsedMs in 150..3_000)
    }

    @Test
    fun awaitReturnsBinderThatArrivesLate() {
        val session = ShuttleSession<String>(5_000L, 5_000L, 60_000L, 5_000L) { System.nanoTime() / 1_000_000 }
        val generation = session.beginBind()
        Thread {
            Thread.sleep(100)
            session.accept(generation, "binder")
        }.start()
        assertEquals("binder", session.await(5_000L))
    }

    @Test
    fun expiresAfterIdle() {
        val clock = FakeClock()
        val session = session(idleMs = 5_000L, clock = clock)
        assertTrue(session.accept(session.beginBind(), "binder"))

        clock.now += 5_001L
        assertNull(session.peek())
    }

    @Test
    fun staysAliveWhileActive() {
        val clock = FakeClock()
        val session = session(idleMs = 5_000L, clock = clock)
        assertTrue(session.accept(session.beginBind(), "binder"))

        repeat(10) {
            clock.now += 4_000L
            session.touch()
            assertEquals("binder", session.peek())
        }
    }

    /**
     * Пинги -- единственное, что удерживает другую сторону от самоубийства, и они не идут,
     * пока наш процесс заморожен. Если после такой паузы выдать биндер, синхронная
     * транзакция уйдет в процесс, которого уже нет или который заморожен, и убьет его.
     * Срок связи -- зеркало суицидного таймера другой стороны, и работа пользователя его
     * не продлевает.
     */
    @Test
    fun expiresWhenNothingReachedTheOtherSide() {
        val clock = FakeClock()
        val session = session(idleMs = 300_000L, contactMs = 60_000L, clock = clock)
        assertTrue(session.accept(session.beginBind(), "binder"))

        clock.now += 60_001L
        session.touch()

        assertNull("биндер без подтвержденной связи выдавать нельзя", session.peek())
    }

    /** Пинги держат биндер годным, пока пользователь ничего не делает. */
    @Test
    fun contactKeepsBinderValidWithoutUserActivity() {
        val clock = FakeClock()
        val session = session(idleMs = 600_000L, contactMs = 60_000L, clock = clock)
        assertTrue(session.accept(session.beginBind(), "binder"))

        repeat(8) {
            clock.now += 30_000L
            session.markContact(clock.now)
        }
        assertEquals("binder", session.peek())

        clock.now += 60_001L
        assertNull(session.peek())
    }

    /**
     * Другая сторона сбрасывает свой суицидный таймер в начале обработки вызова, а не в
     * конце, поэтому и наш срок обязан считаться от отправки. Иначе долгий вызов сдвигает
     * наш срок вперед относительно чужого, и в этом окне выдается биндер сервиса, который
     * уже покончил с собой: он отвечает как живой, пока процесс не заморозят и не убьют.
     */
    @Test
    fun contactIsMeasuredFromTheMomentTheCallWasSent() {
        val clock = FakeClock()
        val session = session(idleMs = 600_000L, contactMs = 60_000L, clock = clock)
        assertTrue(session.accept(session.beginBind(), "binder"))

        val sentAt = clock.now
        clock.now += 10_000L
        session.markContact(sentAt)

        clock.now += 50_001L
        assertNull("срок контакта отсчитан от ответа, а не от отправки", session.peek())
    }

    /** Ответ вызова, отправленного раньше, не должен отматывать срок контакта назад. */
    @Test
    fun contactNeverMovesBackwards() {
        val clock = FakeClock()
        val session = session(idleMs = 600_000L, contactMs = 60_000L, clock = clock)
        assertTrue(session.accept(session.beginBind(), "binder"))

        val early = clock.now
        clock.now += 30_000L
        session.markContact(clock.now)
        session.markContact(early)

        clock.now += 40_000L
        assertEquals("binder", session.peek())
    }

    /** Опоздавший пинг не воскрешает связь: другая сторона уже могла умереть. */
    @Test
    fun lateContactDoesNotReviveExpiredSession() {
        val clock = FakeClock()
        val session = session(idleMs = 300_000L, contactMs = 60_000L, clock = clock)
        assertTrue(session.accept(session.beginBind(), "binder"))

        clock.now += 60_001L
        session.markContact(clock.now)

        assertNull(session.peek())
    }

    /**
     * Потолок на сессию целиком. Без него фоновый клиент с сохраненным tree-URI, который
     * дергает провайдер раз в минуту, удерживал бы передний план в другом профиле вечно.
     */
    @Test
    fun expiresAtAbsoluteCap() {
        val clock = FakeClock()
        val session = session(idleMs = 300_000L, contactMs = 60_000L, maxMs = 500_000L, clock = clock)
        assertTrue(session.accept(session.beginBind(), "binder"))

        repeat(20) {
            clock.now += 30_000L
            session.touch()
            session.markContact(clock.now)
        }
        assertNull("сессия обязана кончиться, как бы часто ее ни трогали", session.peek())
    }

    /** Отказ старого вызова не должен выбрасывать биндер, полученный уже после него. */
    @Test
    fun dropOnlyRemovesTheBinderItWasGiven() {
        val session = session()
        val stale = "stale"
        session.accept(session.beginBind(), stale)
        session.release()
        val fresh = "fresh"
        session.accept(session.beginBind(), fresh)

        session.drop(stale)

        assertSame(fresh, session.peek())
    }

    /** Биндер, переживший срок годности, не должен выдаваться после отметки активности. */
    @Test
    fun touchDoesNotReviveExpiredSession() {
        val clock = FakeClock()
        val session = session(idleMs = 5_000L, clock = clock)
        assertTrue(session.accept(session.beginBind(), "binder"))

        clock.now += 5_001L
        session.touch()

        assertNull(session.peek())
    }

    /**
     * Пинги, удерживающие удаленный сервис, ходят через [ShuttleSession.peek]. Если peek
     * продлевает сессию, она не истечет никогда: удерживаемый процесс в другом профиле
     * переживет уход пользователя.
     */
    @Test
    fun peekDoesNotExtendTheSession() {
        val clock = FakeClock()
        val session = session(idleMs = 300_000L, clock = clock)
        assertTrue(session.accept(session.beginBind(), "binder"))

        repeat(20) {
            clock.now += 30_000L
            session.peek()
        }
        assertNull("сессия обязана истечь по бездействию, даже если ее опрашивают", session.peek())
    }

    @Test
    fun peekDropsDeadBinder() {
        val session = session()
        session.accept(session.beginBind(), "binder")
        assertNull(session.peek { false })
        assertNull(session.peek())
    }

    @Test
    fun failedBindAllowsImmediateRetry() {
        val clock = FakeClock()
        val session = session(bindMs = 5_000L, clock = clock)
        val first = session.beginBind()
        assertEquals(ShuttleSession.NO_GENERATION, session.beginBind())

        session.failBind(first)
        val second = session.beginBind()
        assertTrue(second != ShuttleSession.NO_GENERATION && second != first)
    }

    @Test
    fun abandonedBindAttemptExpires() {
        val clock = FakeClock()
        val session = session(bindMs = 5_000L, clock = clock)
        session.beginBind()
        assertEquals(ShuttleSession.NO_GENERATION, session.beginBind())

        clock.now += 5_001L
        assertTrue(session.beginBind() != ShuttleSession.NO_GENERATION)
    }

    /** Сброс мертвого биндера не должен обесценивать уже начатую привязку. */
    @Test
    fun dropKeepsGenerationSoInFlightBindStillCounts() {
        val session = session()
        val dead = "dead"
        session.accept(session.beginBind(), dead)
        val inFlight = session.beginBind()

        session.drop(dead)

        assertNull(session.peek())
        assertTrue(session.accept(inFlight, "fresh"))
        assertEquals("fresh", session.peek())
    }

    /** Смерть старого биндера не должна выбрасывать уже принятый новый. */
    @Test
    fun discardIgnoresStaleGeneration() {
        val session = session()
        val stale = session.beginBind()
        session.release()
        session.accept(session.beginBind(), "fresh")

        session.discard(stale)
        assertEquals("fresh", session.peek())
    }

    @Test
    fun releaseDropsBinder() {
        val session = session()
        val value = "binder"
        session.accept(session.beginBind(), value)
        assertSame(value, session.peek())

        session.release()
        assertNull(session.peek())
    }
}
