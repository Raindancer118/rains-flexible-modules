package de.raindancer.modules.arenagate.service;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.arenagate.GateSettings;
import de.raindancer.modules.arenagate.model.Held;
import de.raindancer.modules.arenagate.store.EscrowStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Stakes for arena fights, held here — where the money is. The arena asks to hold, then to pay out or give back.
 *
 * <ul>
 *   <li><b>Hold</b> takes the stake from each player and writes it down; if one cannot pay, those already taken are given
 *       back at once and the fight is off.</li>
 *   <li><b>Pay</b> never pays out more than was held for the fight (the arena is trusted, but not with creating money),
 *       less the house's cut; the rest of what was held goes back to whoever paid it.</li>
 *   <li><b>Refund</b> gives every held stake back.</li>
 *   <li>A row is settled once: asked twice, the second answer is "done" and moves no money.</li>
 *   <li>Held longer than {@link #STALE_MILLIS} — the arena never said how it ended — is given back by itself.</li>
 *   <li>With several survival servers a fight's pot may be held by more than one; a winner who belongs to another
 *       server is paid out of this one's pot, so a payee only has to be a participant, not a staker here.</li>
 *   <li>Money moves at most once: a row is settled before its money moves and never settled again, so a call that
 *       timed out is not repeated into a double payment. A movement that failed is logged for the owner to settle.
 *       Nothing is settled while the economy cannot be reached ({@link #bank}), so a bank that is down costs
 *       nothing but time.</li>
 * </ul>
 */
public final class EscrowService implements IGateService {

    private static final LogChannel log = Log.of("arenagate");
    public static final long STALE_MILLIS = 2 * 60 * 60 * 1000L;
    private static final String SOURCE_STAKE = "arena.stake";
    private static final String SOURCE_PRIZE = "arena.prize";

    private final Supplier<Optional<Economy>> economy;
    private final EscrowStore store;
    private final LongSupplier clock;
    private final Function<UUID, String> names;
    private volatile java.util.function.BiPredicate<UUID, Long> consented = (player, amount) -> true;
    private volatile BooleanSupplier bankUp = () -> true;
    private volatile GateSettings settings;

    public EscrowService(Supplier<Optional<Economy>> economy, EscrowStore store, LongSupplier clock,
                         Function<UUID, String> names, GateSettings settings) {
        this.economy = economy;
        this.store = store;
        this.clock = clock;
        this.names = names;
        this.settings = settings;
    }

    /** Who agreed to what — asked, and used up, for each player before their stake is taken. */
    public void consents(java.util.function.BiPredicate<UUID, Long> agreed) {
        this.consented = agreed;
    }

    /** Whether whoever keeps the money can be reached — on a network, whether the bank's role has a holder. */
    public void bank(BooleanSupplier reachable) {
        this.bankUp = reachable;
    }

    @Override
    public void settings(GateSettings settings) {
        this.settings = settings;
    }

    /** @return empty when every stake is held, or the name of whoever could not pay */
    public synchronized Optional<String> hold(String fight, List<UUID> players, long amount) {
        Optional<Economy> money = economy.get();
        if (money.isEmpty() || !bankUp.getAsBoolean()) {
            return Optional.of("the economy");
        }
        if (amount <= 0) {
            return Optional.empty();
        }
        if (!store.held(fight).isEmpty()) {
            // Asked again for a fight already held: it is held.
            return Optional.empty();
        }
        for (UUID player : players) {
            if (!consented.test(player, amount)) {
                return Optional.of(names.apply(player) + " (did not agree to that stake)");
            }
        }
        List<UUID> taken = new ArrayList<>();
        for (UUID player : players) {
            EconomyResult withdrawn = money.get().withdraw(player, Money.of(amount), "Arena stake", SOURCE_STAKE);
            boolean paid = withdrawn.succeeded();
            if (!paid && withdrawn.outcome() == EconomyResult.Outcome.UNAVAILABLE) {
                log.error("The stake of {} for fight {} was asked of the bank and no answer came: it may or may not "
                        + "have been taken. Check their balance.", player, fight);
            }
            if (!paid || !store.hold(fight, player, amount, clock.getAsLong())) {
                if (paid) {
                    money.get().refund(player, Money.of(amount), "Arena stake back", SOURCE_STAKE);
                }
                for (UUID back : taken) {
                    if (store.settle(fight, back, "REFUNDED", clock.getAsLong())) {
                        money.get().refund(back, Money.of(amount), "Arena stake back", SOURCE_STAKE);
                    }
                }
                return Optional.of(names.apply(player));
            }
            taken.add(player);
        }
        return Optional.empty();
    }

    /** Pays the winners out of what was held; only those who staked here may be paid. */
    public synchronized boolean pay(String fight, Map<UUID, Long> payouts) {
        return pay(fight, payouts, null);
    }

    /**
     * Pays the winners out of what was held.
     *
     * @param participants everybody of the fight, wherever they staked; null for the ones who staked here
     * @return true once nothing of the fight is held any more
     */
    public synchronized boolean pay(String fight, Map<UUID, Long> payouts, Set<UUID> participants) {
        Optional<Economy> money = economy.get();
        List<Held> held = store.held(fight);
        if (held.isEmpty()) {
            return true;
        }
        if (money.isEmpty() || !bankUp.getAsBoolean()) {
            return false;
        }
        long pot = held.stream().mapToLong(Held::amount).sum();
        long asked = payouts.values().stream().mapToLong(amount -> Math.max(0, amount)).sum();
        java.util.Set<UUID> staked = new java.util.HashSet<>();
        held.forEach(row -> staked.add(row.player()));
        if (!(participants == null ? staked : participants).containsAll(payouts.keySet())) {
            log.error("Fight {} asked to pay somebody who is not in it; it is given back instead.", fight);
            return refund(fight);
        }
        if (asked > pot) {
            log.error("Fight {} asked to pay out {} from a pot of {}; it is given back instead.", fight, asked, pot);
            return refund(fight);
        }
        int cut = Math.max(0, Math.min(100, settings.houseCutPercent()));
        for (Held row : held) {
            store.settle(fight, row.player(), "PAID", clock.getAsLong());
        }
        Map<UUID, Long> paid = new LinkedHashMap<>();
        payouts.forEach((player, amount) -> {
            long after = amount - ((amount / 100) * cut + (amount % 100) * cut / 100);
            if (after > 0) {
                paid.put(player, after);
            }
        });
        paid.forEach((player, amount) -> {
            EconomyResult result = money.get().deposit(player, Money.of(amount), "Arena winnings", SOURCE_PRIZE);
            if (!result.succeeded()) {
                log.error("Fight {}: {} is owed {} in winnings and the economy did not confirm it ({}). Not repeated "
                        + "by itself, as it may have gone through; settle it by hand if the balance is short.", fight,
                        player, amount, result.outcome());
            }
        });
        long left = pot - asked;
        if (left > 0) {
            // A pot paid out in part: the rest goes back, shared like it came in.
            for (Held row : held) {
                long back = left * row.amount() / pot;
                if (back > 0) {
                    giveBack(money.get(), fight, row.player(), back);
                }
            }
        }
        return true;
    }

    public synchronized boolean refund(String fight) {
        Optional<Economy> money = economy.get();
        List<Held> held = store.held(fight);
        if (held.isEmpty()) {
            return true;
        }
        if (money.isEmpty() || !bankUp.getAsBoolean()) {
            return false;
        }
        for (Held row : held) {
            if (store.settle(fight, row.player(), "REFUNDED", clock.getAsLong())) {
                giveBack(money.get(), fight, row.player(), row.amount());
            }
        }
        return true;
    }

    private void giveBack(Economy money, String fight, UUID player, long amount) {
        EconomyResult result = money.refund(player, Money.of(amount), "Arena stake back", SOURCE_STAKE);
        if (!result.succeeded()) {
            log.error("Fight {}: {} is owed {} back and the economy did not confirm it ({}). Not repeated by itself, "
                    + "as it may have gone through; settle it by hand if the balance is short.", fight, player, amount,
                    result.outcome());
        }
    }

    /** Stakes held too long without word from the arena go back. */
    public void sweep() {
        long cutoff = clock.getAsLong() - STALE_MILLIS;
        store.heldBefore(cutoff).stream().map(Held::fight).distinct().forEach(fight -> {
            log.warn("The stakes of fight {} were held for over two hours without a result; they go back.", fight);
            refund(fight);
        });
    }
}
