package de.raindancer.modules.oneblock.service;

import de.raindancer.core.platform.link.ServerLink;
import de.raindancer.modules.oneblock.model.InviteKind;
import de.raindancer.modules.oneblock.model.Reply;
import de.raindancer.modules.oneblock.model.Requester;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** The island server answering the others, on every link it has. Handlers run on link threads and wait for the service. */
public final class IslandEndpoint {

    private static final Reply ASLEEP = Reply.refused("oneblock.refuse.asleep");
    private static final long WAIT_SECONDS = 6;

    private final InviteService invites;
    private final Collection<ServerLink> links;

    public IslandEndpoint(InviteService invites, Collection<ServerLink> links) {
        this.invites = invites;
        this.links = links;
    }

    public void listen() {
        for (ServerLink link : links) {
            link.on(IslandProtocol.INVITE, data -> {
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
            });
            link.on(IslandProtocol.ANSWER, data -> {
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
            });
        }
    }

    public void stop() {
        for (ServerLink link : links) {
            link.off(IslandProtocol.INVITE);
            link.off(IslandProtocol.ANSWER);
        }
    }
}
