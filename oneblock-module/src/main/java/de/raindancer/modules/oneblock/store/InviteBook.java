package de.raindancer.modules.oneblock.store;

import de.raindancer.modules.oneblock.model.Invite;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The open invites, by whom they are for. In memory only, and rightly so: this is the island server's own book —
 * the other servers only forward over the network to it, so no invite is ever read by two servers — and every invite
 * here has a twin that is BentoBox's own pending team invite, which is memory too. A restored invite could not be
 * accepted through BentoBox, so one lost to a restart (it lives minutes) is simply asked again. At most one per
 * inviter and invitee — a second replaces the first.
 */
public final class InviteBook {

    /** What "accept" without a name means: the only one, none, or several to choose from. */
    public record Pick(Optional<Invite> invite, boolean several) {
    }

    private final Map<UUID, List<Invite>> byInvitee = new HashMap<>();

    /** @return the invite it replaced, from the same inviter to the same player */
    public synchronized Optional<Invite> add(Invite invite) {
        List<Invite> waiting = byInvitee.computeIfAbsent(invite.to().id(), key -> new ArrayList<>());
        Optional<Invite> replaced = waiting.stream().filter(old -> old.from().id().equals(invite.from().id())).findFirst();
        replaced.ifPresent(waiting::remove);
        waiting.add(invite);
        return replaced;
    }

    /** The invite {@code fromName} sent this player, or with no name the only one there is. */
    public synchronized Pick pick(UUID invitee, String fromName) {
        List<Invite> waiting = byInvitee.getOrDefault(invitee, List.of());
        if (fromName != null && !fromName.isBlank()) {
            return new Pick(waiting.stream().filter(each -> each.from().name().equalsIgnoreCase(fromName)).findFirst(),
                    false);
        }
        if (waiting.size() > 1) {
            return new Pick(Optional.empty(), true);
        }
        return new Pick(waiting.stream().findFirst(), false);
    }

    public synchronized List<Invite> waitingFor(UUID invitee) {
        return List.copyOf(byInvitee.getOrDefault(invitee, List.of()));
    }

    /** @return whether it was still there — false when somebody else answered or it ran out first */
    public synchronized boolean take(Invite invite) {
        List<Invite> waiting = byInvitee.get(invite.to().id());
        if (waiting == null || !waiting.remove(invite)) {
            return false;
        }
        if (waiting.isEmpty()) {
            byInvitee.remove(invite.to().id());
        }
        return true;
    }

    /** Takes out everything that ran out by {@code now}, and hands it back. */
    public synchronized List<Invite> expire(long now) {
        List<Invite> gone = new ArrayList<>();
        byInvitee.values().forEach(waiting -> waiting.removeIf(each -> {
            if (each.ranOut(now)) {
                gone.add(each);
                return true;
            }
            return false;
        }));
        byInvitee.values().removeIf(List::isEmpty);
        return gone;
    }
}
