package de.raindancer.modules.oneblock.service;

import de.raindancer.modules.oneblock.OneBlockSettings;
import de.raindancer.modules.oneblock.model.Content;
import de.raindancer.modules.oneblock.model.EconomyMode;
import de.raindancer.modules.oneblock.model.Island;
import de.raindancer.modules.oneblock.model.IslandDimension;
import de.raindancer.modules.oneblock.model.Reply;
import de.raindancer.modules.oneblock.model.Spawn;
import de.raindancer.modules.oneblock.model.Upgrade;
import de.raindancer.modules.oneblock.rules.PurchaseRule;
import de.raindancer.modules.oneblock.store.IslandBook;

import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * What players do with islands: making one, choosing its money, the team, unlocking and upgrading. Decides nothing
 * itself — the book keeps one island per player and the island keeps its own invariants; the purchase rule prices.
 * Every answer is a {@link Reply}, said by whoever asked. No Bukkit here, so all of it is tested.
 */
public final class IslandService implements IOneBlockService {

    private final IslandBook book;
    private final Supplier<Content> content;
    private final LongSupplier clock;
    private final PurchaseRule purchases = new PurchaseRule();

    public IslandService(IslandBook book, Supplier<Content> content, LongSupplier clock) {
        this.book = book;
        this.content = content;
        this.clock = clock;
    }

    /** Nothing in the settings changes what players may do with their islands. */
    @Override
    public void settings(OneBlockSettings settings) {
    }

    public IslandBook book() {
        return book;
    }

    // ------------------------------------------------------------------ making one

    /** A new island for {@code owner}, with the money they chose — once, for good. */
    public Reply create(UUID owner, String name, EconomyMode mode) {
        if (mode == null || !mode.decided()) {
            return Reply.refused("oneblock.create.choose");
        }
        if (book.ofPlayer(owner).isPresent()) {
            return Reply.refused("oneblock.create.already");
        }
        Island island = Island.create(UUID.randomUUID(), owner, name, mode, clock.getAsLong());
        IslandBook.Changed added = book.add(island);
        return added.done() ? Reply.ok("oneblock.create.done", "mode-key", mode.nameKey())
                : Reply.refused("oneblock.create.already");
    }

    /** The owner of an island that came over from BentoBox picks its money, once. */
    public Reply decide(UUID owner, EconomyMode mode) {
        Optional<Island> island = book.ofPlayer(owner);
        if (island.isEmpty()) {
            return Reply.refused("oneblock.refuse.no-island");
        }
        if (!island.get().owner().equals(owner)) {
            return Reply.refused("oneblock.mode.owner-only");
        }
        IslandBook.Changed changed = book.change(island.get().id(), it -> it.decide(mode));
        return changed.done() ? Reply.ok("oneblock.mode.chosen", "mode-key", mode.nameKey())
                : Reply.refused("oneblock.mode.already");
    }

    // ------------------------------------------------------------------ the team

    /** Somebody who accepted a team invite joins the inviter's island. */
    public Reply join(UUID player, UUID islandId) {
        Optional<Island> island = book.get(islandId);
        if (island.isEmpty()) {
            return Reply.refused("oneblock.refuse.no-island");
        }
        if (island.get().isMember(player)) {
            return Reply.silent();
        }
        Optional<Island> theirs = book.ofPlayer(player);
        if (theirs.isPresent()) {
            return Reply.refused(theirs.get().owner().equals(player) ? "oneblock.join.own-island"
                    : "oneblock.join.other-island");
        }
        int most = content.get().teamSize();
        IslandBook.Changed changed = book.change(islandId, it -> it.everyone().size() >= most ? Optional.empty()
                : Optional.of(it.withMember(player)));
        return switch (changed.outcome()) {
            case DONE -> Reply.ok("oneblock.join.done", "player", island.get().ownerName());
            case ON_ANOTHER_ISLAND -> Reply.refused("oneblock.join.other-island");
            default -> Reply.refused("oneblock.join.full", "most", String.valueOf(most));
        };
    }

    /**
     * A member leaves; an owner alone on their island gives it up — archived, worlds kept, nothing deleted. An owner
     * with a team cannot leave them without an owner.
     */
    public Reply leave(UUID player) {
        Optional<Island> island = book.ofPlayer(player);
        if (island.isEmpty()) {
            return Reply.refused("oneblock.refuse.no-island");
        }
        if (!island.get().owner().equals(player)) {
            book.change(island.get().id(), it -> Optional.of(it.withoutMember(player)));
            return Reply.ok("oneblock.leave.done", "player", island.get().ownerName());
        }
        if (!island.get().members().isEmpty()) {
            return Reply.refused("oneblock.leave.has-team");
        }
        book.archive(island.get().id());
        return Reply.ok("oneblock.leave.abandoned");
    }

    public Reply kick(UUID owner, UUID member, String memberName) {
        Optional<Island> island = book.ofPlayer(owner);
        if (island.isEmpty()) {
            return Reply.refused("oneblock.refuse.no-island");
        }
        if (!island.get().owner().equals(owner)) {
            return Reply.refused("oneblock.kick.owner-only");
        }
        if (owner.equals(member)) {
            return Reply.refused("oneblock.kick.self");
        }
        if (!island.get().members().contains(member)) {
            return Reply.refused("oneblock.kick.not-member", "player", memberName);
        }
        book.change(island.get().id(), it -> Optional.of(it.withoutMember(member)));
        return Reply.ok("oneblock.kick.done", "player", memberName);
    }

    // ------------------------------------------------------------------ shards

    public Reply unlock(UUID player, IslandDimension dimension) {
        Optional<Island> island = book.ofPlayer(player);
        if (island.isEmpty()) {
            return Reply.refused("oneblock.refuse.no-island");
        }
        Content now = content.get();
        PurchaseRule.Verdict verdict = purchases.unlock(now, island.get(), player, dimension);
        if (!verdict.allowed()) {
            return refusedFor(verdict, island.get(), dimension.nameKey());
        }
        IslandBook.Changed changed = book.change(island.get().id(), it -> {
            PurchaseRule.Verdict again = purchases.unlock(now, it, player, dimension);
            return again.allowed() ? it.spending(again.price()).flatMap(paid -> paid.unlocking(dimension))
                    : Optional.empty();
        });
        return changed.done() ? Reply.ok("oneblock.unlock.done", "dimension-key", dimension.nameKey(),
                "price", String.valueOf(verdict.price()))
                : Reply.refused("oneblock.buy.too-poor", "price", String.valueOf(verdict.price()),
                "shards", String.valueOf(changed.island().map(Island::shards).orElse(0L)));
    }

    public Reply buy(UUID player, IslandDimension dimension, String upgradeId) {
        Optional<Island> island = book.ofPlayer(player);
        if (island.isEmpty()) {
            return Reply.refused("oneblock.refuse.no-island");
        }
        Content now = content.get();
        PurchaseRule.Verdict verdict = purchases.upgrade(now, island.get(), player, dimension, upgradeId);
        String name = now.upgrade(upgradeId).map(Upgrade::name).orElse(upgradeId);
        if (!verdict.allowed()) {
            return refusedFor(verdict, island.get(), dimension.nameKey(), "upgrade", name);
        }
        IslandBook.Changed changed = book.change(island.get().id(), it -> {
            PurchaseRule.Verdict again = purchases.upgrade(now, it, player, dimension, upgradeId);
            int level = it.block(dimension).map(block -> block.level(upgradeId)).orElse(0);
            return again.allowed() ? it.spending(again.price())
                    .map(paid -> paid.withBlock(dimension, block -> block.withLevel(upgradeId, level + 1)))
                    : Optional.empty();
        });
        if (!changed.done()) {
            return Reply.refused("oneblock.buy.too-poor", "price", String.valueOf(verdict.price()),
                    "shards", String.valueOf(changed.island().map(Island::shards).orElse(0L)));
        }
        int level = changed.island().flatMap(it -> it.block(dimension)).map(block -> block.level(upgradeId)).orElse(0);
        return Reply.ok("oneblock.buy.done", "upgrade", name, "level", String.valueOf(level),
                "price", String.valueOf(verdict.price()));
    }

    private static Reply refusedFor(PurchaseRule.Verdict verdict, Island island, String dimensionKey,
                                    String... more) {
        String[] values = new String[4 + more.length];
        values[0] = "shards";
        values[1] = String.valueOf(island.shards());
        values[2] = "dimension-key";
        values[3] = dimensionKey;
        System.arraycopy(more, 0, values, 4, more.length);
        return Reply.refused(verdict.refusal().orElseThrow(), values);
    }

    // ------------------------------------------------------------------ arriving

    /** Where the team arrives in this dimension, set to where the player stands. Members only, on their own island. */
    public Reply setSpawn(UUID player, String world, Spawn spawn) {
        Optional<Island> island = book.ofPlayer(player);
        if (island.isEmpty()) {
            return Reply.refused("oneblock.refuse.no-island");
        }
        Optional<Island> here = book.ofWorld(world);
        Optional<IslandDimension> dimension = Island.dimensionOf(world);
        if (here.isEmpty() || dimension.isEmpty() || !here.get().id().equals(island.get().id())) {
            return Reply.refused("oneblock.spawn.not-here");
        }
        book.change(island.get().id(), it -> Optional.of(it.withSpawn(dimension.get(), spawn)));
        return Reply.ok("oneblock.spawn.set", "dimension-key", dimension.get().nameKey());
    }
}
