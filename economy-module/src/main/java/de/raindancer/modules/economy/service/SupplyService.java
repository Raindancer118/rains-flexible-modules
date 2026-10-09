package de.raindancer.modules.economy.service;

import de.raindancer.core.social.economy.EconomyLever;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.social.economy.MoneySupply;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.SupplySettings;
import de.raindancer.modules.economy.model.Flows;
import de.raindancer.modules.economy.rules.PriceIndexRule;
import de.raindancer.modules.economy.rules.StabilizerRule;
import de.raindancer.modules.economy.rules.StabilizerRule.Taps;
import de.raindancer.modules.economy.rules.SupplyRule;
import de.raindancer.modules.economy.store.AccountBook;
import de.raindancer.modules.economy.store.SupplyBook;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

/**
 * How much money there is, and the taps on it: puts the owner's hard cap on the ledger, prices the basket of
 * goods once a day, turns the stabiliser, and answers Core's {@link EconomyLever} so every payout on the
 * server — this module's and every other plugin's — shrinks when the treasury runs low, everybody is rich, or
 * prices run away.
 *
 * <p>Talks to the database in {@link #daily()}, {@link #supply} and {@link #health}: call those off the server
 * thread. The lever answers from a snapshot and is cheap.
 */
public final class SupplyService implements IEconomyService, EconomyLever {

    private static final long DAY = 86_400_000L;

    private final AccountBook book;
    private final SupplyBook store;
    private final ToLongFunction<String> unitPrice;
    private final LongSupplier clock;
    private final ZoneId zone;
    private final Consumer<String> alert;
    private final SupplyRule supplyRule = new SupplyRule();
    private final StabilizerRule stabilizerRule = new StabilizerRule();
    private final PriceIndexRule indexRule = new PriceIndexRule();

    private volatile EconomySettings settings;
    private volatile SupplySettings supply = SupplySettings.DEFAULTS;
    private volatile MoneySupply snapshot = MoneySupply.open(Money.ZERO, 0);
    private volatile int faucetFromSupply;
    private volatile Taps taps = Taps.NEUTRAL;
    private volatile long tapsDay = Long.MIN_VALUE;
    private volatile long recordedDay = Long.MIN_VALUE;
    private volatile double measuredLevel = 1.0;
    private volatile boolean loaded;

    /**
     * @param unitPrice what one of an item (upper-case material name) costs in the shop now, in minor units;
     *                  0 for nothing
     * @param alert     a sentence for staff and the log
     */
    public SupplyService(AccountBook book, SupplyBook store, ToLongFunction<String> unitPrice, LongSupplier clock,
                         ZoneId zone, Consumer<String> alert, EconomySettings settings) {
        this.book = book;
        this.store = store;
        this.unitPrice = unitPrice;
        this.clock = clock;
        this.zone = zone;
        this.alert = alert;
        settings(settings);
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
    }

    /** Takes the anti-inflation settings: caps or uncaps the ledger at once. Reads the database the first time. */
    public void supply(SupplySettings updated) {
        SupplySettings next = updated == null ? SupplySettings.DEFAULTS : updated;
        this.supply = next;
        if (!loaded) {
            SupplyBook.Stabilized kept = store.stabilizer();
            taps = kept.taps();
            tapsDay = kept.day();
            Map<Long, Long> days = store.basketDays(today() - 30);
            if (days.containsKey(today())) {
                recordedDay = today();
            }
            measuredLevel = level(days);
            loaded = true;
        }
        if (next.capped()) {
            Money cap = SupplySettings.money(next.cap(), settings.currency());
            if (cap.isPositive()) {
                book.limitSupply(true, cap);
            } else {
                book.limitSupply(false, null);
                alert.accept("The money cap '" + next.cap() + "' cannot be read as an amount, so money is not "
                        + "capped. Set money-supply.cap to an amount above what is out there.");
            }
        } else {
            book.limitSupply(false, null);
        }
        refresh(snapshot.activePlayers());
    }

    public SupplySettings current() {
        return supply;
    }

    /** The settings a service wired to {@code service} reads; the shipped ones before it is wired. */
    public static SupplySettings settingsOf(SupplyService service) {
        return service == null ? SupplySettings.DEFAULTS : service.current();
    }

    /** Reads how much money there is now and works out the taps from it. Cheap enough for every half minute. */
    public void refresh(int activePlayers) {
        MoneySupply now = book.supply(activePlayers);
        snapshot = now;
        SupplySettings live = supply;
        faucetFromSupply = supplyRule.faucetChange(now, live.lowTreasuryBrake() ? live.scaleBelowPercent() : 0,
                live.perPlayerBrake() ? SupplySettings.money(live.targetPerPlayer(), settings.currency()) : Money.ZERO);
    }

    public MoneySupply snapshot() {
        return snapshot;
    }

    public Taps taps() {
        return supply.stabilizer() ? taps : Taps.NEUTRAL;
    }

    @Override
    public int faucetChange(String source) {
        return faucetFromSupply + taps().faucet();
    }

    @Override
    public int sinkChange(String source) {
        return taps().sink();
    }

    /** The factor fees follow: the measured price level when the owner said fees follow it, 1.0 otherwise. */
    public double priceLevel() {
        return supply.feesFollow() ? measuredLevel : 1.0;
    }

    /** Today's basket over the first one priced. */
    public double measuredLevel() {
        return measuredLevel;
    }

    /**
     * Once a day: prices the basket, writes the day's row, and turns the stabiliser. Asked as often as anybody
     * likes; does its work once per day.
     */
    public void daily() {
        long today = today();
        if (recordedDay == today) {
            return;
        }
        long basket = basketPrice();
        MoneySupply now = book.supply(snapshot.activePlayers());
        if (!store.recordDay(today, now.circulating(), now.cap(), basket)) {
            return;
        }
        recordedDay = today;
        Map<Long, Long> days = store.basketDays(today - 30);
        measuredLevel = level(days);
        SupplySettings live = supply;
        if (!live.stabilizer() || tapsDay == today) {
            return;
        }
        OptionalDouble weekly = stabilizerRule.weeklyPercent(days, today);
        if (weekly.isEmpty()) {
            return;
        }
        Taps next = stabilizerRule.next(taps, weekly.getAsDouble(), live.targetWeeklyPercent(),
                live.tolerancePercent(), live.stepPercent(), live.mostPercent());
        tapsDay = today;
        if (!next.equals(taps)) {
            alert.accept(String.format(java.util.Locale.ROOT, "The stabiliser saw prices move %.1f%% this week "
                    + "(target %.1f%%): payouts now %+d%%, fees %+d%%.", weekly.getAsDouble(),
                    live.targetWeeklyPercent(), next.faucet(), next.sink()));
        }
        taps = next;
        store.saveStabilizer(next, today);
    }

    /** What the basket costs at the shop now, in minor units. */
    public long basketPrice() {
        long total = 0;
        for (Map.Entry<String, Integer> item : indexRule.basket(supply.basket())) {
            try {
                total = Math.addExact(total, Math.multiplyExact(Math.max(0, unitPrice.applyAsLong(item.getKey())),
                        item.getValue()));
            } catch (ArithmeticException overflow) {
                return Long.MAX_VALUE;
            }
        }
        return total;
    }

    /** How much more money is out there than the cap allows, if any — asked by the audit. */
    public Optional<Money> audit() {
        Money over = snapshot.overCap();
        return over.isPositive() ? Optional.of(over) : Optional.empty();
    }

    /** Everything /eco health and /treasury show. Reads a week of the ledger: off the server thread. */
    public Health health() {
        long now = clock.getAsLong();
        Map<Long, Long> days = store.basketDays(today() - 30);
        return new Health(book.supply(snapshot.activePlayers()), book.flows(now - 7 * DAY), measuredLevel,
                stabilizerRule.weeklyPercent(days, today()), taps(), faucetChange(""), sinkChange(""),
                store.circulatingDays(today() - 7));
    }

    /**
     * @param weeklyInflation how much the basket moved in the last week, in percent, if there is a week to compare
     * @param faucetPercent   what every payout is changed by now, all brakes together
     * @param circulatingDays money out there by epoch day, for "how fast is it growing"
     */
    public record Health(MoneySupply supply, Flows week, double level, OptionalDouble weeklyInflation, Taps taps,
                         int faucetPercent, int sinkPercent, Map<Long, Long> circulatingDays) {

        /** How much more money there is than a week ago, in percent; empty without a row from back then. */
        public OptionalDouble moneyGrowthPercent() {
            if (circulatingDays.isEmpty()) {
                return OptionalDouble.empty();
            }
            long first = circulatingDays.entrySet().iterator().next().getValue();
            return first > 0 ? OptionalDouble.of((supply.circulating().minor() - first) * 100.0 / first)
                    : OptionalDouble.empty();
        }
    }

    private double level(Map<Long, Long> recent) {
        long today = today();
        Long now = recent.get(today);
        if (now == null || now <= 0) {
            return measuredLevel;
        }
        return store.firstBasket().filter(first -> first.getValue() > 0)
                .map(first -> (double) now / first.getValue()).orElse(1.0);
    }

    private long today() {
        return Instant.ofEpochMilli(clock.getAsLong()).atZone(zone).toLocalDate().toEpochDay();
    }
}
