package de.raindancer.modules.voicebridge.service;

import de.maxhenkel.voicechat.api.Group;

import java.lang.reflect.Method;

/**
 * Reads a voice chat group's password, which SVC's API does not expose — needed to keep SVC's own
 * rule that a locked group takes its password. Reaches into SVC's {@code GroupImpl}; if a future
 * SVC moves it, this answers {@code null} and locked groups stay shut (invites still work).
 */
public final class SvcPasswords {

    private SvcPasswords() {
    }

    public static String passwordOf(Group group) {
        try {
            Method inner = group.getClass().getMethod("getGroup");
            Object server = inner.invoke(group);
            Method password = server.getClass().getMethod("getPassword");
            Object value = password.invoke(server);
            return value instanceof String text ? text : null;
        } catch (ReflectiveOperationException | RuntimeException moved) {
            return null;
        }
    }
}
