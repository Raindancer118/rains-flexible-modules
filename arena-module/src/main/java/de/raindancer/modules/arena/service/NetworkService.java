package de.raindancer.modules.arena.service;

import de.raindancer.modules.arena.api.ArenaProtocol;
import de.raindancer.modules.arena.api.MatchSpec;
import de.raindancer.modules.arena.api.SpecCodec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What the survival server tells the arena over the link: who is on the way and what for, invites from over there,
 * and answers given over there to invites from here.
 */
public final class NetworkService {

    private final ArenaLink link;
    private final IntentBook intents;
    private final InviteService invites;
    private final AgreedFights agreed;

    public NetworkService(ArenaLink link, IntentBook intents, InviteService invites, AgreedFights agreed) {
        this.link = link;
        this.intents = intents;
        this.invites = invites;
        this.agreed = agreed;
    }

    public void listen() {
        link.on(ArenaProtocol.INTENT, this::intent);
        link.on(ArenaProtocol.INVITE, invites::fromAcross);
        link.on(ArenaProtocol.INVITE_ANSWER, invites::answeredAcross);
    }

    /**
     * Somebody is about to be sent over: {@code player}, {@code kind} (hub, queue, waves, agreed), the spec, and for an
     * agreed fight its {@code agreed} id and {@code sides} ({@code uuid,uuid|uuid}).
     */
    private Map<String, String> intent(Map<String, String> data) {
        try {
            UUID player = UUID.fromString(data.get("player"));
            IntentBook.Kind kind = IntentBook.Kind.valueOf(data.getOrDefault("kind", "HUB").toUpperCase(Locale.ROOT));
            MatchSpec spec = SpecCodec.decode(data);
            String agreedId = data.get("agreed");
            if (kind == IntentBook.Kind.AGREED && agreedId != null && data.containsKey("sides")) {
                agreed.agree(agreedId, spec, sides(data.get("sides")));
            }
            intents.put(player, kind, spec, agreedId);
            return Map.of("ok", "true");
        } catch (RuntimeException garbled) {
            return Map.of("ok", "false");
        }
    }

    static List<Set<UUID>> sides(String text) {
        List<Set<UUID>> sides = new ArrayList<>();
        for (String side : text.split("\\|")) {
            Set<UUID> members = new HashSet<>();
            for (String member : side.split(",")) {
                if (!member.isBlank()) {
                    members.add(UUID.fromString(member.trim()));
                }
            }
            if (!members.isEmpty()) {
                sides.add(members);
            }
        }
        return sides;
    }
}
