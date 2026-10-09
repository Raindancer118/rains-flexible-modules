package de.raindancer.modules.moderation.service;

import de.raindancer.core.moderation.punishment.Punishment;
import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.modules.moderation.rules.BuyoffRule;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** A muted player paying to be heard again: the quote, then — once they have confirmed — the charge and the lift. */
public final class BuyoffService implements IModerationService {

    public static final String SOURCE = "moderation." + "mute-buyoff";

    /** What it would cost: the price as charged, and for how many started hours. */
    public record Quote(Money price, long hours, Duration remaining) {
    }

    public enum Status { DONE, REFUSED, CANNOT_PAY, FAILED }

    public record Outcome(Status status, Verdict verdict, Quote quote, EconomyResult payment) {
    }

    /** Ends the mute; false when it was already over. */
    @FunctionalInterface
    public interface Lifter {
        boolean lift(UUID player, String name, String why);
    }

    private final Function<UUID, Optional<Punishment>> activeMute;
    private final Lifter lifter;
    private final BuyoffRule rule = new BuyoffRule();

    private volatile ModerationSettings settings;

    public BuyoffService(Function<UUID, Optional<Punishment>> activeMute, Lifter lifter, ModerationSettings settings) {
        this.activeMute = activeMute;
        this.lifter = lifter;
        settings(settings);
    }

    /** Whether this player may buy their mute off, and what it would cost. */
    public Verdict mayBuyOff(UUID player) {
        return check(player, Instant.now()).verdict();
    }

    public Optional<Quote> quote(UUID player) {
        Check check = check(player, Instant.now());
        return check.verdict().isAllowed() ? Optional.of(check.quote()) : Optional.empty();
    }

    /** Charges and lifts; the caller has asked the player to confirm first. */
    public Outcome buyOff(UUID player, String name) {
        Check check = check(player, Instant.now());
        if (check.verdict().isRefused()) {
            return new Outcome(Status.REFUSED, check.verdict(), null, null);
        }
        Quote quote = check.quote();
        EconomyResult paid = Fees.charge(player, perHour().times(quote.hours()), "Mute bought off", SOURCE);
        if (!paid.succeeded()) {
            return new Outcome(Status.CANNOT_PAY, null, quote, paid);
        }
        if (!lifter.lift(player, name, "bought off for " + Fees.format(paid.amount()))) {
            Fees.refund(player, paid.amount(), "Mute already over", SOURCE);
            return new Outcome(Status.FAILED, null, quote, paid);
        }
        return new Outcome(Status.DONE, null, quote, paid);
    }

    private record Check(Verdict verdict, Quote quote) {
    }

    private Check check(UUID player, Instant now) {
        Optional<Punishment> mute = activeMute.apply(player);
        Money perHour = perHour();
        Duration total = mute.filter(one -> one.endsAt() != null)
                .map(one -> Duration.between(one.givenAt(), one.endsAt())).orElse(null);
        Optional<Duration> left = mute.flatMap(one -> one.remainingAt(now));
        Verdict verdict = rule.mayBuyOff(perHour, settings.muteBuyoffLongestHours(), mute.isPresent(), total, left);
        if (verdict.isRefused()) {
            return new Check(verdict, null);
        }
        Money written = rule.price(perHour, left.orElseThrow());
        long hours = written.minor() / perHour.minor();
        return new Check(verdict, new Quote(Fees.quote(SOURCE, written), hours, left.orElseThrow()));
    }

    private Money perHour() {
        return Fees.amount(settings.muteBuyoffPerHour());
    }

    @Override
    public void settings(ModerationSettings settings) {
        this.settings = settings == null ? ModerationSettings.DEFAULTS : settings;
    }

    @Override
    public String describe() {
        return "buying off a temporary mute, per started hour left";
    }
}
