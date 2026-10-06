package org.berusted.craftable.client;

import java.util.concurrent.atomic.AtomicLong;

/** Correlates asynchronous client responses; it carries no server authority. */
public final class ClientRequestSequence {
    private static final AtomicLong NEXT = new AtomicLong();

    private ClientRequestSequence() {}

    public static long next() {
        long value = NEXT.incrementAndGet();
        if (value < 0) {
            throw new IllegalStateException("Client request sequence exhausted");
        }
        return value;
    }
}
