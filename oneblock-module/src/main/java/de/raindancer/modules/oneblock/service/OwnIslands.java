package de.raindancer.modules.oneblock.service;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.oneblock.model.Content;
import de.raindancer.modules.oneblock.model.Invite;
import de.raindancer.modules.oneblock.model.Island;
import de.raindancer.modules.oneblock.model.IslandDimension;
import de.raindancer.modules.oneblock.model.IslandFacts;
import de.raindancer.modules.oneblock.model.IslandView;
import de.raindancer.modules.oneblock.model.Ranks;
import de.raindancer.modules.oneblock.model.Reply;
import de.raindancer.modules.oneblock.store.IslandBook;
import de.raindancer.modules.oneblock.store.IslandStorage;
import de.raindancer.modules.oneblock.store.PendingCommands;
import de.raindancer.modules.oneblock.store.PlayerSpot;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * {@link IslandHost} on this module's own islands: the invites and the sidebar ask here. A team invite needs no
 * second bookkeeping — the invite book is the only one — so offering and withdrawing are nothing.
 */
public final class OwnIslands implements IslandHost {

    private final Server server;
    private final IslandBook book;
    private final IslandService islands;
    private final IslandTravel travel;
    private final Messages messages;
    private final Supplier<Content> content;
    private final Predicate<UUID> hidden;
    /** What to show somebody without an island: the page to make one. */
    private final Consumer<Player> noIsland;
    /** What to show an owner whose island has not chosen its money yet. */
    private final Consumer<Player> undecided;
    private final IslandStorage storage;
    private final PendingCommands pending;
    private final Executor async;
    private final BiConsumer<Player, Runnable> onPlayer;

    public OwnIslands(Server server, IslandBook book, IslandService islands, IslandTravel travel, Messages messages,
                      Supplier<Content> content, Predicate<UUID> hidden, Consumer<Player> noIsland,
                      Consumer<Player> undecided, IslandStorage storage, PendingCommands pending, Executor async,
                      BiConsumer<Player, Runnable> onPlayer) {
        this.server = server;
        this.book = book;
        this.islands = islands;
        this.travel = travel;
        this.messages = messages;
        this.content = content;
        this.hidden = hidden;
        this.noIsland = noIsland;
        this.undecided = undecided;
        this.storage = storage;
        this.pending = pending;
        this.async = async;
        this.onPlayer = onPlayer;
    }

    public PendingCommands pending() {
        return pending;
    }

    @Override
    public boolean ready() {
        return true;
    }

    @Override
    public Optional<IslandFacts> facts(UUID inviter, UUID invitee) {
        return book.ofPlayer(inviter).map(island -> new IslandFacts(island.id().toString(),
                Ranks.of(island, inviter), Ranks.OWNER, island.everyone().size(), content.get().teamSize(),
                island.isMember(invitee), false, island.mode()));
    }

    @Override
    public void offerTeam(Invite invite) {
    }

    @Override
    public void withdrawTeam(Invite invite) {
    }

    @Override
    public void join(Invite invite) {
        Player player = server.getPlayer(invite.to().id());
        Reply reply = islands.join(invite.to().id(), UUID.fromString(invite.islandId()));
        if (player == null) {
            return;
        }
        Replies.say(messages, player, reply);
        if (reply.ok()) {
            book.get(UUID.fromString(invite.islandId()))
                    .ifPresent(island -> travel.go(player, island, IslandDimension.OVERWORLD));
        }
    }

    @Override
    public void visit(UUID playerId, String islandId) {
        Player player = server.getPlayer(playerId);
        Optional<Island> island = parse(islandId).flatMap(book::get);
        if (player == null || island.isEmpty()) {
            return;
        }
        travel.letIn(playerId, island.get());
        travel.go(player, island.get(), IslandDimension.OVERWORLD);
    }

    @Override
    public boolean inGameWorld(UUID playerId) {
        Player player = server.getPlayer(playerId);
        return player != null && Island.isIslandWorld(player.getWorld().getName());
    }

    @Override
    public Optional<IslandView> view(UUID playerId) {
        Player player = server.getPlayer(playerId);
        if (player == null) {
            return Optional.empty();
        }
        String world = player.getWorld().getName();
        Optional<Island> here = book.ofWorld(world);
        Optional<Island> island = here.or(() -> book.ofPlayer(playerId));
        if (island.isEmpty()) {
            return Optional.empty();
        }
        Island shown = island.get();
        Optional<IslandDimension> dimension = here.isPresent() ? Island.dimensionOf(world) : Optional.empty();
        long broken = shown.block(dimension.orElse(IslandDimension.OVERWORLD)).map(block -> block.broken()).orElse(0L);
        int online = (int) shown.everyone().stream()
                .filter(member -> server.getPlayer(member) != null && !hidden.test(member)).count();
        return Optional.of(new IslandView(here.isPresent(), shown.isMember(playerId), shown.ownerName(),
                Ranks.of(shown, playerId), shown.everyone().size(), content.get().teamSize(), online, shown.mode(),
                shown.shards(), dimension, broken));
    }

    @Override
    public void home(UUID playerId) {
        Player player = server.getPlayer(playerId);
        if (player == null) {
            return;
        }
        Optional<Island> island = book.ofPlayer(playerId);
        if (island.isEmpty()) {
            noIsland.accept(player);
            return;
        }
        travel.go(player, island.get(), IslandDimension.OVERWORLD);
    }

    /**
     * Back where they were: a command typed on another server first, else the spot they left an island from — read
     * here because their world may well be unloaded by now, and the server put them at the main spawn — else home.
     */
    @Override
    public void arrived(UUID playerId) {
        Player player = server.getPlayer(playerId);
        if (player == null) {
            return;
        }
        Optional<String> typed = pending.take(playerId);
        if (typed.isPresent()) {
            player.performCommand("island " + typed.get());
            askForTheMoney(player);
            return;
        }
        async.execute(() -> {
            Optional<PlayerSpot> left = storage.position(playerId);
            onPlayer.accept(player, () -> {
                if (!player.isOnline() || !resume(player, left)) {
                    home(playerId);
                }
                askForTheMoney(player);
            });
        });
    }

    private boolean resume(Player player, Optional<PlayerSpot> left) {
        if (left.isEmpty()) {
            return false;
        }
        Optional<Island> island = book.ofWorld(left.get().world());
        Optional<IslandDimension> dimension = Island.dimensionOf(left.get().world());
        if (island.isEmpty() || dimension.isEmpty() || travel.refusal(player, island.get(), dimension.get()).isPresent()) {
            return false;
        }
        Optional<org.bukkit.World> world = travel.worlds().load(island.get(), dimension.get());
        if (world.isEmpty()) {
            return false;
        }
        PlayerSpot spot = left.get();
        player.teleportAsync(new org.bukkit.Location(world.get(), spot.x(), spot.y(), spot.z(), spot.yaw(), spot.pitch()));
        return true;
    }

    /** An owner whose island came over from BentoBox chooses its money before anything else. */
    private void askForTheMoney(Player player) {
        book.ofPlayer(player.getUniqueId())
                .filter(island -> island.owner().equals(player.getUniqueId()) && !island.mode().decided())
                .ifPresent(island -> undecided.accept(player));
    }

    private static Optional<UUID> parse(String id) {
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException | NullPointerException garbled) {
            return Optional.empty();
        }
    }
}
