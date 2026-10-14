package de.raindancer.modules.sends.store;

import de.raindancer.modules.sends.model.Send;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Where sends are kept: one database every server of a network shares, so a send made on one server can be
 * answered on another.
 *
 * <p>Every change of state is a conditional update, and the database — not any server's memory — is what
 * decides who wins when two servers try to end the same send.
 */
public interface SendStorage {

    /** What a conditional change came to. */
    enum Written {
        /** This server's change is the one that happened. */
        DONE,
        /** Somebody else got there first: the send is no longer in the state this server saw. */
        LOST,
        /** The database could not be written; nothing changed. */
        FAILED
    }

    /** Every send somebody still has a claim on; empty when the database could not be read. */
    Optional<List<Send>> loadOpen();

    /**
     * One send as it is now: an empty outer answer when the database could not be read, an empty inner one when
     * there is no open send of that id.
     */
    Optional<Optional<Send>> load(UUID id);

    /** A new send, items included; false when it could not be written. Writing the same send again changes nothing. */
    boolean save(Send send);

    /**
     * Moves a send from the state {@code before} had to the one {@code after} has — only if it is still in the
     * first. Exactly one of any number of servers trying the same move gets {@link Written#DONE}.
     */
    Written change(Send before, Send after);
}
