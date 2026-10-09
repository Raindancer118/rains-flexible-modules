package de.raindancer.modules.chat.service;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.chat.ChatSettings;
import de.raindancer.modules.chat.rules.AdRule;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Placing a paid ad: the same freeze and quality filters as a chat line, the ad's own rules, then the charge.
 * Everything that can refuse runs before the charge, so a refused ad costs nothing and needs no refund.
 */
public final class AdService implements IChatService {

    public static final String SOURCE = "chat.ad";

    /** @param charged what was actually taken, after the price index and levers */
    public record Result(Verdict verdict, Money charged) {

        public boolean placed() {
            return verdict.isAllowed();
        }
    }

    private final Map<UUID, Long> lastAd = new ConcurrentHashMap<>();
    private final ChatQualityService quality;
    private final FreezeService freeze;
    private final LongSupplier clock;
    private final AdRule rule = new AdRule();
    private volatile ChatSettings settings;

    public AdService(ChatQualityService quality, FreezeService freeze, ChatSettings settings, LongSupplier clock) {
        this.quality = quality;
        this.freeze = freeze;
        this.clock = clock;
        settings(settings);
    }

    @Override
    public void settings(ChatSettings fresh) {
        this.settings = fresh;
    }

    /** The owner's switch for /ad. */
    public boolean enabled() {
        return settings.adsEnabled();
    }

    /** What the ad costs a player now, as the server's currency writes it; empty when it is free. */
    public String priceText() {
        Money price = Fees.quote(SOURCE, Fees.amount(settings.adsPrice()));
        return price.isPositive() ? Fees.format(price) : "";
    }

    public Result place(UUID who, String text, boolean bypassFilters, boolean bypassFreeze) {
        ChatSettings live = settings;
        long since = lastAd.containsKey(who) ? clock.getAsLong() - lastAd.get(who) : Long.MAX_VALUE;
        Verdict verdict = rule.judge(live.adsEnabled(), text, live.adLength(), bypassFilters ? Long.MAX_VALUE : since,
                live.adCooldown());
        if (verdict.isRefused()) {
            return new Result(verdict, Money.ZERO);
        }
        if (freeze.isFrozen() && !bypassFreeze) {
            return new Result(Verdict.refused("chat.frozen"), Money.ZERO);
        }
        verdict = quality.check(who, text, bypassFilters);
        if (verdict.isRefused()) {
            return new Result(verdict, Money.ZERO);
        }
        Money written = Fees.amount(live.adsPrice());
        EconomyResult paid = Fees.charge(who, written, "Chat advertisement", SOURCE);
        if (!paid.succeeded()) {
            String reason = switch (paid.outcome()) {
                case NOT_ENOUGH -> "chat.ad.cannot-afford";
                case UNAVAILABLE -> "chat.ad.no-economy";
                default -> "chat.ad.not-charged";
            };
            return new Result(Verdict.refused(reason, Fees.format(paid.amount())), Money.ZERO);
        }
        quality.recordSent(who, text);
        lastAd.put(who, clock.getAsLong());
        return new Result(Verdict.allowed(), paid.amount());
    }

    public void forget(UUID who) {
        lastAd.remove(who);
    }

    @Override
    public String describe() {
        return "paid /ad broadcasts";
    }
}
