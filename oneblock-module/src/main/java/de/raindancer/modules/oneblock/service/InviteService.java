package de.raindancer.modules.oneblock.service;

import de.raindancer.core.platform.link.LinkDirectory;
import de.raindancer.modules.oneblock.OneBlockSettings;
import de.raindancer.modules.oneblock.model.EconomyMode;
import de.raindancer.modules.oneblock.model.Invite;
import de.raindancer.modules.oneblock.model.InviteKind;
import de.raindancer.modules.oneblock.model.IslandFacts;
import de.raindancer.modules.oneblock.model.Reply;
import de.raindancer.modules.oneblock.model.Requester;
import de.raindancer.modules.oneblock.rules.InviteRule;
import de.raindancer.modules.oneblock.store.InviteBook;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The island server's side of invites, wherever on the network they were typed: deciding, remembering, telling both
 * players, and finishing the job when the invited player arrives.
 *
 * <p>Joining a team and visiting are the {@link IslandHost}'s, run for the player once they are here.
 */
public final class InviteService implements IOneBlockService {

    /** How long after accepting elsewhere an arrival still counts: a proxy move takes seconds, not minutes. */
    public static final long ARRIVAL_MILLIS = 120_000L;

    /** A line for a player on this server or any linked one. */
    @FunctionalInterface
    public interface Delivery {
        void tell(String name, String key, String... values);
    }

    private record Arrival(Invite invite, long until) {
    }

    private final IslandHost host;
    private final InviteBook book;
    private final InviteRule rule;
    private final Delivery delivery;
    private final Function<String, CompletableFuture<Optional<LinkDirectory.Found>>> resolve;
    private final Executor onServer;
    private final LongSupplier clock;
    private final Map<UUID, Arrival> arrivals = new ConcurrentHashMap<>();
    private volatile OneBlockSettings settings;

    public InviteService(IslandHost host, InviteBook book, InviteRule rule, Delivery delivery,
                         Function<String, CompletableFuture<Optional<LinkDirectory.Found>>> resolve, Executor onServer,
                         LongSupplier clock, OneBlockSettings settings) {
        this.host = host;
        this.book = book;
        this.rule = rule;
        this.delivery = delivery;
        this.resolve = resolve;
        this.onServer = onServer;
        this.clock = clock;
        settings(settings);
    }

    @Override
    public void settings(OneBlockSettings fresh) {
        this.settings = fresh;
    }

    public CompletableFuture<Reply> invite(Requester from, String toName, InviteKind kind) {
        if (toName == null || toName.isBlank()) {
            return CompletableFuture.completedFuture(Reply.refused("oneblock.usage"));
        }
        return resolve.apply(toName)
                .thenCompose(found -> onServer(() -> decide(from, toName, found, kind)))
                .exceptionally(failed -> Reply.refused("oneblock.refuse.asleep"));
    }

    private Reply decide(Requester from, String typed, Optional<LinkDirectory.Found> found, InviteKind kind) {
        if (!host.ready()) {
            return Reply.refused("oneblock.refuse.asleep");
        }
        if (found.isEmpty()) {
            return Reply.refused("oneblock.refuse.nobody", "player", typed);
        }
        Requester to = new Requester(found.get().id(), found.get().name());
        Optional<IslandFacts> island = host.facts(from.id(), to.id());
        Optional<String> refusal = rule.judge(kind, from, to.id(), island);
        if (refusal.isPresent()) {
            String most = island.map(facts -> String.valueOf(facts.mostMembers())).orElse("0");
            return Reply.refused(refusal.get(), "player", to.name(), "most", most);
        }
        String minutes = String.valueOf(settings.inviteMinutesClamped());
        Invite invite = new Invite(kind, from, to, island.get().islandId(),
                clock.getAsLong() + settings.inviteMinutesClamped() * 60_000L);
        book.add(invite).filter(old -> old.kind() == InviteKind.TEAM).ifPresent(host::withdrawTeam);
        if (kind == InviteKind.TEAM) {
            host.offerTeam(invite);
        }
        delivery.tell(to.name(), receivedKey(kind, island.get().mode()), "player", from.name(), "minutes", minutes);
        return Reply.ok(kind == InviteKind.TEAM ? "oneblock.invite.sent-team" : "oneblock.invite.sent-guest",
                "player", to.name(), "minutes", minutes);
    }

    /**
     * An answer to an invite.
     *
     * @param fromName whose invite; null for the only one there is
     * @param here     whether the player is on the island server already, or has to be sent over first
     */
    public CompletableFuture<Reply> answer(Requester who, String fromName, boolean accept, boolean here) {
        return onServer(() -> {
            InviteBook.Pick pick = book.pick(who.id(), fromName);
            if (pick.several()) {
                return Reply.refused("oneblock.refuse.several");
            }
            if (pick.invite().isEmpty() || !book.take(pick.invite().get())) {
                return Reply.refused("oneblock.refuse.no-invite");
            }
            Invite invite = pick.invite().get();
            String inviter = invite.from().name();
            if (!accept) {
                if (invite.kind() == InviteKind.TEAM) {
                    host.withdrawTeam(invite);
                }
                delivery.tell(inviter, "oneblock.invite.declined", "player", who.name());
                return Reply.ok("oneblock.invite.declined-by-you", "player", inviter);
            }
            delivery.tell(inviter, "oneblock.invite.accepted", "player", who.name());
            if (here) {
                perform(invite);
                return invite.kind() == InviteKind.TEAM ? Reply.silent()
                        : Reply.ok("oneblock.visit.taking-you", "player", inviter);
            }
            arrivals.put(who.id(), new Arrival(invite, clock.getAsLong() + ARRIVAL_MILLIS));
            return Reply.ok("oneblock.accept.on-the-way", "player", inviter).andTransfer();
        }).exceptionally(failed -> Reply.refused("oneblock.refuse.asleep"));
    }

    /**
     * A player has joined the island server. What they accepted elsewhere is finished, once; with nothing to finish,
     * somebody outside the islands — sent by {@code /island} or the hub — goes to theirs, or gets one. Somebody who
     * logged out on an island is left where they were. Server thread.
     *
     * @return whether an accepted invite was finished
     */
    public boolean joined(UUID player) {
        Arrival arrival = arrivals.remove(player);
        if (arrival != null && clock.getAsLong() <= arrival.until()) {
            perform(arrival.invite());
            return true;
        }
        if (host.ready() && !host.inGameWorld(player)) {
            host.arrived(player);
        }
        return false;
    }

    private void perform(Invite invite) {
        if (invite.kind() == InviteKind.TEAM) {
            host.join(invite);
        } else {
            host.visit(invite.to().id(), invite.islandId());
        }
    }

    /**
     * One wording per money rather than a word put in: the line may be said on another server, and every server has
     * the same wording, so the badge keeps its colours there.
     */
    static String receivedKey(InviteKind kind, EconomyMode mode) {
        boolean team = kind == InviteKind.TEAM;
        return switch (mode) {
            case PURE -> team ? "oneblock.invite.received-team.pure" : "oneblock.invite.received-guest.pure";
            case SMP -> team ? "oneblock.invite.received-team.smp" : "oneblock.invite.received-guest.smp";
            case UNDECIDED -> team ? "oneblock.invite.received-team.undecided" : "oneblock.invite.received-guest.undecided";
        };
    }

    /** Runs out what nobody answered. Server thread. */
    public void sweep() {
        long now = clock.getAsLong();
        for (Invite gone : book.expire(now)) {
            if (gone.kind() == InviteKind.TEAM) {
                host.withdrawTeam(gone);
            }
            delivery.tell(gone.from().name(), "oneblock.invite.ran-out", "player", gone.to().name());
        }
        arrivals.values().removeIf(arrival -> now > arrival.until());
    }

    private CompletableFuture<Reply> onServer(Supplier<Reply> work) {
        return CompletableFuture.supplyAsync(work, onServer);
    }
}
