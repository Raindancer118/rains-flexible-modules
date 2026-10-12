package de.raindancer.modules.arenagate.service;

import de.raindancer.core.platform.link.ServerLink;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.arena.api.ArenaProtocol;
import de.raindancer.modules.arena.api.MatchSpec;
import de.raindancer.modules.arena.api.Mode;
import de.raindancer.modules.arena.api.SpecCodec;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The survival side of the arena link: holding and settling stakes, copying gear, finding players by name, invites
 * from the arena, sending somebody over who is waited for, and knowing who is coming back.
 *
 * <p>Handlers run on the link's threads; anything that touches a player hops to that player's own.
 */
public final class GateNetwork {

    private final Plugin plugin;
    private final Server server;
    private final Messages messages;
    private final ServerLink link;
    private final EscrowService escrow;
    private final GearService gear;
    private final GateInvites invites;
    private final GateService gate;
    private final GateConsents consents;
    private final Set<UUID> returning = ConcurrentHashMap.newKeySet();

    public GateNetwork(Plugin plugin, Server server, Messages messages, ServerLink link, EscrowService escrow,
                       GearService gear, GateInvites invites, GateService gate, GateConsents consents) {
        this.consents = consents;
        this.plugin = plugin;
        this.server = server;
        this.messages = messages;
        this.link = link;
        this.escrow = escrow;
        this.gear = gear;
        this.invites = invites;
        this.gate = gate;
    }

    public void listen() {
        link.on(ArenaProtocol.STAKE_HOLD, this::hold);
        link.on(ArenaProtocol.STAKE_PAY, this::pay);
        link.on(ArenaProtocol.STAKE_REFUND, data -> Map.of("ok", String.valueOf(escrow.refund(data.get("fight")))));
        link.on(ArenaProtocol.GEAR, data -> {
            try {
                return consents.takeGear(UUID.fromString(data.get("player"))) ? gear.copy(data) : Map.of("ok", "false");
            } catch (RuntimeException garbled) {
                return Map.of("ok", "false");
            }
        });
        link.on(ArenaProtocol.CONSENT, data -> {
            try {
                consents.agree(UUID.fromString(data.get("player")), Long.parseLong(data.getOrDefault("stake", "0")),
                        "true".equals(data.get("gear")));
            } catch (RuntimeException garbled) {
                // An agreement that cannot be read is no agreement; whatever needs it is refused.
            }
            return null;
        });
        link.on(ArenaProtocol.WHERE, this::where);
        link.on(ArenaProtocol.INVITE, this::inviteFromArena);
        link.on(ArenaProtocol.SUMMON, this::summon);
        link.on(ArenaProtocol.RETURNING, data -> {
            try {
                returning.add(UUID.fromString(data.get("player")));
            } catch (RuntimeException garbled) {
                // Somebody's welcome back reads as a plain join; nothing else depends on it.
            }
            return null;
        });
    }

    public void stop() {
        for (String topic : List.of(ArenaProtocol.STAKE_HOLD, ArenaProtocol.STAKE_PAY, ArenaProtocol.STAKE_REFUND,
                ArenaProtocol.GEAR, ArenaProtocol.WHERE, ArenaProtocol.INVITE, ArenaProtocol.SUMMON,
                ArenaProtocol.RETURNING, ArenaProtocol.CONSENT)) {
            link.off(topic);
        }
    }

    /** Whether this join is somebody coming back from the arena — said once, then forgotten. */
    public boolean isReturning(UUID player) {
        return returning.remove(player);
    }

    private Map<String, String> hold(Map<String, String> data) {
        try {
            List<UUID> players = Arrays.stream(data.get("players").split(",")).map(UUID::fromString).toList();
            return escrow.hold(data.get("fight"), players, Long.parseLong(data.get("amount")))
                    .map(who -> Map.of("ok", "false", "who", who)).orElse(Map.of("ok", "true"));
        } catch (RuntimeException garbled) {
            return Map.of("ok", "false", "who", "nobody (a garbled request)");
        }
    }

    private Map<String, String> pay(Map<String, String> data) {
        try {
            Map<UUID, Long> payouts = new LinkedHashMap<>();
            String text = data.getOrDefault("payouts", "");
            for (String pair : text.split(",")) {
                if (pair.isBlank()) {
                    continue;
                }
                String[] parts = pair.split("=");
                payouts.merge(UUID.fromString(parts[0]), Long.parseLong(parts[1]), Long::sum);
            }
            return Map.of("ok", String.valueOf(escrow.pay(data.get("fight"), payouts)));
        } catch (RuntimeException garbled) {
            return Map.of("ok", "false");
        }
    }

    private Map<String, String> where(Map<String, String> data) {
        Player online = server.getPlayerExact(data.getOrDefault("name", ""));
        return online == null ? Map.of("online", "false")
                : Map.of("online", "true", "player", online.getUniqueId().toString(), "name", online.getName());
    }

    /** Somebody in the arena asks somebody here. */
    private Map<String, String> inviteFromArena(Map<String, String> data) {
        try {
            Player target = server.getPlayer(UUID.fromString(data.get("to")));
            if (target == null) {
                return Map.of("ok", "false", "reason", "offline");
            }
            MatchSpec spec = SpecCodec.decode(data);
            GateInvites.Pending pending = invites.add(data.get("id"), UUID.fromString(data.get("from")),
                    data.get("fromName"), target.getUniqueId(), spec, true);
            offer(target, pending);
            return Map.of("ok", "true");
        } catch (RuntimeException garbled) {
            return Map.of("ok", "false");
        }
    }

    /** The arena waits for this player — an invite they made was accepted over there. */
    private Map<String, String> summon(Map<String, String> data) {
        try {
            Player player = server.getPlayer(UUID.fromString(data.get("player")));
            if (player == null || !invites.madeBy(data.get("agreed"), player.getUniqueId())) {
                return Map.of("ok", "false");
            }
            Map<String, String> intent = new HashMap<>();
            intent.put("kind", "AGREED");
            intent.put("agreed", data.get("agreed"));
            Scheduling.entity(plugin, player, () -> {
                messages.send(player, "arenagate.summoned");
                gate.go(player, intent);
            });
            return Map.of("ok", "true");
        } catch (RuntimeException garbled) {
            return Map.of("ok", "false");
        }
    }

    /** Says yes to an invite: one from the arena is answered there; one from here sends both over, agreed. */
    public void accept(Player player, String id) {
        if (id == null && invites.forPlayer(player.getUniqueId()).size() > 1) {
            messages.send(player, "arenagate.invite.which");
            return;
        }
        invites.take(player.getUniqueId(), id).ifPresentOrElse(pending -> {
            consents.agree(player.getUniqueId(), pending.spec().stake(), pending.spec().isOwnGear());
            if (pending.fromArena()) {
                link.request(ArenaProtocol.INVITE_ANSWER, Map.of("id", pending.id(), "accepted", "true",
                        "player", player.getUniqueId().toString()), java.time.Duration.ofSeconds(8))
                        .whenComplete((answer, failure) -> Scheduling.entity(plugin, player, () -> {
                            if (failure != null || !"true".equals(answer.get("ok"))) {
                                messages.send(player, "arenagate.invite.gone");
                                return;
                            }
                            gate.go(player, agreed(pending));
                        }));
                return;
            }
            Player inviter = server.getPlayer(pending.from());
            if (inviter == null) {
                messages.send(player, "arenagate.invite.gone");
                return;
            }
            messages.send(inviter, "arenagate.invite.accepted", "player", player.getName());
            consents.agree(inviter.getUniqueId(), pending.spec().stake(), pending.spec().isOwnGear());
            Map<String, String> intent = agreed(pending);
            gate.go(inviter, intent);
            gate.go(player, intent);
        }, () -> messages.send(player, "arenagate.invite.none"));
    }

    public void deny(Player player, String id) {
        invites.take(player.getUniqueId(), id).ifPresentOrElse(pending -> {
            messages.send(player, "arenagate.invite.you-declined", "player", pending.fromName());
            if (pending.fromArena()) {
                link.send(ArenaProtocol.INVITE_ANSWER, Map.of("id", pending.id(), "accepted", "false"));
            } else {
                Player inviter = server.getPlayer(pending.from());
                if (inviter != null) {
                    messages.send(inviter, "arenagate.invite.declined", "player", player.getName());
                }
            }
        }, () -> messages.send(player, "arenagate.invite.none"));
    }

    /** {@code /pvp invite} here: somebody here gets the buttons; somebody in the arena is asked over there. */
    public void invite(Player from, String targetName, MatchSpec spec) {
        consents.agree(from.getUniqueId(), spec.stake(), spec.isOwnGear());
        Player target = server.getPlayerExact(targetName);
        if (target != null) {
            if (target.equals(from)) {
                messages.send(from, "arenagate.invite.self");
                return;
            }
            if (invites.asked(from.getUniqueId(), target.getUniqueId())) {
                messages.send(from, "arenagate.invite.already", "player", target.getName());
                return;
            }
            GateInvites.Pending pending = invites.add(UUID.randomUUID().toString().substring(0, 6), from.getUniqueId(),
                    from.getName(), target.getUniqueId(), spec, false);
            messages.send(from, "arenagate.invite.sent", "player", target.getName());
            offer(target, pending);
            return;
        }
        if (!link.isConnected()) {
            messages.send(from, "arenagate.unreachable");
            return;
        }
        Map<String, String> data = new HashMap<>(SpecCodec.encode(spec));
        String id = UUID.randomUUID().toString().substring(0, 6);
        invites.outgoing(id, from.getUniqueId());
        data.put("id", id);
        data.put("from", from.getUniqueId().toString());
        data.put("fromName", from.getName());
        // Only the name: the arena knows its own players and finds them by it.
        data.put("toName", targetName);
        link.request(ArenaProtocol.INVITE, data, java.time.Duration.ofSeconds(8))
                .whenComplete((answer, failure) -> Scheduling.entity(plugin, from, () -> messages.send(from,
                        failure == null && "true".equals(answer.get("ok")) ? "arenagate.invite.sent-arena"
                                : "arenagate.invite.unknown", "player", targetName)));
    }

    private Map<String, String> agreed(GateInvites.Pending pending) {
        Map<String, String> intent = new HashMap<>(SpecCodec.encode(pending.spec()));
        intent.put("kind", "AGREED");
        intent.put("agreed", pending.id());
        String sides = pending.spec().mode() == Mode.WAVES
                ? pending.from() + "," + pending.to()
                : pending.from() + "|" + pending.to();
        intent.put("sides", sides);
        return intent;
    }

    private void offer(Player target, GateInvites.Pending pending) {
        MatchSpec spec = pending.spec();
        messages.send(target, spec.mode() == Mode.WAVES ? "arenagate.invite.received-waves" : "arenagate.invite.received",
                "player", pending.fromName(), "mode", spec.mode().title(),
                "kit", spec.isOwnGear() ? "own gear" : spec.kit(), "rounds", spec.bestOf(), "stake", spec.stake(),
                "id", pending.id(), "where", pending.fromArena() ? "in the arena" : "here");
    }

    static String csv(Set<UUID> players) {
        return players.stream().map(UUID::toString).collect(Collectors.joining(","));
    }
}
