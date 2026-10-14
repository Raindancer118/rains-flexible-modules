package de.raindancer.modules.chat.service;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Says once per server and kind that a message from another server was ignored, so a forger cannot flood the log. */
final class Rejected {

    private static final LogChannel log = Log.of("chat");

    private final Set<String> told = ConcurrentHashMap.newKeySet();

    void of(String server, String what) {
        if (told.add(server + " " + what)) {
            log.warn("Ignored '{}' from server '{}': it speaks for a player who is not on that server.", what, server);
        }
    }
}
