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

    /**
     * Something only the island server can do — {@code /island nether}, the upgrades: there, it is done at once by
     * whoever asked; elsewhere the island server keeps it for their arrival and they are sent over.
     *
     * @return empty when the caller should do it here and now
     */
    default CompletableFuture<java.util.Optional<Reply>> then(Requester who, String arguments) {
        return CompletableFuture.completedFuture(java.util.Optional.empty());
    }
}
