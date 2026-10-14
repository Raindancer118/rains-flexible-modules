package de.raindancer.modules.arenagate.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.arena.api.ArenaProtocol;
import de.raindancer.modules.arena.api.MatchSpec;
import de.raindancer.modules.arena.api.Mode;
import de.raindancer.modules.arena.api.SpecCodec;
import de.raindancer.modules.arena.api.wire.Wire;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The survival side of the way to the arena: holding and settling stakes, copying gear, finding players by name,
 * invites from the arena, sending somebody over who is waited for, and knowing who is coming back. Over the server
 * link or, on a network, to the one arena server the settings name — any number of survival servers can share it.
 *
 * <p>Handlers run on the wire's threads, never the bus's; anything that touches a player hops to that player's own.
 */
public final class GateNetwork {

    private static final de.raindancer.core.platform.log.LogChannel LOG = de.raindancer.core.platform.log.Log.of("arenagate");

    private final Plugin plugin;
    private final Server server;
    private final Messages messages;
    private final Wire wire;
    private final java.util.function.Supplier<String> arena;
    private final EscrowService escrow;
    private final GearService gear;
    private final GateInvites invites;
    private final GateService gate;
    private final GateConsents consents;
    private final java.util.function.Predicate<UUID> hidden;
    private final Set<UUID> returning = ConcurrentHashMap.newKeySet();
    private final de.raindancer.modules.arenagate.rules.InviteMissRule missed =
            new de.raindancer.modules.arenagate.rules.InviteMissRule();
    private volatile java.util.function.Function<String, Optional<String>> whereElse = name -> Optional.empty();

    /** @param arena the network id (or proxy name) of the arena server, as the settings say it now */
    public GateNetwork(Plugin plugin, Server server, Messages messages, Wire wire,
                       java.util.function.Supplier<String> arena, EscrowService escrow,
                       GearService gear, GateInvites invites, GateService gate, GateConsents consents,
                       java.util.function.Predicate<UUID> hidden) {
        this.consents = consents;
        this.hidden = hidden;
        this.plugin = plugin;
        this.server = server;
        this.messages = messages;
        this.wire = wire;
        this.arena = arena;
        this.escrow = escrow;
        this.gear = gear;
        this.invites = invites;
        this.gate = gate;
    }

    /** Which other server of the network a name is on (hub, OneBlock…), for an invite that found them in neither place. */
    public void whereElse(java.util.function.Function<String, Optional<String>> lookup) {
        this.whereElse = lookup;
    }

    public void listen() {
        // Everything here is the arena's to ask. On a network every server can send these, and a server that is not
        // the arena must not hold, pay or refund a stake, copy gear, or invite and summon in its name.
        // Money and gear wait on the bank, the database and the world: answered later, off the bus's thread.
        onLaterFromArena(ArenaProtocol.STAKE_HOLD, (peer, data) -> CompletableFuture.completedFuture(hold(data)));
        onLaterFromArena(ArenaProtocol.STAKE_PAY, (peer, data) -> CompletableFuture.completedFuture(pay(data)));
        onLaterFromArena(ArenaProtocol.STAKE_REFUND, (peer, data) -> CompletableFuture.completedFuture(
                Map.of("ok", String.valueOf(escrow.refund(data.get("fight"))))));
        onLaterFromArena(ArenaProtocol.GEAR, (peer, data) -> CompletableFuture.completedFuture(gear(data)));
        onFromArena(ArenaProtocol.CONSENT, (peer, data) -> {
            try {
                consents.agree(UUID.fromString(data.get("player")), Long.parseLong(data.getOrDefault("stake", "0")),
                        "true".equals(data.get("gear")));
            } catch (RuntimeException garbled) {
                // An agreement that cannot be read is no agreement; whatever needs it is refused.
            }
            return null;
        });
        onFromArena(ArenaProtocol.WHERE, (peer, data) -> where(data));
        onFromArena(ArenaProtocol.GROUP, (peer, data) -> {
            try {
                return de.raindancer.core.social.team.Groups.of(UUID.fromString(data.get("player"))).stream().findFirst()
                        .map(group -> Map.of("id", group.id(), "tag", group.tag(), "name", group.name()))
                        .orElse(Map.of("id", ""));
            } catch (RuntimeException garbled) {
                return Map.of("id", "");
            }
        });
        onFromArena(ArenaProtocol.INVITE, (peer, data) -> inviteFromArena(data));
        onFromArena(ArenaProtocol.SUMMON, (peer, data) -> summon(data));
        onFromArena(ArenaProtocol.INVITE_CANCEL, (peer, data) -> {
            java.util.Optional<GateInvites.Pending> taken;
            try {
                taken = invites.cancel(UUID.fromString(data.get("from")), data.get("id"));
            } catch (RuntimeException garbled) {
                return Map.of("ok", "false");
            }
            taken.ifPresent(pending -> {
                Player target = server.getPlayer(pending.to());
                if (target != null) {
                    messages.send(target, "arenagate.invite.cancelled", "player", pending.fromName());
                }
            });
            return Map.of("ok", String.valueOf(taken.isPresent()));
        });
        onFromArena(ArenaProtocol.RETURNING, (peer, data) -> {
            try {
                returning.add(UUID.fromString(data.get("player")));
            } catch (RuntimeException garbled) {
                // Somebody's welcome back reads as a plain join; nothing else depends on it.
            }
            return null;
        });
    }

    /** Whether {@code peer} is the arena: on the link the one peer there is, on a network the server the settings name. */
    boolean isArena(String peer) {
        return wire.defaultPeer().isPresent() || peer != null && peer.equalsIgnoreCase(arena.get());
    }

    private static final Map<String, String> REFUSED = Map.of("ok", "false", "who", "nobody (not asked by the arena)");

    private void onFromArena(String topic, Wire.Handler handler) {
        wire.on(topic, (peer, data) -> isArena(peer) ? handler.handle(peer, data) : refused(topic, peer));
    }

    private void onLaterFromArena(String topic, Wire.LaterHandler handler) {
        wire.onLater(topic, (peer, data) -> isArena(peer) ? handler.handle(peer, data)
                : CompletableFuture.completedFuture(refused(topic, peer)));
    }

    private Map<String, String> refused(String topic, String peer) {
        LOG.warn("Server '{}' asked {}, which only the arena ('{}') may ask; refused.", peer, topic, arena.get());
        return REFUSED;
    }

    public void stop() {
        wire.close();
    }

    private Map<String, String> gear(Map<String, String> data) {
        try {
            return consents.takeGear(UUID.fromString(data.get("player"))) ? this.gear.copy(data) : Map.of("ok", "false");
        } catch (RuntimeException garbled) {
            return Map.of("ok", "false");
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
            String everybody = data.getOrDefault("participants", "");
            Set<UUID> participants = everybody.isBlank() ? null : Arrays.stream(everybody.split(","))
                    .map(UUID::fromString).collect(Collectors.toSet());
            String text = data.getOrDefault("payouts", "");
            for (String pair : text.split(",")) {
                if (pair.isBlank()) {
                    continue;
                }
                String[] parts = pair.split("=");
                payouts.merge(UUID.fromString(parts[0]), Long.parseLong(parts[1]), Long::sum);
            }
            return Map.of("ok", String.valueOf(escrow.pay(data.get("fight"), payouts, participants)));
        } catch (RuntimeException garbled) {
            return Map.of("ok", "false");
        }
    }

    private Map<String, String> where(Map<String, String> data) {
        Player online = server.getPlayerExact(data.getOrDefault("name", ""));
        // A vanished player is not online as far as the arena is told — no invite, no payment gives them away.
        return online == null || hidden.test(online.getUniqueId()) ? Map.of("online", "false")
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
                wire.ask(arena.get(), ArenaProtocol.INVITE_ANSWER, Map.of("id", pending.id(), "accepted", "true",
                        "player", player.getUniqueId().toString()))
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

    /** The one who asked takes it back: one to somebody here here, one to somebody in the arena over there. */
    public void cancel(Player player, String id) {
        if (id == null) {
            messages.send(player, "arenagate.invite.nothing-to-cancel");
            return;
        }
        java.util.Optional<GateInvites.Pending> here = invites.cancel(player.getUniqueId(), id);
        if (here.isPresent()) {
            messages.send(player, "arenagate.invite.you-cancelled");
            Player target = server.getPlayer(here.get().to());
            if (target != null) {
                messages.send(target, "arenagate.invite.cancelled", "player", player.getName());
            }
            return;
        }
        if (!invites.madeBy(id, player.getUniqueId())) {
            messages.send(player, "arenagate.invite.nothing-to-cancel");
            return;
        }
        wire.tell(arena.get(), ArenaProtocol.INVITE_CANCEL, Map.of("id", id, "from", player.getUniqueId().toString()));
        messages.send(player, "arenagate.invite.you-cancelled");
    }

    public void deny(Player player, String id) {
        invites.take(player.getUniqueId(), id).ifPresentOrElse(pending -> {
            messages.send(player, "arenagate.invite.you-declined", "player", pending.fromName());
            if (pending.fromArena()) {
                wire.tell(arena.get(), ArenaProtocol.INVITE_ANSWER, Map.of("id", pending.id(), "accepted", "false"));
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
            messages.send(from, "arenagate.invite.sent", "player", target.getName(), "id", pending.id());
            offer(target, pending);
            return;
        }
        if (!wire.reachable(arena.get())) {
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
        wire.ask(arena.get(), ArenaProtocol.INVITE, data)
                .whenComplete((answer, failure) -> Scheduling.entity(plugin, from, () -> {
                    if (failure == null && "true".equals(answer.get("ok"))) {
                        messages.send(from, "arenagate.invite.sent-arena", "player", targetName, "id", id);
                        return;
                    }
                    Optional<String> elsewhere = whereElse.apply(targetName);
                    messages.send(from, missed.keyFor(elsewhere), "player", targetName, "server", elsewhere.orElse(""));
                }));
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
