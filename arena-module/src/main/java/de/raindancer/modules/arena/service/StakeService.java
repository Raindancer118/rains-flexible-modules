package de.raindancer.modules.arena.service;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.arena.api.ArenaProtocol;
import de.raindancer.modules.arena.store.SettlementBook;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Stakes, which live on the survival servers — the money is there, and so is the escrow. The arena only asks: hold this
 * much from each of them before the fight, then pay it out or give it back after.
 *
 * <p>With several survival servers each holds the stake of its own players; a winner from another one is paid out of
 * the pots as {@link #allocate} shares them, so no pot pays more than it holds and nothing is created or lost.
 *
 * <p>Paying out and giving back are written down here first and retried until the survival server says it did, so no
 * pot is lost to a restart. The survival side does each fight's settlement once, however often it is asked — the fight
 * id is the request id.
 */
public final class StakeService {

    private static final LogChannel log = Log.of("arena");

    /** Who holds what for a fight: its players by survival server, and the stake each put in. */
    private record Held(Map<String, List<UUID>> byHome, long stake) {
    }

    private final ArenaLink link;
    private final SettlementBook settlements;
    private final Function<UUID, String> names;
    private final Map<UUID, Held> held = new ConcurrentHashMap<>();

    public StakeService(ArenaLink link, SettlementBook settlements, Function<UUID, String> names) {
        this.link = link;
        this.settlements = settlements;
        this.names = names;
    }

    /** @return empty when every stake is held, or the name of whoever could not pay (or why it failed) */
    public CompletableFuture<Optional<String>> hold(UUID fight, Collection<UUID> players, long stake) {
        Map<String, List<UUID>> byHome = new LinkedHashMap<>();
        for (UUID player : players) {
            Optional<String> home = link.homeOf(player);
            if (home.isEmpty()) {
                return CompletableFuture.completedFuture(
                        Optional.of(names.apply(player) + " (no survival server to take it from)"));
            }
            byHome.computeIfAbsent(home.get(), ignored -> new ArrayList<>()).add(player);
        }
        // Remembered before asking: a server whose answer got lost may well have held, and must be asked to give back.
        held.put(fight, new Held(byHome, stake));
        List<CompletableFuture<Optional<String>>> asked = new ArrayList<>();
        byHome.forEach((home, mine) -> {
            Map<String, String> data = new LinkedHashMap<>();
            data.put("fight", fight.toString());
            data.put("players", mine.stream().map(UUID::toString).collect(Collectors.joining(",")));
            data.put("amount", String.valueOf(stake));
            asked.add(link.ask(home, ArenaProtocol.STAKE_HOLD, data, link.stakeWait()).handle((answer, failure) -> {
                if (failure != null) {
                    return Optional.of("the survival server");
                }
                return "true".equals(answer.get("ok")) ? Optional.<String>empty()
                        : Optional.of(answer.getOrDefault("who", "somebody"));
            }));
        });
        return CompletableFuture.allOf(asked.toArray(CompletableFuture[]::new)).thenApply(done -> asked.stream()
                .map(CompletableFuture::join).filter(Optional::isPresent).map(Optional::get).findFirst());
    }

    public void pay(UUID fight, Map<UUID, Long> payouts) {
        Held pots = held.remove(fight);
        if (pots == null) {
            // Nothing remembered (a link, or a restart): the one server there is settles it all.
            Map<String, Long> byName = new LinkedHashMap<>();
            payouts.forEach((player, amount) -> byName.put(player.toString(), amount));
            settle(new SettlementBook.Settlement(fight.toString(), "", false, byName, ""));
            return;
        }
        Map<String, Long> pot = new LinkedHashMap<>();
        Map<UUID, String> homeOf = new LinkedHashMap<>();
        pots.byHome().forEach((home, players) -> {
            pot.put(home, pots.stake() * players.size());
            players.forEach(player -> homeOf.put(player, home));
        });
        String everybody = homeOf.keySet().stream().map(UUID::toString).collect(Collectors.joining(","));
        allocate(pot, homeOf, payouts).forEach((home, share) -> {
            Map<String, Long> byName = new LinkedHashMap<>();
            share.forEach((player, amount) -> byName.put(player.toString(), amount));
            settle(new SettlementBook.Settlement(fight.toString(), home, false, byName, everybody));
        });
    }

    public void refund(UUID fight) {
        Held pots = held.remove(fight);
        List<String> homes = pots != null ? List.copyOf(pots.byHome().keySet())
                : link.onlyPeer().map(List::of).orElse(List.of());
        homes.forEach(home -> settle(new SettlementBook.Settlement(fight.toString(), home, true, Map.of(), "")));
    }

    /**
     * Who pays whom out of which pot: each winner is paid from the pot of their own server first, then from the others
     * in order. A pot never pays more than it holds, and a server whose pot is not touched is left out — it gives its
     * players their stake back by being asked to pay nothing.
     */
    static Map<String, Map<UUID, Long>> allocate(Map<String, Long> pots, Map<UUID, String> homeOf,
                                                Map<UUID, Long> payouts) {
        Map<String, Long> left = new LinkedHashMap<>(pots);
        Map<String, Map<UUID, Long>> shares = new LinkedHashMap<>();
        pots.keySet().forEach(home -> shares.put(home, new LinkedHashMap<>()));
        payouts.forEach((player, amount) -> {
            long owed = Math.max(0, amount);
            List<String> order = new ArrayList<>();
            String own = homeOf.get(player);
            if (own != null && left.containsKey(own)) {
                order.add(own);
            }
            left.keySet().stream().filter(home -> !home.equals(own)).forEach(order::add);
            for (String home : order) {
                long take = Math.min(owed, left.get(home));
                if (take > 0) {
                    shares.get(home).merge(player, take, Long::sum);
                    left.merge(home, -take, Long::sum);
                    owed -= take;
                }
            }
        });
        return shares;
    }

    /** Everything still owed, asked again — on a timer, and at once after each new fight. */
    public void retry() {
        settlements.open().forEach(this::send);
    }

    private void settle(SettlementBook.Settlement settlement) {
        settlements.add(settlement);
        send(settlement);
    }

    private void send(SettlementBook.Settlement settlement) {
        Optional<String> peer = settlement.home().isEmpty() ? link.onlyPeer() : Optional.of(settlement.home());
        if (peer.isEmpty()) {
            log.error("The stakes of fight {} name no survival server and none is the only one; they stay with the "
                    + "survival servers' escrow, which gives them back after two hours.", settlement.fight());
            settlements.done(settlement.fight(), settlement.home());
            return;
        }
        Map<String, String> data = new LinkedHashMap<>();
        data.put("fight", settlement.fight());
        if (!settlement.refund()) {
            data.put("payouts", settlement.payouts().entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining(",")));
            if (!settlement.participants().isEmpty()) {
                data.put("participants", settlement.participants());
            }
        }
        link.ask(peer.get(), settlement.refund() ? ArenaProtocol.STAKE_REFUND : ArenaProtocol.STAKE_PAY, data,
                link.stakeWait()).whenComplete((answer, failure) -> {
                    if (failure == null && "true".equals(answer.get("ok"))) {
                        settlements.done(settlement.fight(), settlement.home());
                    } else if (failure != null) {
                        log.warn("The stakes of fight {} are not settled yet ({}); asked again later.",
                                settlement.fight(), failure.getMessage());
                    }
                });
    }
}
