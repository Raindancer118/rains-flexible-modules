package de.raindancer.modules.oneblock.service;

import de.raindancer.modules.oneblock.model.InviteKind;
import de.raindancer.modules.oneblock.model.Reply;
import de.raindancer.modules.oneblock.model.Requester;

import java.util.concurrent.CompletableFuture;

/** What a command on any server asks of the islands — straight to the service on the island server, over the link elsewhere. */
public interface IslandsGateway {

    CompletableFuture<Reply> invite(Requester from, String to, InviteKind kind);

    CompletableFuture<Reply> answer(Requester who, String from, boolean accept);

    /** To the player's own island. */
    CompletableFuture<Reply> home(Requester who);
}
