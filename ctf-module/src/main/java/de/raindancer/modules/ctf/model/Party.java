package de.raindancer.modules.ctf.model;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Friends who want to be on the same side. The leader is a member. */
public record Party(UUID leader, Set<UUID> members) {

    public Party {
        LinkedHashSet<UUID> all = new LinkedHashSet<>();
        all.add(leader);
        all.addAll(members);
        members = java.util.Collections.unmodifiableSet(all);
    }

    public boolean has(UUID player) {
        return members.contains(player);
    }

    public Party with(UUID player) {
        Set<UUID> more = new LinkedHashSet<>(members);
        more.add(player);
        return new Party(leader, more);
    }

    /** Without them; if the leader leaves, the next one in leads. Empty when nobody is left. */
    public java.util.Optional<Party> without(UUID player) {
        Set<UUID> fewer = new LinkedHashSet<>(members);
        fewer.remove(player);
        if (fewer.isEmpty()) {
            return java.util.Optional.empty();
        }
        UUID next = player.equals(leader) ? fewer.iterator().next() : leader;
        return java.util.Optional.of(new Party(next, fewer));
    }

    public int size() {
        return members.size();
    }
}
