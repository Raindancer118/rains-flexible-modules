package de.raindancer.modules.chat.service;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.modules.chat.ChatSettings;
import de.raindancer.modules.chat.rules.ChatQualityRules;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Whether a chat line may go out at all: shouting, repeating the last thing you said, and the two
 * cooldowns — the everyday per-player one an owner sets, and the server-wide one {@code /chat
 * slowmode} raises for a busy moment.
 *
 * <h2>Why bypass is a parameter, not read here</h2>
 * Whether somebody holds {@link de.raindancer.modules.chat.util.PermissionNodes#BYPASS_FILTERS} is a
 * question about a {@code Player}, and this class never touches one — everything it decides comes
 * from a uuid and a string, which is what makes {@link #check} testable without a server. The
 * listener asks the permission and hands the answer in.
 */
public final class ChatQualityService implements IChatService {

    private record LastMessage(String text, long at) {
    }

    private final Map<UUID, LastMessage> lastByPlayer = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    /** {@code -1} means "use the settings default"; {@code 0} or higher is an explicit override. */
    private volatile long slowmodeOverrideSeconds = -1;

    private volatile ChatSettings settings;

    public ChatQualityService(ChatSettings settings) {
        this(settings, System::currentTimeMillis);
    }

    /** For a test that wants to control time rather than race a real clock. */
    ChatQualityService(ChatSettings settings, LongSupplier clock) {
        this.clock = clock;
        settings(settings);
    }

    @Override
    public void settings(ChatSettings fresh) {
        this.settings = fresh;
    }

    /** Whether this line may be sent — checked before anything is recorded. */
    public Verdict check(UUID player, String text, boolean bypass) {
        if (bypass) {
            return Verdict.allowed();
        }
        Verdict verdict = Verdict.allowed();
        if (settings.capsFilterEnabled()) {
            verdict = verdict.and(
                    ChatQualityRules.caps(text, settings.capsThreshold(), settings.capsMinimumLength()));
        }
        LastMessage last = lastByPlayer.get(player);
        if (last != null) {
            if (settings.repeatBlockEnabled()) {
                verdict = verdict.and(ChatQualityRules.repeat(text, last.text()));
            }
            long elapsed = clock.getAsLong() - last.at();
            verdict = verdict.and(ChatQualityRules.cooldown(elapsed, settings.messageCooldown()));
            verdict = verdict.and(ChatQualityRules.slowmode(elapsed, effectiveSlowmode()));
        }
        return verdict;
    }

    /** Remembers this line as the player's last, for the next line's repeat and cooldown checks. */
    public void recordSent(UUID player, String text) {
        lastByPlayer.put(player, new LastMessage(text, clock.getAsLong()));
    }

    /** The slowmode actually in force: {@code /chat slowmode}'s override, or the settings default. */
    public int effectiveSlowmode() {
        long override = slowmodeOverrideSeconds;
        return override >= 0 ? (int) override : settings.defaultSlowmode();
    }

    public boolean isSlowmodeOverridden() {
        return slowmodeOverrideSeconds >= 0;
    }

    /** {@code /chat slowmode <seconds>} — in force until {@link #clearSlowmodeOverride} or a restart. */
    public void overrideSlowmode(int seconds) {
        this.slowmodeOverrideSeconds = Math.max(0, seconds);
    }

    /** {@code /chat slowmode off} — back to whatever the settings default is. */
    public void clearSlowmodeOverride() {
        this.slowmodeOverrideSeconds = -1;
    }

    public void forget(UUID player) {
        lastByPlayer.remove(player);
    }

    @Override
    public String describe() {
        return "whether a chat line may go out: shouting, repeats, cooldown and slowmode";
    }
}
