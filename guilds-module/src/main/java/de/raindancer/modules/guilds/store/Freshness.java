package de.raindancer.modules.guilds.store;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps an older reading of a row from replacing a newer one. A cache that reads rows again when another server
 * announces a change has several reads in flight at once; a read that began before a write of this server
 * finished may not have seen it, and one that began before a newer read must not be installed after it.
 */
final class Freshness<K> {

    enum Verdict { INSTALL, RETRY, STALE }

    private final AtomicLong clock = new AtomicLong();
    private final Map<K, long[]> marks = new HashMap<>();

    /** Call before the read. */
    long begin() {
        return clock.incrementAndGet();
    }

    /** Call with the read's result in hand, under the lock that installs it. */
    synchronized Verdict check(K key, long started) {
        long[] mark = marks.computeIfAbsent(key, ignored -> new long[2]);
        if (started < mark[0]) {
            return Verdict.STALE;
        }
        if (started < mark[1]) {
            return Verdict.RETRY;
        }
        mark[0] = started;
        return Verdict.INSTALL;
    }

    /** Call after a write of this server to the row has committed. */
    synchronized void wrote(K key) {
        marks.computeIfAbsent(key, ignored -> new long[2])[1] = clock.incrementAndGet();
    }

    synchronized void forget(K key) {
        marks.remove(key);
    }
}
