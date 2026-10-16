package de.raindancer.modules.oneblock.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * One island: its team, the money it plays with, its shards, and a one block per unlocked dimension. Immutable — every
 * change is a new island, so the book can swap one in atomically and a reader never sees half a change.
 *
 * <p>The rules that must hold whatever path a change takes are kept here, at the change: the owner is always on the
 * team, the economy is chosen once, shards are never spent into debt.
 *
 * @param members    the team besides the owner, in the order they joined
 * @param blocks     one per unlocked dimension
 * @param spawns     where the team arrives, per dimension; absent means on top of the block
 * @param autoUnload whether the world is unloaded once nobody is on it
 */
public record Island(UUID id, UUID owner, String ownerName, EconomyMode mode, long created, long shards,
                     List<UUID> members, Map<IslandDimension, IslandBlock> blocks, Map<IslandDimension, Spawn> spawns,
                     boolean autoUnload, Optional<Hold> hold) {

    public static final String WORLD_PREFIX = "island_";
    private static final int ID_LENGTH = 16;

    public Island {
        members = List.copyOf(new LinkedHashSet<>(members.stream().filter(member -> !member.equals(owner)).toList()));
        blocks = immutable(blocks);
        spawns = immutable(spawns);
        ownerName = ownerName == null ? "" : ownerName;
        shards = Math.max(0, shards);
        hold = hold == null ? Optional.empty() : hold;
    }

    private static <V> Map<IslandDimension, V> immutable(Map<IslandDimension, V> map) {
        return Collections.unmodifiableMap(editable(map));
    }

    /** Not {@code new EnumMap<>(map)}: that throws for an empty map that is not an EnumMap already. */
    private static <V> EnumMap<IslandDimension, V> editable(Map<IslandDimension, V> map) {
        EnumMap<IslandDimension, V> copy = new EnumMap<>(IslandDimension.class);
        copy.putAll(map);
        return copy;
    }

    public static Island create(UUID id, UUID owner, String ownerName, EconomyMode mode, long now) {
        return new Island(id, owner, ownerName, mode, now, 0, List.of(),
                Map.of(IslandDimension.OVERWORLD, IslandBlock.fresh(IslandDimension.OVERWORLD.start())), Map.of(), true, Optional.empty());
    }

    // ------------------------------------------------------------------ worlds

    /** The part of a world name that names the island: the first 16 hex digits of its id. */
    public String idPart() {
        return id.toString().replace("-", "").substring(0, ID_LENGTH);
    }

    public String worldName(IslandDimension dimension) {
        return WORLD_PREFIX + idPart() + dimension.worldSuffix();
    }

    public static boolean isIslandWorld(String world) {
        return idPartOf(world).isPresent();
    }

    public static Optional<String> idPartOf(String world) {
        if (world == null || !world.startsWith(WORLD_PREFIX) || world.length() < WORLD_PREFIX.length() + ID_LENGTH) {
            return Optional.empty();
        }
        String part = world.substring(WORLD_PREFIX.length(), WORLD_PREFIX.length() + ID_LENGTH);
        return part.chars().allMatch(c -> Character.digit(c, 16) >= 0) ? Optional.of(part) : Optional.empty();
    }

    public static Optional<IslandDimension> dimensionOf(String world) {
        if (idPartOf(world).isEmpty()) {
            return Optional.empty();
        }
        String suffix = world.substring(WORLD_PREFIX.length() + ID_LENGTH);
        for (IslandDimension dimension : IslandDimension.values()) {
            if (dimension.worldSuffix().equals(suffix)) {
                return Optional.of(dimension);
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ the team

    public boolean isMember(UUID player) {
        return owner.equals(player) || members.contains(player);
    }

    /** Owner first, then the team in joining order. */
    public List<UUID> everyone() {
        List<UUID> all = new ArrayList<>(members.size() + 1);
        all.add(owner);
        all.addAll(members);
        return all;
    }

    public Island withMember(UUID player) {
        if (isMember(player)) {
            return this;
        }
        List<UUID> more = new ArrayList<>(members);
        more.add(player);
        return new Island(id, owner, ownerName, mode, created, shards, more, blocks, spawns, autoUnload, hold);
    }

    /** The owner is never taken off: an island without an owner is nobody's to decide about. */
    public Island withoutMember(UUID player) {
        if (owner.equals(player) || !members.contains(player)) {
            return this;
        }
        List<UUID> fewer = new ArrayList<>(members);
        fewer.remove(player);
        return new Island(id, owner, ownerName, mode, created, shards, fewer, blocks, spawns, autoUnload, hold);
    }

    public Island withOwnerName(String name) {
        return new Island(id, owner, name, mode, created, shards, members, blocks, spawns, autoUnload, hold);
    }

    // ------------------------------------------------------------------ money and shards

    /** The economy, once: empty when it was chosen already or the choice is no choice. */
    public Optional<Island> decide(EconomyMode chosen) {
        if (mode.decided() || chosen == null || !chosen.decided()) {
            return Optional.empty();
        }
        return Optional.of(new Island(id, owner, ownerName, chosen, created, shards, members, blocks, spawns,
                autoUnload, hold));
    }

    public Island withShardsAdded(long amount) {
        return new Island(id, owner, ownerName, mode, created, Math.max(0, shards + amount), members, blocks, spawns,
                autoUnload, hold);
    }

    /** Empty when the island cannot afford it. */
    public Optional<Island> spending(long amount) {
        if (amount < 0 || shards < amount) {
            return Optional.empty();
        }
        return Optional.of(withShardsAdded(-amount));
    }

    // ------------------------------------------------------------------ blocks

    public boolean unlocked(IslandDimension dimension) {
        return blocks.containsKey(dimension);
    }

    public Optional<IslandBlock> block(IslandDimension dimension) {
        return Optional.ofNullable(blocks.get(dimension));
    }

    /** Empty when it was unlocked already. */
    public Optional<Island> unlocking(IslandDimension dimension) {
        return unlocking(dimension, dimension.start());
    }

    public Optional<Island> unlocking(IslandDimension dimension, BlockSpot spot) {
        if (unlocked(dimension)) {
            return Optional.empty();
        }
        Map<IslandDimension, IslandBlock> more = editable(blocks);
        more.put(dimension, IslandBlock.fresh(spot));
        return Optional.of(new Island(id, owner, ownerName, mode, created, shards, members, more, spawns, autoUnload,
                hold));
    }

    /** Changes one dimension's block; a dimension not unlocked is left as it is. */
    public Island withBlock(IslandDimension dimension, UnaryOperator<IslandBlock> change) {
        IslandBlock block = blocks.get(dimension);
        if (block == null) {
            return this;
        }
        Map<IslandDimension, IslandBlock> changed = editable(blocks);
        changed.put(dimension, change.apply(block));
        return new Island(id, owner, ownerName, mode, created, shards, members, changed, spawns, autoUnload, hold);
    }

    public Island broken(IslandDimension dimension) {
        return withBlock(dimension, IslandBlock::brokenOnceMore);
    }

    public long totalBroken() {
        return blocks.values().stream().mapToLong(IslandBlock::broken).sum();
    }

    public Set<IslandDimension> unlockedDimensions() {
        return blocks.keySet();
    }

    // ------------------------------------------------------------------ arriving

    public Spawn spawn(IslandDimension dimension) {
        Spawn set = spawns.get(dimension);
        if (set != null) {
            return set;
        }
        return Spawn.above(block(dimension).map(IslandBlock::spot).orElse(dimension.start()));
    }

    public Island withSpawn(IslandDimension dimension, Spawn spawn) {
        Map<IslandDimension, Spawn> changed = editable(spawns);
        if (spawn == null) {
            changed.remove(dimension);
        } else {
            changed.put(dimension, spawn);
        }
        return new Island(id, owner, ownerName, mode, created, shards, members, blocks, changed, autoUnload, hold);
    }

    // ------------------------------------------------------------------ admins

    public Island withHold(Optional<Hold> held) {
        return new Island(id, owner, ownerName, mode, created, shards, members, blocks, spawns, autoUnload, held);
    }

    public Island withAutoUnload(boolean on) {
        return new Island(id, owner, ownerName, mode, created, shards, members, blocks, spawns, on, hold);
    }
}
