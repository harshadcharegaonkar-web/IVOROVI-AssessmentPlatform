package com.ivorovi.platform.process;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * This instance's request statistics for the heartbeat (follow-up B): requests in flight, and over the last minute the
 * number of API requests, server errors (5xx or an exception) and the 95th-percentile latency. Six 10-second slots with a
 * fixed latency histogram; lock-free on the request path except for one short slot rotation every 10 seconds.
 */
@Component
public class RequestStats {

    public record Snapshot(int inFlight, int requests, int errors, int p95Ms) {
        public double errorRate() {
            return requests == 0 ? 0 : (double) errors / requests;
        }
    }

    /** Upper bounds (ms) of the latency buckets; the last bucket is open-ended. */
    static final int[] BOUNDS = {5, 10, 25, 50, 100, 250, 500, 1000, 1500, 2000, 3000, 5000, 10000, 30000};
    private static final int SLOTS = 6;
    private static final long SLOT_MS = 10_000;

    private final Clock clock;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Slot[] slots = new Slot[SLOTS];

    private static final class Slot {
        volatile long epoch = -1;
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger errors = new AtomicInteger();
        final AtomicInteger[] latency = new AtomicInteger[BOUNDS.length + 1];

        Slot() {
            for (int i = 0; i < latency.length; i++) latency[i] = new AtomicInteger();
        }
    }

    RequestStats() {
        this(Clock.systemUTC());
    }

    RequestStats(Clock clock) {
        this.clock = clock;
        for (int i = 0; i < SLOTS; i++) slots[i] = new Slot();
    }

    public void started() {
        inFlight.incrementAndGet();
    }

    public void finished(long durationMs, boolean serverError) {
        inFlight.decrementAndGet();
        record(durationMs, serverError);
    }

    /** Records one completed request (without the in-flight bookkeeping). */
    public void record(long durationMs, boolean serverError) {
        Slot s = current();
        s.requests.incrementAndGet();
        if (serverError) s.errors.incrementAndGet();
        s.latency[bucket(durationMs)].incrementAndGet();
    }

    public Snapshot snapshot() {
        long now = clock.millis() / SLOT_MS;
        int requests = 0;
        int errors = 0;
        int[] hist = new int[BOUNDS.length + 1];
        for (Slot s : slots) {
            if (s.epoch <= now - SLOTS || s.epoch > now) continue;
            requests += s.requests.get();
            errors += s.errors.get();
            for (int i = 0; i < hist.length; i++) hist[i] += s.latency[i].get();
        }
        return new Snapshot(Math.max(0, inFlight.get()), requests, errors, p95(hist, requests));
    }

    static int p95(int[] hist, int total) {
        if (total == 0) return 0;
        long rank = (long) Math.ceil(total * 0.95);
        long seen = 0;
        for (int i = 0; i < hist.length; i++) {
            seen += hist[i];
            if (seen >= rank) return i < BOUNDS.length ? BOUNDS[i] : BOUNDS[BOUNDS.length - 1] * 2;
        }
        return BOUNDS[BOUNDS.length - 1] * 2;
    }

    private static int bucket(long ms) {
        for (int i = 0; i < BOUNDS.length; i++) if (ms <= BOUNDS[i]) return i;
        return BOUNDS.length;
    }

    private Slot current() {
        long epoch = clock.millis() / SLOT_MS;
        Slot s = slots[(int) (epoch % SLOTS)];
        if (s.epoch != epoch) {
            synchronized (s) {
                if (s.epoch != epoch) {
                    s.requests.set(0);
                    s.errors.set(0);
                    for (AtomicInteger a : s.latency) a.set(0);
                    s.epoch = epoch;
                }
            }
        }
        return s;
    }
}
