package de.raindancer.modules.guilds.store;

import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildEvent;
import de.raindancer.modules.guilds.model.Invitation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Where guilds live between restarts. Every write is all-or-nothing and answers whether it happened. */
public interface GuildStorage {

    /** Every guild, or empty when the storage cannot be read at all — which is not the same as "no guilds". */
    Optional<List<Guild>> loadAll();

    List<Invitation> loadInvitations();

    /**
     * Writes these guilds as they are now, takes away those named in {@code deleted}, replaces the open
     * invitations and appends the events — all in one transaction.
     */
    boolean write(Collection<Guild> changed, Set<String> deleted, Collection<Invitation> invitations,
                  List<GuildEvent> events);

    /** A page of one guild's log, newest first. {@code kinds} empty means every kind. */
    List<GuildEvent> events(String guild, Set<GuildEvent.Kind> kinds, int limit, int offset);

    boolean isUsable();
}
