package de.raindancer.modules.api;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A stack of things to close, closed in the reverse order they were added.
 *
 * <p>Reverse order is a correctness requirement, not tidiness: a module that opened a database and then
 * started a timer reading from it has to stop the timer first, or the next tick reads a closed connection
 * and throws on the scheduler thread, far away from the shutdown that caused it.
 *
 * <p>And every resource is released even when an earlier one throws. A try-with-resources chain stops at
 * the first failure and silently abandons the rest, which during a server shutdown means unflushed data.
 * So the failures are collected and returned rather than thrown, and the caller decides how loudly to
 * complain.
 *
 * <p>Not thread-safe on purpose: a module's registrations happen on the server thread and so does its
 * shutdown. Synchronising would suggest otherwise.
 */
public final class Unwind {

    private final Deque<AutoCloseable> resources = new ArrayDeque<>();
    private boolean closed;

    /**
     * Adds something to close later.
     *
     * <p>A null is ignored, so a caller may pass the result of something that may not have been created.
     * Something added after {@link #close()} is closed immediately: there will be no second unwind to
     * catch it, and leaving it open would read as registered.
     */
    public void add(AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        if (closed) {
            swallow(resource);
            return;
        }
        resources.push(resource);
    }

    /** How many resources are waiting to be closed. */
    public int size() {
        return resources.size();
    }

    /**
     * Closes everything, most recent first.
     *
     * @return whatever was thrown on the way, in the order it happened — empty when all went well
     */
    public List<Throwable> close() {
        closed = true;
        List<Throwable> trouble = new ArrayList<>();
        while (!resources.isEmpty()) {
            AutoCloseable resource = resources.pop();
            try {
                resource.close();
            } catch (Throwable caught) {
                trouble.add(caught);
            }
        }
        return trouble;
    }

    private static void swallow(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Throwable ignored) {
            // Nowhere to report it: this is a resource handed over after the module was already gone.
        }
    }
}
