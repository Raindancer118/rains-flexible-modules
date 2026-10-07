package de.raindancer.modules.voicebridge.rules;

import de.raindancer.modules.voicebridge.model.PendingLink;

import java.util.Locale;

/** Whether what somebody typed into Discord is this pending code, still in time. */
public final class LinkCodeRule implements IVoiceBridgeRule {

    public boolean accepts(PendingLink pending, String typed, long now) {
        if (pending == null || typed == null || now >= pending.expiresAt()) {
            return false;
        }
        return normal(pending.code()).equals(normal(typed));
    }

    /** The dash is only there to read the code off a screen; typed without it, it is the same code. */
    private static String normal(String code) {
        return code.strip().replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
    }

    @Override
    public String describe() {
        return "a link code matches what was shown and has not run out";
    }
}
