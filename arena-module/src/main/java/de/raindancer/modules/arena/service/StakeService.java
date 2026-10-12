package de.raindancer.modules.arena.service;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.arena.api.ArenaProtocol;
import de.raindancer.modules.arena.store.SettlementBook;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Stakes, which live on the survival server — the money is there, and so is the escrow. The arena only asks: hold this
 * much from each of them before the fight, then pay it out or give it back after.
 *
 * <p>Paying out and giving back are written down here first and retried until the survival server says it did, so no
 * pot is lost to a restart. The survival side does each fight's settlement once, however often it is asked.
 */
public final class StakeService {

    private static final LogChannel log = Log.of("arena");

    private final ArenaLink link;
    private final SettlementBook settlements;

    public StakeService(ArenaLink link, SettlementBook settlements) {
        this.link = link;
        this.settlements = settlements;
    }

    /** @return empty when every stake is held, or the name of whoever could not pay (or why it failed) */
    public CompletableFuture<Optional<String>> hold(UUID fight, Collection<UUID> players, long stake) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("fight", fight.toString());
        data.put("players", players.stream().map(UUID::toString).collect(Collectors.joining(",")));
        data.put("amount", String.valueOf(stake));
        return link.ask(ArenaProtocol.STAKE_HOLD, data).handle((answer, failure) -> {
            if (failure != null) {
                return Optional.of("the survival server");
            }
            return "true".equals(answer.get("ok")) ? Optional.empty()
                    : Optional.of(answer.getOrDefault("who", "somebody"));
        });
    }

    public void pay(UUID fight, Map<UUID, Long> payouts) {
        Map<String, Long> byName = new LinkedHashMap<>();
        payouts.forEach((player, amount) -> byName.put(player.toString(), amount));
        settle(new SettlementBook.Settlement(fight.toString(), false, byName));
    }

    public void refund(UUID fight) {
        settle(new SettlementBook.Settlement(fight.toString(), true, Map.of()));
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
        Map<String, String> data = new LinkedHashMap<>();
        data.put("fight", settlement.fight());
        if (!settlement.refund()) {
            data.put("payouts", settlement.payouts().entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining(",")));
        }
        link.ask(settlement.refund() ? ArenaProtocol.STAKE_REFUND : ArenaProtocol.STAKE_PAY, data)
                .whenComplete((answer, failure) -> {
                    if (failure == null && "true".equals(answer.get("ok"))) {
                        settlements.done(settlement.fight());
                    } else if (failure != null) {
                        log.warn("The stakes of fight {} are not settled yet ({}); asked again later.",
                                settlement.fight(), failure.getMessage());
                    }
                });
    }
}
