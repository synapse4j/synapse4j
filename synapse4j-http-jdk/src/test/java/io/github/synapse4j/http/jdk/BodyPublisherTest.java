package io.github.synapse4j.http.jdk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.synapse4j.http.HttpBody;

/**
 * The two body publishers of {@link JdkHttpClient} against the Flow contract they answer to —
 * demand, resubscription and the release of a producer parked for demand.
 */
class BodyPublisherTest {

    @Test
    void aDemandOfZeroIsAnErrorRatherThanSilence() {
        JdkHttpClient.OneBufferPublisher publisher = new JdkHttpClient.OneBufferPublisher(
                ByteBuffer.wrap(new byte[] { 1, 2, 3 }));
        RecordingSubscriber subscriber = new RecordingSubscriber();

        publisher.subscribe(subscriber);
        subscriber.subscription.request(0);

        assertEquals(1, subscriber.errors.size());
        assertInstanceOf(IllegalArgumentException.class, subscriber.errors.get(0));
        assertTrue(subscriber.received.isEmpty());
        assertFalse(subscriber.completed);
    }

    @Test
    void aSecondSubscriptionSendsTheWholeBufferAgain() {
        // The path a direct buffer takes: no array to hand over, so the publisher carries the
        // bytes itself — and a retry or a redirect subscribes again to the same content. What
        // the first subscriber drained belongs to the first subscriber alone.
        ByteBuffer direct = ByteBuffer.allocateDirect(4);
        direct.put(new byte[] { 1, 2, 3, 4 });
        direct.flip();
        JdkHttpClient.OneBufferPublisher publisher = new JdkHttpClient.OneBufferPublisher(direct);
        RecordingSubscriber first = new RecordingSubscriber();
        RecordingSubscriber second = new RecordingSubscriber();

        publisher.subscribe(first);
        first.subscription.request(1);
        publisher.subscribe(second);
        second.subscription.request(1);

        assertArrayEquals(new byte[] { 1, 2, 3, 4 }, first.received.get(0));
        assertArrayEquals(new byte[] { 1, 2, 3, 4 }, second.received.get(0));
    }

    @Test
    void aDemandOfZeroReleasesAProducerParkedForDemand() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        HttpBody body = sink -> {
            writing.countDown();
            try {
                sink.write(7); // has no demand to write under, and waits for one
                sink.write(8);
            } catch (IOException released) {
                cancelled.countDown();
                throw released; // the way out; the run above keeps it quiet
            }
        };
        JdkHttpClient.StreamingBodyPublisher publisher = new JdkHttpClient.StreamingBodyPublisher(body);
        RecordingSubscriber subscriber = new RecordingSubscriber();

        publisher.subscribe(subscriber);
        assertTrue(writing.await(5, TimeUnit.SECONDS));
        subscriber.subscription.request(0);

        // The error is half of it: the producer, waiting for a demand that will now never come,
        // must be released with it — the body's own write failing as cancelled is that release,
        // and without it the run would sit parked on a virtual thread forever.
        assertEquals(1, subscriber.errors.size());
        assertInstanceOf(IllegalArgumentException.class, subscriber.errors.get(0));
        assertTrue(cancelled.await(5, TimeUnit.SECONDS));
    }

    @Test
    void aSecondRunWaitsForTheFirstToLeaveTheBody() throws Exception {
        // The premise of the lock: a retry or a redirect subscribes again while the run before it
        // may still be inside the body, and HttpBody promises its calls are sequential.
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondInside = new CountDownLatch(1);
        CountDownLatch secondWrote = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        AtomicBoolean firstRun = new AtomicBoolean(true);
        HttpBody body = sink -> {
            int now = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            try {
                boolean isFirst = firstRun.getAndSet(false);
                if (isFirst) {
                    firstInside.countDown();
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("the test never released the first write");
                    }
                } else {
                    secondInside.countDown();
                }
                sink.write(now);
                if (!isFirst) {
                    // The chunk is delivered once the write returns, so the signal has to follow it:
                    // awaiting the second run's entry into the body would read the receipt too soon.
                    secondWrote.countDown();
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                throw new IOException(stopped);
            } finally {
                concurrent.decrementAndGet();
            }
        };
        JdkHttpClient.StreamingBodyPublisher publisher = new JdkHttpClient.StreamingBodyPublisher(body);
        RecordingSubscriber first = new RecordingSubscriber();
        RecordingSubscriber second = new RecordingSubscriber();

        publisher.subscribe(first);
        first.subscription.request(1);
        assertTrue(firstInside.await(5, TimeUnit.SECONDS));
        publisher.subscribe(second);
        second.subscription.request(1);

        // The second run may not set foot in the body while the first is inside it: entering
        // together is two interleaved writes of one body's bytes.
        assertFalse(secondInside.await(250, TimeUnit.MILLISECONDS));
        releaseFirst.countDown();
        assertTrue(secondWrote.await(5, TimeUnit.SECONDS));
        assertEquals(1, maxConcurrent.get());
        assertEquals(1, first.received.size());
        assertEquals(1, second.received.size());
    }

    @Test
    void aCancelledRunLeavesTheBodyAndTheNextRunProceeds() throws Exception {
        // A redirect or a retry cancels the run it abandons, and cancellation is what has to get that
        // run out of the body: the run that follows waits on the same lock, and nothing else releases
        // it. This is the path a run parked on a demand that will never come has to take.
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch secondInside = new CountDownLatch(1);
        CountDownLatch secondWrote = new CountDownLatch(1);
        AtomicBoolean firstRun = new AtomicBoolean(true);
        HttpBody body = sink -> {
            if (firstRun.getAndSet(false)) {
                firstInside.countDown();
                // No demand will ever arrive for this run, so the write itself is where it waits —
                // and the cancellation is what has to break that wait.
                sink.write(1);
            } else {
                secondInside.countDown();
                sink.write(2);
                secondWrote.countDown();
            }
        };
        JdkHttpClient.StreamingBodyPublisher publisher = new JdkHttpClient.StreamingBodyPublisher(body);
        RecordingSubscriber first = new RecordingSubscriber();
        RecordingSubscriber second = new RecordingSubscriber();

        publisher.subscribe(first);
        assertTrue(firstInside.await(5, TimeUnit.SECONDS));
        publisher.subscribe(second);
        second.subscription.request(1);
        assertFalse(secondInside.await(250, TimeUnit.MILLISECONDS));

        first.subscription.cancel();

        // The cancelled run leaves at its next chunk and releases the lock, and only then does the
        // run behind it write. Awaiting the second write's own signal rather than its entry into the
        // body is what makes the chunk it delivered visible here.
        assertTrue(secondWrote.await(5, TimeUnit.SECONDS));
        assertEquals(1, second.received.size());
    }

    /** A subscriber that records every signal and drains what it is handed, as the JDK does. */
    private static final class RecordingSubscriber implements Flow.Subscriber<ByteBuffer> {

        private final List<byte[]> received = new ArrayList<>();

        private final List<Throwable> errors = new ArrayList<>();

        private volatile Flow.Subscription subscription;

        private boolean completed;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
        }

        @Override
        public void onNext(ByteBuffer item) {
            byte[] bytes = new byte[item.remaining()];
            item.get(bytes);
            received.add(bytes);
        }

        @Override
        public void onError(Throwable throwable) {
            errors.add(throwable);
        }

        @Override
        public void onComplete() {
            completed = true;
        }
    }

}
