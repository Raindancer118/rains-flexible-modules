package de.raindancer.modules.voicebridge.rules;

import de.raindancer.modules.voicebridge.model.PendingLink;

import java.util.Locale;

/** Whether what somebody typed into Discord is this pending code, still in time. */
public final class LinkCodeRule implements IVoiceBridgeRule {

    public boolean accepts(PendingLink pending, String typed, long now) {
        if (pending == null || typed == null || now >= pending.expiresAt()) {
            return false;
        }
        return pending.code().equals(typed.strip().toUpperCase(Locale.ROOT));
    }

    @Override
    public String describe() {
        return "a link code matches what was shown and has not run out";
    }
}
