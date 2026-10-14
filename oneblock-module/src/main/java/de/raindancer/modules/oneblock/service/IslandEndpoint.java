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
 * The island server answering the others, on every link it has and on the network's calls. Handlers wait for the
 * service, which works on the server thread — a tick or two. On a network that is the bus's thread waiting, so the
 * wait is short and nothing in it waits on another server.
 */
public final class IslandEndpoint {

    private static final Reply ASLEEP = Reply.refused("oneblock.refuse.asleep");
    private static final long WAIT_SECONDS = 6;

    private final InviteService invites;
    private final Collection<ServerLink> links;
    private final Optional<NetworkCalls> calls;
    private final List<NetworkBus.Subscription> answering = new ArrayList<>();

    public IslandEndpoint(InviteService invites, Collection<ServerLink> links) {
        this(invites, links, Optional.empty());
    }

    public IslandEndpoint(InviteService invites, Collection<ServerLink> links, Optional<NetworkCalls> calls) {
        this.invites = invites;
        this.links = links;
        this.calls = calls;
    }

    public void listen() {
        for (ServerLink link : links) {
            link.on(IslandProtocol.INVITE, this::invite);
            link.on(IslandProtocol.ANSWER, this::answer);
        }
        calls.ifPresent(network -> {
            answering.add(network.answer(IslandProtocol.INVITE, this::invite));
            answering.add(network.answer(IslandProtocol.ANSWER, this::answer));
        });
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

    public void stop() {
        for (ServerLink link : links) {
            link.off(IslandProtocol.INVITE);
            link.off(IslandProtocol.ANSWER);
        }
        answering.forEach(NetworkBus.Subscription::close);
        answering.clear();
    }
}
