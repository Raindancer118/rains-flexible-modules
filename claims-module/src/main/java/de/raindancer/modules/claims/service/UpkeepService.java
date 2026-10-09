package de.raindancer.modules.claims.service;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.social.economy.DebtKeeper;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.claims.ClaimSettings;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.UpkeepAccount;
import de.raindancer.modules.claims.model.UpkeepBill;
import de.raindancer.modules.claims.store.ClaimRegistry;
import de.raindancer.modules.claims.store.UpkeepStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Land costs money to hold: bills each owner for the chunks they hold, remembers what they owe, and tells
 * Core about it so the economy collects from their income.
 *
 * <p>The owner is the claim's primary owner; a claim with no owner is server land and is never billed. All
 * of an owner's claims count together, so the bill is progressive — see {@link UpkeepBill}.
 *
 * <h2>No double charging</h2>
 * Everything about one owner happens under that owner's lock, and the next due time is written to disk
 * <em>before</em> the money is asked for. A crash in between loses one bill; it can never charge twice.
 *
 * <h2>Nothing is ever deleted</h2>
 * Unpaid upkeep becomes a debt. It blocks making and growing claims, and — only when the server chose a number
 * of days — stops a claim protecting until it is paid. The claim itself stays.
 */
public final class UpkeepService implements IClaimService, DebtKeeper {

    private static final String SOURCE = "claims.upkeep";
    private static final String REASON = "Claim upkeep";
    private static final long DAY = 86_400_000L;
    private static final LogChannel log = Log.of("claims");

    public enum Outcome { NOT_DUE, PAID, ARREARS, UNAVAILABLE }

    /** What settling one owner came to. */
    public record Billing(UUID owner, Outcome outcome, Money amount) {
    }

    /** What paying arrears came to; {@code refusal} is null when something was paid. */
    public record Payment(Money paid, Money left, EconomyResult.Outcome refusal) {
    }

    /**
     * The next bill taken apart.
     *
     * @param chunks     chunks held, all claims together
     * @param claims     number of claims held
     * @param land       the per-chunk part (grows with total land)
     * @param perClaim   the per-claim part (each claim's fee grows with its own size)
     * @param payPercent what this owner pays of the sum: the operators' percent, or 100
     * @param total      what is billed, before the economy's price level
     */
    public record Parts(int chunks, int claims, Money land, Money perClaim, double payPercent, Money total) {
        public Money subtotal() {
            return land.plus(perClaim);
        }

        public boolean discounted() {
            return payPercent < 100.0D;
        }
    }

    private final ClaimRegistry claims;
    private final UpkeepStore store;
    private final LongSupplier clock;
    private volatile ClaimSettings settings;
    private final Map<UUID, UpkeepAccount> accounts = new ConcurrentHashMap<>();
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();
    private volatile Predicate<UUID> discount = who -> false;

    public UpkeepService(ClaimRegistry claims, UpkeepStore store, ClaimSettings settings, LongSupplier clock) {
        this.claims = claims;
        this.store = store;
        this.settings = settings;
        this.clock = clock;
        accounts.putAll(store.load());
    }

    @Override
    public void settings(ClaimSettings settings) {
        this.settings = settings;
    }

    @Override
    public String describe() {
        return "billing owners for the land they hold";
    }

    public boolean enabled() {
        return settings.upkeepEnabled();
    }

    // ------------------------------------------------------------ what is owed

    public int chunksHeld(UUID owner) {
        int chunks = 0;
        for (Claim claim : claims.all()) {
            if (owner.equals(claim.primaryOwner())) {
                chunks += claim.shape().coveredChunkKeys().size();
            }
        }
        return chunks;
    }

    /** Who pays the operators' percent instead of the full bill: operators, and holders of the discount node. */
    public void discount(Predicate<UUID> discount) {
        this.discount = discount == null ? who -> false : discount;
    }

    /** What the next bill would be, before the price level — what the owner can expect to see. */
    public Money billFor(UUID owner) {
        return partsFor(owner).total();
    }

    /** The next bill and what it is made of. */
    public Parts partsFor(UUID owner) {
        ClaimSettings now = settings;
        List<Integer> sizes = new ArrayList<>();
        int chunks = 0;
        for (Claim claim : claims.all()) {
            if (owner.equals(claim.primaryOwner())) {
                int covered = claim.shape().coveredChunkKeys().size();
                sizes.add(Math.max(1, covered));
                chunks += covered;
            }
        }
        Money land = UpkeepBill.of(now.upkeepPerChunkAmount(), now.upkeepGrowthPercent(), chunks);
        Money perClaim = UpkeepBill.claimFees(now.upkeepPerClaimAmount(), now.upkeepPerClaimAreaPercent(), sizes);
        double percent = discount.test(owner) ? Math.max(0.0D, Math.min(100.0D, now.upkeepOperatorsPayPercent()))
                : 100.0D;
        Money total = UpkeepBill.discounted(land.plus(perClaim), percent);
        return new Parts(chunks, sizes.size(), land, perClaim, percent, total);
    }

    /** The bill as it would actually be charged, after the economy's price level. */
    public Money quotedBillFor(UUID owner) {
        return Fees.quote(SOURCE, billFor(owner));
    }

    public UpkeepAccount account(UUID owner) {
        return accounts.getOrDefault(owner, new UpkeepAccount(0L, 0L, 0L));
    }

    public Optional<Long> nextDue(UUID owner) {
        UpkeepAccount account = accounts.get(owner);
        return account == null ? Optional.empty() : Optional.of(account.nextDue());
    }

    /** Unpaid upkeep. Zero while upkeep is switched off: an old debt is kept but not enforced or collected. */
    @Override
    public Money owed(UUID who) {
        return enabled() ? Money.of(account(who).owed()) : Money.ZERO;
    }

    public boolean inArrears(UUID who) {
        return owed(who).isPositive();
    }

    // ------------------------------------------------------------ billing

    /** Bills everyone who is due; returns only the ones something happened to. */
    public List<Billing> settleAll() {
        List<Billing> happened = new ArrayList<>();
        if (!enabled()) {
            return happened;
        }
        Set<UUID> owners = new LinkedHashSet<>();
        for (Claim claim : claims.all()) {
            if (claim.primaryOwner() != null) {
                owners.add(claim.primaryOwner());
            }
        }
        for (UUID owner : owners) {
            Billing billing = settle(owner);
            if (billing.outcome() != Outcome.NOT_DUE) {
                happened.add(billing);
            }
        }
        refreshProtection();
        return happened;
    }

    public Billing settle(UUID owner) {
        if (!enabled()) {
            return new Billing(owner, Outcome.NOT_DUE, Money.ZERO);
        }
        synchronized (lockOf(owner)) {
            long now = clock.getAsLong();
            long period = settings.upkeepPeriodMillis();
            UpkeepAccount account = accounts.get(owner);
            if (account == null) {
                // Seen for the first time: a full period of grace, so turning upkeep on does not send a bill
                // to everybody in the same minute.
                put(owner, new UpkeepAccount(now + period, 0L, 0L));
                return new Billing(owner, Outcome.NOT_DUE, Money.ZERO);
            }
            if (now < account.nextDue()) {
                return new Billing(owner, Outcome.NOT_DUE, Money.ZERO);
            }
            Money bill = billFor(owner);
            if (!bill.isPositive()) {
                put(owner, new UpkeepAccount(now + period, account.owed(), account.since()));
                return new Billing(owner, Outcome.NOT_DUE, Money.ZERO);
            }
            put(owner, new UpkeepAccount(now + period, account.owed(), account.since()));
            EconomyResult result = Fees.charge(owner, bill, REASON, SOURCE);
            if (result.succeeded()) {
                return new Billing(owner, Outcome.PAID, result.amount());
            }
            if (result.outcome() == EconomyResult.Outcome.UNAVAILABLE) {
                // No economy (yet) or it is down: ask again next time rather than turning it into a debt.
                put(owner, account);
                return new Billing(owner, Outcome.UNAVAILABLE, Money.ZERO);
            }
            Money missed = Fees.quote(SOURCE, bill);
            long since = account.since() > 0 ? account.since() : now;
            put(owner, new UpkeepAccount(now + period, Math.addExact(account.owed(), missed.minor()), since));
            return new Billing(owner, Outcome.ARREARS, missed);
        }
    }

    // ------------------------------------------------------------ paying

    /** The owner pays their arrears themselves, as much as they can afford of it. */
    public Payment pay(UUID owner) {
        synchronized (lockOf(owner)) {
            UpkeepAccount account = accounts.get(owner);
            Money owing = account == null ? Money.ZERO : Money.of(account.owed());
            if (!owing.isPositive()) {
                return new Payment(Money.ZERO, Money.ZERO, null);
            }
            Optional<Economy> economy = Economies.current();
            if (economy.isEmpty()) {
                return new Payment(Money.ZERO, owing, EconomyResult.Outcome.UNAVAILABLE);
            }
            Money amount = owing.min(economy.get().balance(owner));
            if (!amount.isPositive()) {
                return new Payment(Money.ZERO, owing, EconomyResult.Outcome.NOT_ENOUGH);
            }
            EconomyResult result = economy.get().withdraw(owner, amount, REASON, SOURCE);
            if (!result.succeeded()) {
                return new Payment(Money.ZERO, owing, result.outcome());
            }
            Money left = reduce(owner, amount);
            refreshProtection(owner);
            return new Payment(amount, left, null);
        }
    }

    /** Pays what is owed and says how it went; the one wording for the command and the screen. */
    public void payAndTell(org.bukkit.entity.Player player, de.raindancer.core.ui.messages.Messages messages) {
        Money owing = owed(player.getUniqueId());
        if (!owing.isPositive()) {
            messages.send(player, "upkeep.nothing-owed");
            return;
        }
        Payment payment = pay(player.getUniqueId());
        if (payment.refusal() == EconomyResult.Outcome.UNAVAILABLE) {
            messages.send(player, "upkeep.no-economy");
        } else if (!payment.paid().isPositive()) {
            messages.send(player, "upkeep.cannot-pay", "owed", Fees.format(owing));
        } else if (payment.left().isPositive()) {
            messages.send(player, "upkeep.paid-part",
                    "paid", Fees.format(payment.paid()), "left", Fees.format(payment.left()));
        } else {
            messages.send(player, "upkeep.paid-all", "paid", Fees.format(payment.paid()));
        }
    }

    /** Core took {@code amount} from {@code who}'s income toward what they owe; this is how much it settled. */
    @Override
    public Money paid(UUID who, Money amount) {
        if (!enabled() || amount == null || !amount.isPositive()) {
            return Money.ZERO;
        }
        synchronized (lockOf(who)) {
            Money owing = Money.of(account(who).owed());
            Money settled = owing.min(amount);
            if (settled.isPositive()) {
                reduce(who, settled);
                refreshProtection(who);
            }
            return settled;
        }
    }

    private Money reduce(UUID owner, Money amount) {
        UpkeepAccount account = account(owner);
        long left = Math.max(0L, account.owed() - amount.minor());
        put(owner, new UpkeepAccount(account.nextDue(), left, left == 0 ? 0L : account.since()));
        return Money.of(left);
    }

    // ------------------------------------------------------------ protection

    /** Whether this owner's claims have gone unprotected for unpaid upkeep. */
    public boolean lapsed(UUID owner) {
        int days = settings.upkeepLiftProtectionAfterDays();
        UpkeepAccount account = account(owner);
        return enabled() && days > 0 && account.owed() > 0 && account.since() > 0
                && clock.getAsLong() - account.since() >= days * DAY;
    }

    public void refreshProtection() {
        Map<UUID, Boolean> verdicts = new HashMap<>();
        for (Claim claim : claims.all()) {
            UUID owner = claim.primaryOwner();
            claim.lapsed(owner != null && verdicts.computeIfAbsent(owner, this::lapsed));
        }
    }

    private void refreshProtection(UUID owner) {
        boolean lapsed = lapsed(owner);
        for (Claim claim : claims.all()) {
            if (owner.equals(claim.primaryOwner())) {
                claim.lapsed(lapsed);
            }
        }
    }

    // ------------------------------------------------------------ state

    private Object lockOf(UUID owner) {
        return locks.computeIfAbsent(owner, key -> new Object());
    }

    private void put(UUID owner, UpkeepAccount account) {
        accounts.put(owner, account);
        if (!store.save(Map.copyOf(accounts))) {
            log.error("Could not write upkeep.yml; the schedule and arrears are right in memory but a restart "
                    + "would forget them.");
        }
    }
}
