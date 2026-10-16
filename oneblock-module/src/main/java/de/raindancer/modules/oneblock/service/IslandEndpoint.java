package de.raindancer.modules.oneblock.service;

import de.raindancer.core.platform.link.ServerLink;
import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.core.platform.network.NetworkCalls;
import de.raindancer.modules.oneblock.model.InviteKind;
import de.raindancer.modules.oneblock.model.Reply;
import de.raindancer.modules.oneblock.model.Requester;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The island server answering the others, on every link it has and on the network's calls. On the link handlers
 * wait for the service, which works on the server thread; on a network they answer later, so the bus never waits.
 * On a network a server speaks only for players who are on it: another server cannot invite or accept in their name.
 */
public final class IslandEndpoint {

    private static final Reply ASLEEP = Reply.refused("oneblock.refuse.asleep");
    private static final long WAIT_SECONDS = 6;

    private final InviteService invites;
    private final Collection<ServerLink> links;
    private final Optional<NetworkCalls> calls;
    private final java.util.function.BiPredicate<UUID, String> isOn;
    private final java.util.function.BiConsumer<UUID, String> then;
    private final List<NetworkBus.Subscription> answering = new ArrayList<>();

    public IslandEndpoint(InviteService invites, Collection<ServerLink> links) {
        this(invites, links, Optional.empty(), (player, server) -> false, (player, arguments) -> { });
    }

    /**
     * @param isOn whether a player is on a server of the network now (presence)
     * @param then keeps an {@code /island} for a player's arrival
     */
    public IslandEndpoint(InviteService invites, Collection<ServerLink> links, Optional<NetworkCalls> calls,
                          java.util.function.BiPredicate<UUID, String> isOn,
                          java.util.function.BiConsumer<UUID, String> then) {
        this.invites = invites;
        this.links = links;
        this.calls = calls;
        this.isOn = isOn;
        this.then = then;
    }

    public void listen() {
        for (ServerLink link : links) {
            link.on(IslandProtocol.INVITE, this::invite);
            link.on(IslandProtocol.ANSWER, this::answer);
            link.on(IslandProtocol.THEN, this::keep);
        }
        calls.ifPresent(network -> {
            answering.add(network.answerFrom(IslandProtocol.INVITE, (from, data) -> forOwn(from, data.get("from"))
                    ? inviteLater(data) : asleep()));
            answering.add(network.answerFrom(IslandProtocol.ANSWER, (from, data) -> forOwn(from, data.get("player"))
                    ? answerLater(data) : asleep()));
            answering.add(network.answerFrom(IslandProtocol.THEN, (from, data) -> forOwn(from, data.get("player"))
                    ? java.util.concurrent.CompletableFuture.completedFuture(keep(data)) : asleep()));
        });
    }

    private boolean forOwn(String server, String player) {
        try {
            return isOn.test(UUID.fromString(player), server);
        } catch (RuntimeException garbled) {
            return false;
        }
    }

    private static java.util.concurrent.CompletableFuture<Map<String, String>> asleep() {
        return java.util.concurrent.CompletableFuture.completedFuture(ASLEEP.toWire());
    }

    private java.util.concurrent.CompletableFuture<Map<String, String>> inviteLater(Map<String, String> data) {
        try {
            Requester from = new Requester(UUID.fromString(data.get("from")), data.getOrDefault("fromName", "?"));
            return later(invites.invite(from, data.get("to"), InviteKind.fromWire(data.get("kind"))));
        } catch (RuntimeException garbled) {
            return asleep();
        }
    }

    private java.util.concurrent.CompletableFuture<Map<String, String>> answerLater(Map<String, String> data) {
        try {
            Requester who = new Requester(UUID.fromString(data.get("player")), data.getOrDefault("name", "?"));
            return later(invites.answer(who, data.get("from"), "true".equals(data.get("accept")), false));
        } catch (RuntimeException garbled) {
            return asleep();
        }
    }

    private static java.util.concurrent.CompletableFuture<Map<String, String>> later(
            java.util.concurrent.CompletableFuture<Reply> reply) {
        return reply.orTimeout(WAIT_SECONDS, TimeUnit.SECONDS).thenApply(Reply::toWire)
                .exceptionally(failed -> ASLEEP.toWire());
    }

    private Map<String, String> invite(Map<String, String> data) {
        try {
            Requester from = new Requester(UUID.fromString(data.get("from")), data.getOrDefault("fromName", "?"));
            return invites.invite(from, data.get("to"), InviteKind.fromWire(data.get("kind")))
                    .get(WAIT_SECONDS, TimeUnit.SECONDS).toWire();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ASLEEP.toWire();
        } catch (Exception failed) {
            return ASLEEP.toWire();
        }
    }

    private Map<String, String> answer(Map<String, String> data) {
        try {
            Requester who = new Requester(UUID.fromString(data.get("player")), data.getOrDefault("name", "?"));
            return invites.answer(who, data.get("from"), "true".equals(data.get("accept")), false)
                    .get(WAIT_SECONDS, TimeUnit.SECONDS).toWire();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ASLEEP.toWire();
        } catch (Exception failed) {
            return ASLEEP.toWire();
        }
    }

    private Map<String, String> keep(Map<String, String> data) {
        try {
            then.accept(UUID.fromString(data.get("player")), data.getOrDefault("arguments", ""));
            return Reply.silent().toWire();
        } catch (RuntimeException garbled) {
            return ASLEEP.toWire();
        }
    }

    public void stop() {
        for (ServerLink link : links) {
            link.off(IslandProtocol.INVITE);
            link.off(IslandProtocol.ANSWER);
            link.off(IslandProtocol.THEN);
        }
        answering.forEach(NetworkBus.Subscription::close);
        answering.clear();
    }
}
