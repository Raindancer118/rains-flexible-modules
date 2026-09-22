package de.raindancer.modules.chat.model;

import java.util.Set;
import java.util.UUID;

/**
 * One private chat as it stood when it was asked about — a snapshot, never the live thing, so
 * whoever holds it may walk it off the thread it was taken on.
 *
 * @param owner   who started it, and the only one who may add, remove or end
 * @param members everybody in it, the owner included
 */
public record PrivateChat(UUID owner, Set<UUID> members) {

    public PrivateChat {
        members = Set.copyOf(members);
    }

    public boolean isOwner(UUID who) {
        return owner.equals(who);
    }
}
