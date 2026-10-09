package de.raindancer.modules.speedrun;

import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The entry fee and the prize pot of a run.
 *
 * <p>Who races is fixed when a run starts, so that is when every racer pays {@code economy.entry-fee}; the
 * fees form the pot, which {@link SpeedrunEntryLedger} keeps on disk. A run that is cancelled, reset or
 * stopped with the plugin, or that ends with nobody winning, gives the fees back; a run that is won pays the
 * pot minus the house cut by place ({@link SpeedrunPrizeRules}). A pot found on disk when the module starts
 * belongs to a run the server did not outlive, and is refunded. With the fee at 0 none of this touches the
 * economy and no economy is needed.
 *
 * <p>A payout the treasury refuses is logged and the winner told — the house cut already stayed with the
 * server, so what is refused is held by nobody and staff settle it by hand.
 */
public final class SpeedrunEntryFees {

    static final String ENTRY = "speedrun.entry";
    static final String PRIZE = "speedrun.prize";

    /** Tells a player something, if they are online. */
    @FunctionalInterface
    public interface Notifier {
        void tell(UUID player, String key, Object... pairs);
    }

    private final SpeedrunEntryLedger ledger;
    private final Notifier notifier;
    private final Consumer<String> log;
    private final Supplier<SpeedrunSettings> settings;
    private final SpeedrunPrizeRules rules = new SpeedrunPrizeRules();

    public SpeedrunEntryFees(SpeedrunEntryLedger ledger, Notifier notifier, Consumer<String> log,
                             Supplier<SpeedrunSettings> settings) {
        this.ledger = ledger;
        this.notifier = notifier;
        this.log = log;
        this.settings = settings;
    }

    private Money written() {
        return Fees.amount(settings.get().entryFee());
    }

    /** Whether there is an entry fee at all. */
    public boolean active() {
        return written().isPositive();
    }

    /** Whoever could not pay the fee right now, each told so; nobody is charged. */
    public List<UUID> unaffordable(Collection<UUID> racers) {
        Money written = written();
        if (!written.isPositive()) {
            return List.of();
        }
        Money quote = Fees.quote(ENTRY, written);
        Optional<Economy> economy = Economies.current();
        List<UUID> broke = new ArrayList<>();
        for (UUID racer : racers) {
            if (ledger.has(racer)) {
                continue;
            }
            if (economy.isEmpty() || !economy.get().has(racer, quote)) {
                broke.add(racer);
                notifier.tell(racer, "speedrun.entry.cannot-pay", "amount", Fees.format(quote));
            }
        }
        return broke;
    }

    /**
     * Takes the fee from every racer, all or none.
     *
     * @return why it could not be done, or empty when everybody paid (or nobody has to)
     */
    public Optional<String> charge(Collection<UUID> racers) {
        Money written = written();
        if (!written.isPositive()) {
            return Optional.empty();
        }
        List<UUID> charged = new ArrayList<>();
        for (UUID racer : racers) {
            if (ledger.has(racer)) {
                continue;
            }
            Optional<String> refusal = chargeOne(racer, written, false);
            if (refusal.isPresent()) {
                charged.forEach(this::refundOne);
                return refusal;
            }
            charged.add(racer);
        }
        String pot = Fees.format(ledger.pot());
        charged.forEach(racer -> notifier.tell(racer, "speedrun.entry.paid",
                "amount", Fees.format(Fees.quote(ENTRY, written)), "pot", pot));
        return Optional.empty();
    }

    /** A racer who joins a run under way pays too. @return why they could not, or empty */
    public Optional<String> chargeOne(UUID racer) {
        Money written = written();
        if (!written.isPositive() || ledger.has(racer)) {
            return Optional.empty();
        }
        return chargeOne(racer, written, true);
    }

    private Optional<String> chargeOne(UUID racer, Money written, boolean tell) {
        EconomyResult result = Fees.charge(racer, written, "Speedrun entry fee", ENTRY);
        if (!result.succeeded()) {
            String reason = result.outcome() == EconomyResult.Outcome.NOT_ENOUGH
                    ? "cannot pay the entry fee of " + Fees.format(result.amount())
                    : "the entry fee could not be taken (" + result.outcome() + ")";
            notifier.tell(racer, "speedrun.entry.cannot-pay", "amount", Fees.format(result.amount()));
            return Optional.of(reason);
        }
        if (result.amount().isPositive() && !ledger.paid(racer, result.amount())) {
            Fees.refund(racer, result.amount(), "Speedrun entry fee returned", ENTRY);
            return Optional.of("the entry could not be saved, so the fee was returned");
        }
        if (tell) {
            notifier.tell(racer, "speedrun.entry.paid", "amount", Fees.format(result.amount()),
                    "pot", Fees.format(ledger.pot()));
        }
        return Optional.empty();
    }

    /** Gives one racer's fee back, if they paid one. */
    public void refundOne(UUID racer) {
        ledger.take(racer).ifPresent(taken -> {
            EconomyResult result = Fees.refund(racer, taken, "Speedrun entry fee returned", ENTRY);
            if (result.succeeded()) {
                notifier.tell(racer, "speedrun.entry.refunded", "amount", Fees.format(taken));
            } else {
                ledger.putBack(racer, taken);
                log.accept("Could not refund " + Fees.format(taken) + " to " + racer + " (" + result.outcome()
                        + "); kept in the pot.");
            }
        });
    }

    /** Gives every fee back and empties the pot. */
    public void refundAll(String why) {
        if (ledger.isEmpty()) {
            return;
        }
        log.accept("Refunding the speedrun entry fees: " + why + ".");
        ledger.entries().keySet().forEach(this::refundOne);
        ledger.clear();
    }

    /** Pays the pot minus the house cut by place, and empties it. */
    public void payOut(List<List<UUID>> places) {
        Money pot = ledger.pot();
        if (!pot.isPositive()) {
            ledger.clear();
            return;
        }
        SpeedrunSettings live = settings.get();
        Map<UUID, Money> prizes = rules.payouts(pot, live.houseCutPercent(), live.prizeSplit(), places);
        Optional<Economy> economy = Economies.current();
        prizes.forEach((racer, amount) -> {
            EconomyResult result = economy.isEmpty()
                    ? EconomyResult.failed(EconomyResult.Outcome.UNAVAILABLE, amount, Money.ZERO)
                    : economy.get().deposit(racer, amount, "Speedrun prize", PRIZE);
            if (result.succeeded()) {
                notifier.tell(racer, "speedrun.entry.prize", "amount", Fees.format(amount));
            } else {
                log.accept("Prize of " + Fees.format(amount) + " for " + racer + " was refused ("
                        + result.outcome() + ") and is not paid.");
                notifier.tell(racer, "speedrun.entry.prize-refused", "amount", Fees.format(amount));
            }
        });
        ledger.clear();
    }
}
