package de.raindancer.modules.arena.service;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.arena.api.ArenaProtocol;
import de.raindancer.modules.arena.api.MatchSpec;
import de.raindancer.modules.arena.api.Mode;
import de.raindancer.modules.arena.api.SpecCodec;
import de.raindancer.modules.arena.model.Invite;
import de.raindancer.modules.arena.rules.InviteRule;
import de.raindancer.modules.arena.rules.PairingRule;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Asking somebody to a fight, here or across on a survival server.
 *
 * <p>Both here: an accepted invite is an agreed fight and goes ahead of the queue. The other one on survival: the invite
 * goes to the survival server they are on and they answer there; on yes, that server sends them here, and the fight
 * starts once they have arrived. An invite from survival to somebody here works the same way round: on yes, the
 * survival server of the one who asked is asked to send them.
 */
public final class InviteService {

    private static final long LIFETIME = 60_000;

    private final Server server;
    private final Messages messages;
    private final ArenaLink link;
    private final QueueService queue;
    private final AgreedFights agreedFights;
    private final Predicate<UUID> busy;
    private final Predicate<String> kitKnown;
    private final LongSupplier clock;
    private final InviteRule rule = new InviteRule();
    private final Map<String, Invite> open = new ConcurrentHashMap<>();
    private final AnsweredInvites answered = new AnsweredInvites();
    /** The survival server the other end of an open invite is on, by invite id. */
    private final Map<String, String> across = new ConcurrentHashMap<>();
    private volatile long mostStake;

    public InviteService(Server server, Messages messages, ArenaLink link, QueueService queue, AgreedFights agreedFights,
                         Predicate<UUID> busy, Predicate<String> kitKnown, LongSupplier clock, long mostStake) {
        this.server = server;
        this.messages = messages;
        this.link = link;
        this.queue = queue;
        this.agreedFights = agreedFights;
        this.busy = busy;
        this.kitKnown = kitKnown;
        this.clock = clock;
        this.mostStake = mostStake;
    }

    public void mostStake(long value) {
        this.mostStake = value;
    }

    public void invite(Player from, String targetName, MatchSpec spec) {
        Player here = server.getPlayerExact(targetName);
        if (here != null) {
            if (refused(from, here.getUniqueId(), here.getName(), spec, true)) {
                return;
            }
            Invite invite = create(from, here.getUniqueId(), here.getName(), spec);
            queue.agreed(from.getUniqueId(), spec);
            messages.send(from, "arena.invite.sent", "player", here.getName(), "id", invite.id());
            offer(here, invite);
            return;
        }
        if (!link.isUp()) {
            refused(from, null, targetName, spec, false);
            return;
        }
        link.find(targetName).whenComplete((found, failure) -> {
            if (failure != null || found.isEmpty()) {
                refused(from, null, targetName, spec, false);
                return;
            }
            UUID target = found.get().id();
            String name = found.get().name();
            String peer = found.get().server();
            if (refused(from, target, name, spec, true)) {
                return;
            }
            Invite invite = create(from, target, name, spec);
            across.put(invite.id(), peer);
            queue.agreed(from.getUniqueId(), spec);
            Map<String, String> data = new HashMap<>(SpecCodec.encode(spec));
            data.put("id", invite.id());
            data.put("from", from.getUniqueId().toString());
            data.put("fromName", from.getName());
            data.put("to", target.toString());
            data.put("toName", name);
            link.ask(peer, ArenaProtocol.INVITE, data).whenComplete((sent, failed) -> {
                if (failed != null || !"true".equals(sent.get("ok"))) {
                    open.remove(invite.id());
                    messages.send(from, "arena.invite.refuse.unknown", "player", name);
                } else {
                    messages.send(from, "arena.invite.sent-across", "player", name, "id", invite.id());
                }
            });
        });
    }

    /** An invite from somebody on survival (the server {@code peer}) to somebody here. @return the answer */
    public Map<String, String> fromAcross(String peer, Map<String, String> data) {
        // From survival only the name comes: this server knows its own players by it.
        String to = data.get("to");
        Player target = to != null && !to.isBlank() ? server.getPlayer(UUID.fromString(to))
                : server.getPlayerExact(data.getOrDefault("toName", ""));
        if (target == null) {
            return Map.of("ok", "false", "reason", "offline");
        }
        if (busy.test(target.getUniqueId())) {
            return Map.of("ok", "false", "reason", "busy");
        }
        MatchSpec spec = SpecCodec.decode(data);
        Invite invite = new Invite(data.get("id"), UUID.fromString(data.get("from")), data.get("fromName"),
                target.getUniqueId(), target.getName(), spec, clock.getAsLong() + LIFETIME);
        open.put(invite.id(), invite);
        across.put(invite.id(), peer);
        offer(target, invite);
        return Map.of("ok", "true");
    }

    /** The answer, given on survival, to an invite from here. */
    public Map<String, String> answeredAcross(String peer, Map<String, String> data) {
        String id = data.getOrDefault("id", "");
        if (!answeredBy(id, peer)) {
            return Map.of("ok", "false");
        }
        Invite invite = open.remove(id);
        if (invite == null || invite.isExpired(clock.getAsLong())) {
            return Map.of("ok", "false");
        }
        Player inviter = server.getPlayer(invite.from());
        if (!"true".equals(data.get("accepted"))) {
            if (inviter != null) {
                messages.send(inviter, "arena.invite.declined", "player", invite.toName());
            }
            return Map.of("ok", "true");
        }
        if (inviter == null || busy.test(invite.from())) {
            return Map.of("ok", "false");
        }
        // The one who said yes is on their way; the fight starts once they are here.
        agreedFights.agree(invite.id(), invite.spec(), sides(invite));
        agreedFights.arrived(invite.id(), invite.from());
        messages.send(inviter, "arena.invite.accepted-across", "player", invite.toName());
        return Map.of("ok", "true");
    }

    public void accept(Player player, String id) {
        Optional<Invite> found = find(player, id);
        if (found.isEmpty()) {
            return;
        }
        Invite invite = found.get();
        String peer = across.getOrDefault(invite.id(), "");
        open.remove(invite.id());
        answered.remember(invite.id(), player.getUniqueId(), clock.getAsLong());
        queue.agreed(player.getUniqueId(), invite.spec());
        if (busy.test(player.getUniqueId())) {
            messages.send(player, "arena.invite.refuse.you-busy");
            return;
        }
        Player inviter = server.getPlayer(invite.from());
        if (inviter != null) {
            messages.send(inviter, "arena.invite.accepted", "player", player.getName());
            messages.send(player, "arena.invite.you-accepted", "player", invite.fromName());
            queue.reserve(new PairingRule.Group(invite.spec(), sides(invite)));
            return;
        }
        // The one who asked is on survival: they are sent over, and the fight starts once they are here.
        agreedFights.agree(invite.id(), invite.spec(), sides(invite));
        agreedFights.arrived(invite.id(), player.getUniqueId());
        link.ask(peer, ArenaProtocol.SUMMON,
                Map.of("player", invite.from().toString(), "agreed", invite.id()))
                .whenComplete((answer, failure) -> {
                    if (failure != null || !"true".equals(answer.get("ok"))) {
                        messages.send(player, "arena.invite.gone");
                    } else {
                        messages.send(player, "arena.invite.coming", "player", invite.fromName());
                    }
                });
    }

    /** The one who asked takes it back: {@code /pvp cancel [id]}, or the [Cancel] button. */
    public void cancel(Player player, String id) {
        List<Invite> mine = madeBy(player.getUniqueId());
        Optional<Invite> found = id == null ? (mine.size() == 1 ? Optional.of(mine.getFirst()) : Optional.empty())
                : mine.stream().filter(invite -> invite.id().equalsIgnoreCase(id)).findFirst();
        if (found.isEmpty()) {
            messages.send(player, mine.isEmpty() || id != null ? "arena.invite.nothing-to-cancel" : "arena.invite.which");
            return;
        }
        Invite invite = found.get();
        String peer = across.getOrDefault(invite.id(), "");
        open.remove(invite.id());
        messages.send(player, "arena.invite.you-cancelled", "player", invite.toName());
        Player target = server.getPlayer(invite.to());
        if (target != null) {
            messages.send(target, "arena.invite.cancelled", "player", invite.fromName());
        } else {
            link.tell(peer, ArenaProtocol.INVITE_CANCEL,
                    Map.of("id", invite.id(), "from", invite.from().toString()));
        }
    }

    /** Somebody on survival took back an invite they made to somebody here. */
    public Map<String, String> cancelledAcross(String peer, Map<String, String> data) {
        Invite invite = open.get(data.getOrDefault("id", ""));
        if (invite == null || !invite.from().toString().equals(data.get("from")) || !answeredBy(invite.id(), peer)) {
            return Map.of("ok", "false");
        }
        open.remove(invite.id());
        Player target = server.getPlayer(invite.to());
        if (target != null) {
            messages.send(target, "arena.invite.cancelled", "player", invite.fromName());
        }
        return Map.of("ok", "true");
    }

    public List<Invite> madeBy(UUID player) {
        expire();
        return open.values().stream().filter(invite -> invite.from().equals(player)).toList();
    }

    public void deny(Player player, String id) {
        find(player, id).ifPresent(invite -> {
            open.remove(invite.id());
            messages.send(player, "arena.invite.you-declined", "player", invite.fromName());
            Player inviter = server.getPlayer(invite.from());
            if (inviter != null) {
                messages.send(inviter, "arena.invite.declined", "player", player.getName());
            }
        });
    }

    /** The agreed fight an arrival from survival belongs to, when they were the last it waited for. */
    public Optional<PairingRule.Group> arrived(String agreedId, UUID player) {
        return agreedFights.arrived(agreedId, player).map(agreed -> new PairingRule.Group(agreed.spec(), agreed.sides()));
    }

    public List<Invite> openFor(UUID player) {
        expire();
        return open.values().stream().filter(invite -> invite.to().equals(player)).toList();
    }

    private Optional<Invite> find(Player player, String id) {
        List<Invite> mine = openFor(player.getUniqueId());
        if (id != null) {
            Optional<Invite> named = mine.stream().filter(invite -> invite.id().equalsIgnoreCase(id)).findFirst();
            if (named.isEmpty()) {
                messages.send(player, answered.answeredBy(id, player.getUniqueId(), clock.getAsLong())
                        ? "arena.invite.already" : "arena.invite.gone");
            }
            return named;
        }
        if (mine.isEmpty()) {
            messages.send(player, "arena.invite.none");
            return Optional.empty();
        }
        if (mine.size() > 1) {
            // "The latest" would let somebody slip a different invite in just before the other types accept.
            messages.send(player, "arena.invite.which");
            return Optional.empty();
        }
        return Optional.of(mine.getFirst());
    }

    private Invite create(Player from, UUID to, String toName, MatchSpec spec) {
        String id = UUID.randomUUID().toString().substring(0, 6);
        Invite invite = new Invite(id, from.getUniqueId(), from.getName(), to, toName, spec, clock.getAsLong() + LIFETIME);
        open.put(id, invite);
        return invite;
    }

    private void offer(Player target, Invite invite) {
        MatchSpec spec = invite.spec();
        messages.send(target, spec.mode() == Mode.WAVES ? "arena.invite.received-waves" : "arena.invite.received",
                "player", invite.fromName(), "mode", spec.mode().title(),
                "kit", spec.isOwnGear() ? "own gear" : spec.kit(), "rounds", spec.bestOf(), "stake", spec.stake(),
                "id", invite.id());
    }

    private boolean refused(Player from, UUID target, String name, MatchSpec spec, boolean known) {
        expire();
        boolean asked = target != null && open.values().stream()
                .anyMatch(invite -> invite.from().equals(from.getUniqueId()) && invite.to().equals(target));
        Optional<String> refusal = rule.refusal(new InviteRule.Facts(target != null && target.equals(from.getUniqueId()),
                known, busy.test(from.getUniqueId()), target != null && busy.test(target), asked, spec.stake(),
                mostStake, spec.isOwnGear() || kitKnown.test(spec.kit())));
        refusal.ifPresent(key -> messages.send(from, key, "player", name, "kit", spec.kit(), "most", mostStake));
        return refusal.isPresent();
    }

    /** Only the survival server an invite went to (or came from) answers or takes back for it. */
    private boolean answeredBy(String id, String peer) {
        return peer.equals(across.getOrDefault(id, peer));
    }

    private void expire() {
        long now = clock.getAsLong();
        open.values().removeIf(invite -> invite.isExpired(now));
        across.keySet().retainAll(open.keySet());
    }

    private static List<Set<UUID>> sides(Invite invite) {
        if (invite.spec().mode() == Mode.WAVES) {
            return List.of(Set.of(invite.from(), invite.to()));
        }
        return List.of(Set.of(invite.from()), Set.of(invite.to()));
    }

    public void forget(UUID player) {
        open.values().removeIf(invite -> invite.from().equals(player));
        agreedFights.forget(player);
    }
}
