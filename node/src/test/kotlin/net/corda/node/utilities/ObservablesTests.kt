package net.corda.node.utilities

import com.google.common.util.concurrent.SettableFuture
import net.corda.core.internal.bufferUntilSubscribed
import net.corda.core.internal.tee
import net.corda.core.utilities.FlowSafeSubject
import net.corda.core.internal.FlowSafeSubscriber
import net.corda.core.internal.OnNextFailedException
import net.corda.nodeapi.internal.persistence.*
import net.corda.testing.internal.configureDatabase
import net.corda.testing.node.MockServices.Companion.makeTestDataSourceProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import rx.Observable
import rx.exceptions.CompositeException
import rx.exceptions.OnErrorFailedException
import rx.exceptions.OnErrorNotImplementedException
import rx.internal.util.ActionSubscriber
import rx.observers.SafeSubscriber
import rx.observers.Subscribers
import rx.subjects.PublishSubject
import java.io.Closeable
import java.lang.IllegalStateException
import java.lang.RuntimeException
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ObservablesTests {
    private fun isInDatabaseTransaction() = contextTransactionOrNull != null
    private val toBeClosed = mutableListOf<Closeable>()

    private fun createDatabase(): CordaPersistence {
        val database = configureDatabase(makeTestDataSourceProperties(), DatabaseConfig(), { null }, { null })
        toBeClosed += database
        return database
    }

    @After
    fun after() {
        toBeClosed.forEach { it.close() }
        toBeClosed.clear()
    }

    @Test
    fun `bufferUntilDatabaseCommit delays until transaction closed`() {
        val database = createDatabase()

        val source = PublishSubject.create<Int>()
        val observable: Observable<Int> = source

        val firstEvent = SettableFuture.create<Pair<Int, Boolean>>()
        val secondEvent = SettableFuture.create<Pair<Int, Boolean>>()

        observable.first().subscribe { firstEvent.set(it to isInDatabaseTransaction()) }
        observable.skip(1).first().subscribe { secondEvent.set(it to isInDatabaseTransaction()) }

        database.transaction {
            val delayedSubject = source.bufferUntilDatabaseCommit()
            assertThat(source).isNotEqualTo(delayedSubject)
            delayedSubject.onNext(0)
            source.onNext(1)
            assertThat(firstEvent.isDone).isTrue()
            assertThat(secondEvent.isDone).isFalse()
        }
        assertThat(secondEvent.isDone).isTrue()

        assertThat(firstEvent.get()).isEqualTo(1 to true)
        assertThat(secondEvent.get()).isEqualTo(0 to false)
    }

    class TestException : Exception("Synthetic exception for tests")

    @Test
    fun `bufferUntilDatabaseCommit swallows if transaction rolled back`() {
        val database = createDatabase()

        val source = PublishSubject.create<Int>()
        val observable: Observable<Int> = source

        val firstEvent = SettableFuture.create<Pair<Int, Boolean>>()
        val secondEvent = SettableFuture.create<Pair<Int, Boolean>>()

        observable.first().subscribe { firstEvent.set(it to isInDatabaseTransaction()) }
        observable.skip(1).first().subscribe { secondEvent.set(it to isInDatabaseTransaction()) }

        try {
            database.transaction {
                val delayedSubject = source.bufferUntilDatabaseCommit()
                assertThat(source).isNotEqualTo(delayedSubject)
                delayedSubject.onNext(0)
                source.onNext(1)
                assertThat(firstEvent.isDone).isTrue()
                assertThat(secondEvent.isDone).isFalse()
                throw TestException()
            }
        } catch (e: TestException) {
        }
        assertThat(secondEvent.isDone).isFalse()

        assertThat(firstEvent.get()).isEqualTo(1 to true)
    }

    @Test
    fun `bufferUntilDatabaseCommit propagates error if transaction rolled back`() {
        val database = createDatabase()

        val source = PublishSubject.create<Int>()
        val observable: Observable<Int> = source

        val firstEvent = SettableFuture.create<Pair<Int, Boolean>>()
        val secondEvent = SettableFuture.create<Pair<Int, Boolean>>()

        observable.first().subscribe({ firstEvent.set(it to isInDatabaseTransaction()) }, {})
        observable.skip(1).subscribe({ secondEvent.set(it to isInDatabaseTransaction()) }, {})
        observable.skip(1).subscribe({}, { secondEvent.set(2 to isInDatabaseTransaction()) })

        try {
            database.transaction {
                val delayedSubject = source.bufferUntilDatabaseCommit(propagateRollbackAsError = true)
                assertThat(source).isNotEqualTo(delayedSubject)
                delayedSubject.onNext(0)
                source.onNext(1)
                assertThat(firstEvent.isDone).isTrue()
                assertThat(secondEvent.isDone).isFalse()
                throw TestException()
            }
        } catch (e: TestException) {
        }
        assertThat(secondEvent.isDone).isTrue()

        assertThat(firstEvent.get()).isEqualTo(1 to true)
        assertThat(secondEvent.get()).isEqualTo(2 to false)
    }

    @Test
    fun `bufferUntilDatabaseCommit delays until transaction closed repeatable`() {
        val database = createDatabase()

        val source = PublishSubject.create<Int>()
        val observable: Observable<Int> = source

        val firstEvent = SettableFuture.create<Pair<Int, Boolean>>()
        val secondEvent = SettableFuture.create<Pair<Int, Boolean>>()

        observable.first().subscribe { firstEvent.set(it to isInDatabaseTransaction()) }
        observable.skip(1).first().subscribe { secondEvent.set(it to isInDatabaseTransaction()) }

        database.transaction {
            val delayedSubject = source.bufferUntilDatabaseCommit()
            assertThat(source).isNotEqualTo(delayedSubject)
            delayedSubject.onNext(0)
            assertThat(firstEvent.isDone).isFalse()
            assertThat(secondEvent.isDone).isFalse()
        }
        assertThat(firstEvent.isDone).isTrue()
        assertThat(firstEvent.get()).isEqualTo(0 to false)
        assertThat(secondEvent.isDone).isFalse()

        database.transaction {
            val delayedSubject = source.bufferUntilDatabaseCommit()
            assertThat(source).isNotEqualTo(delayedSubject)
            delayedSubject.onNext(1)
            assertThat(secondEvent.isDone).isFalse()
        }
        assertThat(secondEvent.isDone).isTrue()
        assertThat(secondEvent.get()).isEqualTo(1 to false)
    }

    @Test
    fun `tee correctly copies observations to multiple observers`() {

        val source1 = PublishSubject.create<Int>()
        val source2 = PublishSubject.create<Int>()
        val source3 = PublishSubject.create<Int>()

        val event1 = SettableFuture.create<Int>()
        val event2 = SettableFuture.create<Int>()
        val event3 = SettableFuture.create<Int>()

        source1.subscribe { event1.set(it) }
        source2.subscribe { event2.set(it) }
        source3.subscribe { event3.set(it) }

        val tee = source1.tee(source2, source3)
        tee.onNext(0)

        assertThat(event1.isDone).isTrue()
        assertThat(event2.isDone).isTrue()
        assertThat(event3.isDone).isTrue()
        assertThat(event1.get()).isEqualTo(0)
        assertThat(event2.get()).isEqualTo(0)
        assertThat(event3.get()).isEqualTo(0)

        tee.onCompleted()
        assertThat(source1.hasCompleted()).isTrue()
        assertThat(source2.hasCompleted()).isTrue()
        assertThat(source3.hasCompleted()).isTrue()
    }

    /**
     * tee combines [PublishSubject]s under one PublishSubject. We need to make sure that they are not wrapped with a [SafeSubscriber].
     * Otherwise, if a non Rx exception gets thrown from a subscriber under one of the PublishSubject it will get caught by the
     * SafeSubscriber wrapping that PublishSubject and will call [PublishSubject.PublishSubjectState.onError], which will
     * eventually shut down all of the subscribers under that PublishSubject.
     */
    @Test
    fun `error in unsafe subscriber won't shutdown subscribers under same publish subject, after tee`() {
        val source1 = PublishSubject.create<Int>()
        val source2 = PublishSubject.create<Int>()
        var count = 0

        source1.subscribe { count += it } // safe subscriber
        source1.unsafeSubscribe(Subscribers.create { throw RuntimeException() }) // this subscriber should not shut down the above subscriber

        assertFailsWith<RuntimeException> {
            source1.tee(source2).onNext(1)
        }
        assertFailsWith<RuntimeException> {
            source1.tee(source2).onNext(1)
        }
        assertEquals(2, count)
    }

    @Test
    fun `FlowSafeSubject subscribes by default FlowSafeSubscribers, wrapped Observers will survive errors from onNext`() {
        var heartBeat = 0
        val source = FlowSafeSubject(PublishSubject.create<Int>())
        source.subscribe { runNo ->
            // subscribes with a FlowSafeSubscriber
            heartBeat++
            if (runNo == 1) {
                throw IllegalStateException()
            }
        }
        source.subscribe { runNo ->
            // subscribes with a FlowSafeSubscriber
            heartBeat++
            if (runNo == 2) {
                throw IllegalStateException()
            }
        }

        assertFailsWith<OnErrorNotImplementedException> {
            source.onNext(1) // first observer only will run and throw
        }
        assertFailsWith<OnErrorNotImplementedException> {
            source.onNext(2) // first observer will run, second observer will run and throw
        }
        source.onNext(3) // both observers will run
        assertThat(heartBeat == 5)
    }

    @Test
    fun `FlowSafeSubject unsubscribes FlowSafeSubscribers only upon explicitly calling onError`() {
        var heartBeat = 0
        val source = FlowSafeSubject(PublishSubject.create<Int>())
        source.subscribe { heartBeat += it }
        source.subscribe { heartBeat += it }
        source.onNext(1)
        // send an onError event
        assertFailsWith<CompositeException> {
            source.onError(IllegalStateException()) // all FlowSafeSubscribers under FlowSafeSubject get unsubscribed here
        }
        source.onNext(1)
        assertThat(heartBeat == 2)
    }

    @Test
    fun `FlowSafeSubject wrapped with a SafeSubscriber shuts down the whole structure, if one of them is unsafe and it throws`() {
        var heartBeat = 0
        val source = FlowSafeSubject(PublishSubject.create<Int>())
        source.unsafeSubscribe(Subscribers.create { runNo ->
            heartBeat++
            if (runNo == 1) {
                throw IllegalStateException()
            }
        })
        source.subscribe { heartBeat += it }
        // wrap FlowSafeSubject with a FlowSafeSubscriber
        val sourceWrapper = SafeSubscriber(Subscribers.from(source))
        assertFailsWith<OnErrorFailedException> {
            sourceWrapper.onNext(1)
        }
        sourceWrapper.onNext(2)
        assertThat(heartBeat == 1)
    }

    /**
     * A FlowSafeSubscriber that is not a leaf in a Subscribers structure, if it throws at onNext,
     * it will not call its onError, because in case it wraps a [PublishSubject] it will then call [PublishSubject.onError]
     * which will shut down all of the Subscribers under it.
     */
    @Test
    fun `FlowSafeSubject wrapped with a FlowSafeSubscriber will preserve the structure, if one of them is unsafe and it throws`() {
        var heartBeat = 0
        val source = FlowSafeSubject(PublishSubject.create<Int>())
        source.unsafeSubscribe(Subscribers.create { runNo ->
            heartBeat++
            if (runNo == 1) {
                throw IllegalStateException()
            }
        })
        source.subscribe { heartBeat++ }
        // wrap FlowSafeSubject with a FlowSafeSubscriber
        val sourceWrapper = FlowSafeSubscriber(Subscribers.from(source))
        assertFailsWith<OnNextFailedException>("Observer.onNext failed, this is a non leaf FlowSafeSubscriber, therefore onError will be skipped") {
            sourceWrapper.onNext(1)
        }
        sourceWrapper.onNext(2)
        assertThat(heartBeat == 3)
    }

    @Test
    fun `throwing FlowSafeSubscriber as a leaf will call onError`() {
        var heartBeat = 0
        val source = FlowSafeSubject(PublishSubject.create<Int>())
        // add a leaf FlowSafeSubscriber
        source.subscribe(/*onNext*/{
            heartBeat++
            throw IllegalStateException()
        },/*onError*/{
            heartBeat++
        })

        source.onNext(1)
        source.onNext(1)
        assertThat(heartBeat == 4)
    }

    @Test
    fun `throwing FlowSafeSubscriber at onNext will wrap with a Rx OnErrorNotImplementedException`() {
        val flowSafeSubscriber = FlowSafeSubscriber<Int>(Subscribers.create { throw IllegalStateException() })
        assertFailsWith<OnErrorNotImplementedException> {
            flowSafeSubscriber.onNext(1)
        }
    }

    @Test
    fun `throwing FlowSafeSubscriber at onError will wrap with a Rx OnErrorFailedException`() {
        val flowSafeSubscriber = FlowSafeSubscriber<Int>(
            ActionSubscriber(
                { throw IllegalStateException() },
                { throw IllegalStateException() },
                null
            )
        )
        assertFailsWith<OnErrorFailedException> {
            flowSafeSubscriber.onNext(1)
        }
    }

    @Test
    fun `propagated Rx exception will be rethrown at ConsistentSafeSubscriber onError`() {
        val source = FlowSafeSubject(PublishSubject.create<Int>())
        source.subscribe { throw IllegalStateException("123") } // will give a leaf FlowSafeSubscriber
        val sourceWrapper = FlowSafeSubscriber(Subscribers.from(source)) // will give an inner FlowSafeSubscriber

        assertFailsWith<OnErrorNotImplementedException>("123") {
            // IllegalStateException will be wrapped and rethrown as a OnErrorNotImplementedException in leaf FlowSafeSubscriber,
            // will be caught by inner FlowSafeSubscriber and just be rethrown
            sourceWrapper.onNext(1)
        }
    }

    @Test
    fun `combine tee and bufferUntilDatabaseCommit`() {
        val database = createDatabase()

        val source = PublishSubject.create<Int>()
        val teed = PublishSubject.create<Int>()

        val observable: Observable<Int> = source

        val firstEvent = SettableFuture.create<Pair<Int, Boolean>>()
        val teedEvent = SettableFuture.create<Pair<Int, Boolean>>()

        observable.first().subscribe { firstEvent.set(it to isInDatabaseTransaction()) }

        teed.first().subscribe { teedEvent.set(it to isInDatabaseTransaction()) }

        database.transaction {
            val delayedSubject = source.bufferUntilDatabaseCommit().tee(teed)
            assertThat(source).isNotEqualTo(delayedSubject)
            delayedSubject.onNext(0)
            assertThat(firstEvent.isDone).isFalse()
            assertThat(teedEvent.isDone).isTrue()
        }
        assertThat(firstEvent.isDone).isTrue()

        assertThat(firstEvent.get()).isEqualTo(0 to false)
        assertThat(teedEvent.get()).isEqualTo(0 to true)
    }

    @Test
    fun `new transaction open in observer when wrapped`() {
        val database = createDatabase()

        val source = PublishSubject.create<Int>()
        val observableWithDbTx: Observable<Int> = source.wrapWithDatabaseTransaction()

        val undelayedEvent = SettableFuture.create<Pair<Int, Boolean>>()
        val delayedEventFromSecondObserver = SettableFuture.create<Pair<Int, UUID?>>()
        val delayedEventFromThirdObserver = SettableFuture.create<Pair<Int, UUID?>>()

        observableWithDbTx.first().subscribe { undelayedEvent.set(it to isInDatabaseTransaction()) }

        fun observeSecondEvent(event: Int, future: SettableFuture<Pair<Int, UUID?>>) {
            future.set(event to if (isInDatabaseTransaction()) contextTransaction.id else null)
        }

        observableWithDbTx.skip(1).first().subscribe { observeSecondEvent(it, delayedEventFromSecondObserver) }
        observableWithDbTx.skip(1).first().subscribe { observeSecondEvent(it, delayedEventFromThirdObserver) }

        database.transaction {
            val commitDelayedSource = source.bufferUntilDatabaseCommit()
            assertThat(source).isNotEqualTo(commitDelayedSource)
            commitDelayedSource.onNext(0)
            source.onNext(1)
            assertThat(undelayedEvent.isDone).isTrue()
            assertThat(undelayedEvent.get()).isEqualTo(1 to true)
            assertThat(delayedEventFromSecondObserver.isDone).isFalse()
        }
        assertThat(delayedEventFromSecondObserver.isDone).isTrue()

        assertThat(delayedEventFromSecondObserver.get().first).isEqualTo(0)
        assertThat(delayedEventFromSecondObserver.get().second).isNotNull()
        assertThat(delayedEventFromThirdObserver.get().first).isEqualTo(0)
        assertThat(delayedEventFromThirdObserver.get().second).isNotNull()

        // Test that the two observers of the second event were notified inside the same database transaction.
        assertThat(delayedEventFromSecondObserver.get().second).isEqualTo(delayedEventFromThirdObserver.get().second)
    }

    @Test
    fun `check wrapping in db tx doesn't eagerly subscribe`() {
        val database = createDatabase()

        val source = PublishSubject.create<Int>()
        var subscribed = false
        val event = SettableFuture.create<Int>()

        val bufferedObservable: Observable<Int> = source.bufferUntilSubscribed().doOnSubscribe { subscribed = true }
        val databaseWrappedObservable: Observable<Int> = bufferedObservable.wrapWithDatabaseTransaction(database)

        source.onNext(0)

        assertThat(subscribed).isFalse()
        assertThat(event.isDone).isFalse()

        databaseWrappedObservable.first().subscribe { event.set(it) }
        source.onNext(1)

        assertThat(event.isDone).isTrue()
        assertThat(event.get()).isEqualTo(0)
    }

    @Test
    fun `check wrapping in db tx unsubscribes`() {
        val database = createDatabase()

        val source = PublishSubject.create<Int>()
        var unsubscribed = false

        val bufferedObservable: Observable<Int> = source.bufferUntilSubscribed().doOnUnsubscribe { unsubscribed = true }
        val databaseWrappedObservable: Observable<Int> = bufferedObservable.wrapWithDatabaseTransaction(database)

        assertThat(unsubscribed).isFalse()

        val subscription1 = databaseWrappedObservable.subscribe { }
        val subscription2 = databaseWrappedObservable.subscribe { }

        subscription1.unsubscribe()
        assertThat(unsubscribed).isFalse()

        subscription2.unsubscribe()
        assertThat(unsubscribed).isTrue()
    }

    @Test
    fun `check wrapping in db tx restarts if we pass through zero subscribers`() {
        val database = createDatabase()

        val source = PublishSubject.create<Int>()
        var unsubscribed = false

        val bufferedObservable: Observable<Int> = source.doOnUnsubscribe { unsubscribed = true }
        val databaseWrappedObservable: Observable<Int> = bufferedObservable.wrapWithDatabaseTransaction(database)

        assertThat(unsubscribed).isFalse()

        val subscription1 = databaseWrappedObservable.subscribe { }
        val subscription2 = databaseWrappedObservable.subscribe { }

        subscription1.unsubscribe()
        assertThat(unsubscribed).isFalse()

        subscription2.unsubscribe()
        assertThat(unsubscribed).isTrue()

        val event = SettableFuture.create<Int>()
        val subscription3 = databaseWrappedObservable.subscribe { event.set(it) }

        source.onNext(1)

        assertThat(event.isDone).isTrue()
        assertThat(event.get()).isEqualTo(1)

        subscription3.unsubscribe()
    }
}