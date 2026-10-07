package de.raindancer.modules.voicebridge.service;

import de.raindancer.modules.voicebridge.VoiceBridgeSettings;
import de.raindancer.modules.voicebridge.model.PendingLink;
import de.raindancer.modules.voicebridge.rules.LinkCodeRule;
import de.raindancer.modules.voicebridge.store.LinkStore;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Linking a Discord account to a player: the player asks for a code in game, types it into Discord's
 * {@code /link}, and from then on that account's voice is that player's.
 */
public final class LinkService implements IVoiceBridgeService {

    public static final long CODE_LIFETIME_MILLIS = 10 * 60 * 1000L;
    public static final int MOST_WRONG_GUESSES = 5;
    /** Across every account: 31^6 codes against 30 guesses per 10 minutes is never going to land. */
    public static final int MOST_WRONG_GUESSES_OVERALL = 30;

    /** No 0/O, 1/I/L: a code is read off a screen and typed somewhere else. */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private record Guesses(int wrong, long since) {
    }

    private final LinkStore store;
    private final LinkCodeRule rule;
    private final Random random;
    private final LongSupplier clock;
    private final Map<UUID, PendingLink> pending = new HashMap<>();
    private final Map<Long, Guesses> guesses = new HashMap<>();
    private Guesses overall = new Guesses(0, 0);

    public LinkService(LinkStore store, LinkCodeRule rule, Random random, LongSupplier clock) {
        this.store = store;
        this.rule = rule;
        this.random = random;
        this.clock = clock;
    }

    @Override
    public void settings(VoiceBridgeSettings settings) {
        // Nothing here is configurable; codes and their lifetime are a security choice, not a preference.
    }

    public synchronized String codeFor(UUID player) {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        pending.put(player, new PendingLink(code.toString(), player, clock.getAsLong() + CODE_LIFETIME_MILLIS));
        return code.toString();
    }

    /** The player now linked to this Discord account, or empty for a wrong, used or expired code. */
    public synchronized Optional<UUID> redeem(String typed, long discordUser) {
        long now = clock.getAsLong();
        Guesses tried = guesses.get(discordUser);
        if (tried != null && now - tried.since() >= CODE_LIFETIME_MILLIS) {
            guesses.remove(discordUser);
            tried = null;
        }
        if (now - overall.since() >= CODE_LIFETIME_MILLIS) {
            overall = new Guesses(0, now);
        }
        if ((tried != null && tried.wrong() >= MOST_WRONG_GUESSES) || overall.wrong() >= MOST_WRONG_GUESSES_OVERALL) {
            return Optional.empty();
        }
        pending.values().removeIf(link -> now >= link.expiresAt());
        for (PendingLink link : pending.values()) {
            if (rule.accepts(link, typed, now)) {
                pending.remove(link.player());
                guesses.remove(discordUser);
                store.link(discordUser, link.player());
                return Optional.of(link.player());
            }
        }
        overall = new Guesses(overall.wrong() + 1, overall.since());
        guesses.put(discordUser, tried == null ? new Guesses(1, now) : new Guesses(tried.wrong() + 1, tried.since()));
        return Optional.empty();
    }

    public boolean unlink(UUID player) {
        return store.unlink(player);
    }

    public Optional<Long> discordOf(UUID player) {
        return store.discordOf(player);
    }

    public Optional<UUID> playerOf(long discordUser) {
        return store.playerOf(discordUser);
    }
}
