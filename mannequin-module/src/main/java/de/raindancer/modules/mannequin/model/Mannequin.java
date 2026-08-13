package de.raindancer.modules.mannequin.model;

import org.bukkit.inventory.EquipmentSlot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A training dummy, as data — no server, no live entity, nothing that can throw. What {@link
 * de.raindancer.modules.mannequin.store.MannequinStore} reads and writes, one file per mannequin.
 *
 * <h2>Bound to its block</h2>
 * {@code world}/{@code x}/{@code y}/{@code z} are the exact block the mannequin was created on.
 * Every spawn or respawn places the live entity here again, and combined with {@code
 * Mannequin#setImmovable(true)} on the live entity — no knockback, no piston, no water, no
 * explosion — the dummy is fully static once created. That is a hard requirement, not cosmetic.
 *
 * <h2>It can die — and that is fine</h2>
 * A mannequin is <em>not</em> invulnerable. It has a real health pool, takes real damage, and can
 * be killed. What makes that safe to allow is everything downstream of a death: {@code
 * MannequinDeathListener} clears every drop and zeroes the dropped experience, and {@code
 * MannequinService#scheduleRespawn} brings an identical replacement back — same block, same
 * loadout, same skin — after {@code MannequinSettings#respawnDelaySeconds}. Nothing obtainable is
 * ever produced by killing one, and the training room never has a permanent gap in it.
 *
 * @param id                   this mannequin's own id, unique within the store
 * @param owner                who created it
 * @param world                the world it is anchored to
 * @param x                    the anchor block's x
 * @param y                    the anchor block's y — the entity stands here, not one above
 * @param z                    the anchor block's z
 * @param displayName          shown above the dummy
 * @param loadout              what is chosen for each equipment slot, as specs rather than live
 *                             stacks — see {@link ItemSpec}
 * @param skinSource           whose skin it wears, or {@code null} for the default profile
 * @param blocksWithShield     whether it actively raises a shield it is holding
 * @param emitsRedstoneSignal  whether a hit should pulse the barrel placed under it
 * @param maxHealthOverride    this mannequin's own max health, or {@code null} to use the owner's
 *                             server-wide {@code MannequinSettings#maxHealth} — see {@link
 *                             de.raindancer.modules.mannequin.util.HealthPresets} for named
 *                             starting points; any raw value works too, since the point of keeping
 *                             this a plain number rather than an enum is that a server can give a
 *                             dummy a mob's health pool without this module being changed for it
 * @param kind                 which mob this is spawned as — see {@link MannequinKind}; never
 *                             {@code null}, defaulted to {@link MannequinKind#PLAYER} for anything
 *                             built before this field existed
 * @param yaw                  which way it faces, in degrees — the owner's own yaw at the moment
 *                             of creation, so a freshly placed dummy looks the way they were
 *                             looking rather than vanilla's default (due south, yaw 0)
 */
public record Mannequin(String id, UUID owner, String world, int x, int y, int z,
                        String displayName, Map<EquipmentSlot, ItemSpec> loadout,
                        UUID skinSource, boolean blocksWithShield, boolean emitsRedstoneSignal,
                        Double maxHealthOverride, MannequinKind kind, float yaw) {

    public Mannequin {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a mannequin needs an id");
        }
        if (owner == null) {
            throw new IllegalArgumentException("a mannequin needs an owner");
        }
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("a mannequin needs a world");
        }
        displayName = displayName == null || displayName.isBlank() ? "Mannequin" : displayName;
        loadout = loadout == null ? Map.of() : Map.copyOf(loadout);
        if (maxHealthOverride != null && maxHealthOverride <= 0) {
            throw new IllegalArgumentException("a max health override has to be positive");
        }
        kind = kind == null ? MannequinKind.PLAYER : kind;
    }

    /** A freshly placed, {@link MannequinKind#PLAYER} mannequin — every call site from before kinds existed. */
    public static Mannequin freshlyPlaced(String id, UUID owner, String world, int x, int y, int z) {
        return freshlyPlaced(id, owner, world, x, y, z, MannequinKind.PLAYER, 0f);
    }

    /** The same, facing a chosen direction — every call site from before yaw was captured. */
    public static Mannequin freshlyPlaced(String id, UUID owner, String world, int x, int y, int z,
                                          MannequinKind kind) {
        return freshlyPlaced(id, owner, world, x, y, z, kind, 0f);
    }

    /** A freshly placed mannequin of a chosen kind and facing: no loadout yet, default skin, shield blocking on. */
    public static Mannequin freshlyPlaced(String id, UUID owner, String world, int x, int y, int z,
                                          MannequinKind kind, float yaw) {
        return new Mannequin(id, owner, world, x, y, z, "Mannequin", Map.of(), null, true, false,
                null, kind, yaw);
    }

    /** The barrel this mannequin's redstone pulse is written to — directly under its feet. */
    public int barrelY() {
        return y - 1;
    }

    /**
     * Its own max health if an owner has set one, otherwise a sensible default: the server-wide
     * setting for {@link MannequinKind#PLAYER}, or that kind's own vanilla-realistic health for
     * anything else ({@link MannequinKind#defaultMaxHealth()}) — a Wither dummy defaults to a
     * Wither's own 300, not the 20 a bare player has, and matches what its native boss bar expects.
     */
    public double resolvedMaxHealth(double serverDefaultForPlayer) {
        if (maxHealthOverride != null) {
            return maxHealthOverride;
        }
        return kind == MannequinKind.PLAYER ? serverDefaultForPlayer : kind.defaultMaxHealth();
    }

    public Mannequin withDisplayName(String name) {
        return new Mannequin(id, owner, world, x, y, z, name, loadout, skinSource,
                blocksWithShield, emitsRedstoneSignal, maxHealthOverride, kind, yaw);
    }

    public Mannequin withSlot(EquipmentSlot slot, ItemSpec spec) {
        Map<EquipmentSlot, ItemSpec> next = new LinkedHashMap<>(loadout);
        if (spec == null) {
            next.remove(slot);
        } else {
            next.put(slot, spec);
        }
        return new Mannequin(id, owner, world, x, y, z, displayName, next, skinSource,
                blocksWithShield, emitsRedstoneSignal, maxHealthOverride, kind, yaw);
    }

    public Mannequin withSkinSource(UUID skin) {
        return new Mannequin(id, owner, world, x, y, z, displayName, loadout, skin,
                blocksWithShield, emitsRedstoneSignal, maxHealthOverride, kind, yaw);
    }

    public Mannequin withBlocksWithShield(boolean blocks) {
        return new Mannequin(id, owner, world, x, y, z, displayName, loadout, skinSource,
                blocks, emitsRedstoneSignal, maxHealthOverride, kind, yaw);
    }

    public Mannequin withEmitsRedstoneSignal(boolean emits) {
        return new Mannequin(id, owner, world, x, y, z, displayName, loadout, skinSource,
                blocksWithShield, emits, maxHealthOverride, kind, yaw);
    }

    /** @param health {@code null} to fall back to the server-wide default again */
    public Mannequin withMaxHealthOverride(Double health) {
        return new Mannequin(id, owner, world, x, y, z, displayName, loadout, skinSource,
                blocksWithShield, emitsRedstoneSignal, health, kind, yaw);
    }

    /** Which mob this is spawned as — see {@link MannequinKind}. */
    public Mannequin withKind(MannequinKind newKind) {
        return new Mannequin(id, owner, world, x, y, z, displayName, loadout, skinSource,
                blocksWithShield, emitsRedstoneSignal, maxHealthOverride, newKind, yaw);
    }

    /** Which way it faces, in degrees. */
    public Mannequin withYaw(float newYaw) {
        return new Mannequin(id, owner, world, x, y, z, displayName, loadout, skinSource,
                blocksWithShield, emitsRedstoneSignal, maxHealthOverride, kind, newYaw);
    }

    public ItemSpec specFor(EquipmentSlot slot) {
        return loadout.get(slot);
    }
}
