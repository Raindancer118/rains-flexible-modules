package de.raindancer.modules.economy.service;

import de.raindancer.core.social.economy.EconomyLever;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.Fund;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.rules.FundRule;
import de.raindancer.modules.economy.store.SupplyBook;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Community funds: everybody donates toward a goal, the money is destroyed, and a full fund's effect happens —
 * a boost to every payout for a while (through Core's levers), or console commands. A money sink people want
 * to pay into.
 *
 * <p>{@link #load} and the writes behind {@link #create} and {@link #donate} talk to the database.
 */
public final class FundService implements IEconomyService, EconomyLever {

    public static final String SOURCE = de.raindancer.modules.economy.model.Sources.FUND;

    /** What came of a donation, and how much was actually given. */
    public record Donation(Outcome outcome, Money given) {
        public enum Outcome { GIVEN, FILLED, OFF, NO_SUCH_FUND, NOT_ENOUGH, REFUSED }
    }

    private final RainEconomy economy;
    private final SupplyBook store;
    private final LongSupplier clock;
    private final BooleanSupplier enabled;
    private final Consumer<Fund> onFilled;
    private final FundRule rule = new FundRule();
    private final Map<UUID, Fund> funds = new ConcurrentHashMap<>();

    /**
     * @param enabled  whether funds are switched on now
     * @param onFilled told once, when a fund fills — runs its commands, tells everybody
     */
    public FundService(RainEconomy economy, SupplyBook store, LongSupplier clock, BooleanSupplier enabled,
                       Consumer<Fund> onFilled) {
        this.economy = economy;
        this.store = store;
        this.clock = clock;
        this.enabled = enabled;
        this.onFilled = onFilled;
    }

    @Override
    public void settings(EconomySettings updated) {
        // Funds read their switch through the supply settings, live.
    }

    public void load() {
        store.funds().forEach(fund -> funds.put(fund.id(), fund));
    }

    /** Funds still collecting, oldest first. */
    public List<Fund> running() {
        return funds.values().stream().filter(fund -> !fund.done())
                .sorted(java.util.Comparator.comparingLong(Fund::created)).toList();
    }

    public List<Fund> all() {
        return funds.values().stream().sorted(java.util.Comparator.comparingLong(Fund::created)).toList();
    }

    public Optional<Fund> find(String name) {
        String wanted = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        return funds.values().stream().filter(fund -> fund.name().toLowerCase(Locale.ROOT).equals(wanted)).findFirst();
    }

    /** @return the message key saying why not, or empty when the fund was started */
    public synchronized Optional<String> create(String name, Money target, String effect) {
        if (name == null || name.isBlank() || name.length() > 32) {
            return Optional.of("economy.fund.bad-name");
        }
        if (target == null || !target.isPositive()) {
            return Optional.of("economy.not-an-amount");
        }
        if (rule.effect(effect).isEmpty()) {
            return Optional.of("economy.fund.bad-effect");
        }
        if (find(name).filter(fund -> !fund.done()).isPresent()) {
            return Optional.of("economy.fund.exists");
        }
        Fund fund = new Fund(UUID.randomUUID(), name.strip(), target, Money.ZERO, effect == null ? "" : effect.strip(),
                clock.getAsLong(), 0);
        if (!store.saveFund(fund)) {
            return Optional.of("economy.unavailable");
        }
        funds.put(fund.id(), fund);
        return Optional.empty();
    }

    /** Takes a fund off the list. What was donated stays destroyed. */
    public synchronized boolean remove(String name) {
        Optional<Fund> found = find(name);
        if (found.isEmpty()) {
            return false;
        }
        funds.remove(found.get().id());
        store.removeFund(found.get().id());
        return true;
    }

    /** Gives toward a fund. The last donation is cut to what is missing; the money is destroyed. */
    public synchronized Donation donate(UUID player, String name, Money amount) {
        if (!enabled.getAsBoolean()) {
            return new Donation(Donation.Outcome.OFF, Money.ZERO);
        }
        Optional<Fund> found = find(name).filter(fund -> !fund.done());
        if (found.isEmpty()) {
            return new Donation(Donation.Outcome.NO_SUCH_FUND, Money.ZERO);
        }
        Fund fund = found.get();
        Money giving = amount.min(fund.missing());
        if (!giving.isPositive()) {
            return new Donation(Donation.Outcome.REFUSED, Money.ZERO);
        }
        EconomyResult paid = economy.move(player, giving.negate(), TransactionKind.FUND, fund.name(), SOURCE);
        if (!paid.succeeded()) {
            return new Donation(paid.outcome() == EconomyResult.Outcome.NOT_ENOUGH ? Donation.Outcome.NOT_ENOUGH
                    : Donation.Outcome.REFUSED, Money.ZERO);
        }
        Fund after = fund.donated(giving, clock.getAsLong());
        funds.put(after.id(), after);
        store.saveFund(after);
        if (after.done()) {
            onFilled.accept(after);
            return new Donation(Donation.Outcome.FILLED, giving);
        }
        return new Donation(Donation.Outcome.GIVEN, giving);
    }

    /** What a full fund's effect is, read again — staff wrote it, and it was checked when the fund started. */
    public Optional<FundRule.Effect> effectOf(Fund fund) {
        return rule.effect(fund.effect());
    }

    @Override
    public int faucetChange(String source) {
        if (!enabled.getAsBoolean()) {
            return 0;
        }
        long now = clock.getAsLong();
        int total = 0;
        for (Fund fund : funds.values()) {
            if (fund.done() && rule.effect(fund.effect()).orElse(null) instanceof FundRule.Effect.Boost boost
                    && rule.boosting(boost, fund.doneAt(), now)) {
                total += boost.percent();
            }
        }
        return total;
    }

    /** Boosts on now, for /fund and /eco health. */
    public List<Fund> boosting() {
        long now = clock.getAsLong();
        List<Fund> on = new ArrayList<>();
        for (Fund fund : funds.values()) {
            if (fund.done() && rule.effect(fund.effect()).orElse(null) instanceof FundRule.Effect.Boost boost
                    && rule.boosting(boost, fund.doneAt(), now)) {
                on.add(fund);
            }
        }
        return on;
    }
}
