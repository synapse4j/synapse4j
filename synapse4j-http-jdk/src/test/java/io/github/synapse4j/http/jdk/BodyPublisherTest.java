package io.github.synapse4j.http.jdk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;

import org.junit.jupiter.api.Test;

/**
 * The buffer publisher of {@link JdkHttpClient} against the Flow contract it answers to — a demand of
 * zero being an error, and a second subscription sending the whole buffer again.
 */
class BodyPublisherTest {

    @Test
    void zeroDemandErrorNotSilence() {
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
    void secondSubscriptionResendsBuffer() {
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
