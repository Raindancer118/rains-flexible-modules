package de.raindancer.modules.arenagate.rules;

import java.util.Optional;

/**
 * What an invite that found nobody here or in the arena says: "nobody" only when the player is on no server of the
 * network at all — somebody on the hub or OneBlock is told apart, since they are online, just not where a duel starts.
 */
public final class InviteMissRule implements IGateRule {

    /** @param elsewhere the server they are on instead, when they are on one */
    public String keyFor(Optional<String> elsewhere) {
        return elsewhere.isPresent() ? "arenagate.invite.elsewhere" : "arenagate.invite.unknown";
    }

    @Override
    public String describe() {
        return "what a duel invite to somebody not here says";
    }
}
