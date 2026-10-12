package de.raindancer.modules.arenagate.service;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.social.economy.Economy;
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

    @Override
    public void settings(GateSettings settings) {
        this.settings = settings;
    }

    /** @return empty when every stake is held, or the name of whoever could not pay */
    public synchronized Optional<String> hold(String fight, List<UUID> players, long amount) {
        Optional<Economy> money = economy.get();
        if (money.isEmpty()) {
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
            boolean paid = money.get().withdraw(player, Money.of(amount), "Arena stake", SOURCE_STAKE).succeeded();
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

    /** Pays the winners out of what was held. @return true once nothing of the fight is held any more */
    public synchronized boolean pay(String fight, Map<UUID, Long> payouts) {
        Optional<Economy> money = economy.get();
        List<Held> held = store.held(fight);
        if (held.isEmpty()) {
            return true;
        }
        if (money.isEmpty()) {
            return false;
        }
        long pot = held.stream().mapToLong(Held::amount).sum();
        long asked = payouts.values().stream().mapToLong(amount -> Math.max(0, amount)).sum();
        java.util.Set<UUID> staked = new java.util.HashSet<>();
        held.forEach(row -> staked.add(row.player()));
        if (!staked.containsAll(payouts.keySet())) {
            log.error("Fight {} asked to pay somebody who put no stake in; it is given back instead.", fight);
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
        paid.forEach((player, amount) ->
                money.get().deposit(player, Money.of(amount), "Arena winnings", SOURCE_PRIZE));
        long left = pot - asked;
        if (left > 0) {
            // A pot paid out in part: the rest goes back, shared like it came in.
            for (Held row : held) {
                long back = left * row.amount() / pot;
                if (back > 0) {
                    money.get().refund(row.player(), Money.of(back), "Arena stake back", SOURCE_STAKE);
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
        if (money.isEmpty()) {
            return false;
        }
        for (Held row : held) {
            if (store.settle(fight, row.player(), "REFUNDED", clock.getAsLong())) {
                money.get().refund(row.player(), Money.of(row.amount()), "Arena stake back", SOURCE_STAKE);
            }
        }
        return true;
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
