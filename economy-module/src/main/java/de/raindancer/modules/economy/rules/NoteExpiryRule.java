package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;

/** What a banknote is worth when it is paid in, once it may have expired. */
public final class NoteExpiryRule implements IEconomyRule {

    private static final long DAY = 86_400_000L;

    /** @param days 0 for notes that never expire */
    public Money worth(Money face, long issuedAt, long now, int days, int percent) {
        if (days <= 0 || now - issuedAt <= days * DAY) {
            return face;
        }
        return face.share(Math.clamp(percent, 0, 100) / 100.0);
    }

    @Override
    public String describe() {
        return "what an old banknote is worth";
    }
}
