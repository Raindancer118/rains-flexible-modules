package de.raindancer.modules.oneblock.service;

import de.raindancer.modules.oneblock.model.InviteKind;
import de.raindancer.modules.oneblock.model.Reply;
import de.raindancer.modules.oneblock.model.Requester;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** On the island server: the service itself, the player already here. */
public final class LocalGateway implements IslandsGateway {

    private final InviteService invites;
    private final IslandHost host;
    private final Executor onServer;

    public LocalGateway(InviteService invites, IslandHost host, Executor onServer) {
        this.invites = invites;
        this.host = host;
        this.onServer = onServer;
    }

    @Override
    public CompletableFuture<Reply> invite(Requester from, String to, InviteKind kind) {
        return invites.invite(from, to, kind);
    }

    @Override
    public CompletableFuture<Reply> answer(Requester who, String from, boolean accept) {
        return invites.answer(who, from, accept, true);
    }

    @Override
    public CompletableFuture<Reply> home(Requester who) {
        return CompletableFuture.supplyAsync(() -> {
            if (!host.ready()) {
                return Reply.refused("oneblock.refuse.asleep");
            }
            host.home(who.id());
            return Reply.silent();
        }, onServer);
    }
}
