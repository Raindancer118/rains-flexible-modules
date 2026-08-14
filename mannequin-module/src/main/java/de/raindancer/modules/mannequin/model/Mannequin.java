package de.raindancer.modules.mannequin.model;

import org.bukkit.inventory.EquipmentSlot;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
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
 * @param trusted              everybody besides the owner who may open and edit this mannequin —
 *                             the same "trust somebody, without making them an owner" shape
 *                             {@code claims-module}'s own members list already follows. Never
 *                             {@code null}, and never contains the owner: trust is meaningless for
 *                             somebody who already has every right there is.
 * @param claimId              the claim this mannequin belongs to, or {@code null} for one that
 *                             stands on its own — see {@code de.raindancer.modules.mannequin.claims}.
 *                             Never resolved to a live {@code Claim} in this class: the model does
 *                             not reach for a claims plugin that might not be installed, so this is
 *                             nothing more than an id somebody else knows what to do with.
 */
public record Mannequin(String id, UUID owner, String world, int x, int y, int z,
                        String displayName, Map<EquipmentSlot, ItemSpec> loadout,
                        UUID skinSource, boolean blocksWithShield, boolean emitsRedstoneSignal,
                        Double maxHealthOverride, MannequinKind kind, float yaw, Set<UUID> trusted,
                        UUID claimId) {

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
        trusted = trusted == null ? Set.of() : Set.copyOf(trusted);
        if (trusted.contains(owner)) {
            trusted = new LinkedHashSet<>(trusted);
            trusted.remove(owner);
            trusted = Set.copyOf(trusted);
        }
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
                null, kind, yaw, Set.of(), null);
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
                blocksWithShield, emitsRedstoneSignal, maxHealthOverride, kind, yaw, trusted, claimId);
    }

    public Mannequin withSlot(EquipmentSlot slot, ItemSpec spec) {
        Map<EquipmentSlot, ItemSpec> next = new LinkedHashMap<>(loadout);
        if (spec == null) {
            next.remove(slot);
        } else {
            next.put(slot, spec);
        }
        return new Mannequin(id, owner, world, x, y, z, displayName, next, skinSource,
                blocksWithShield, emitsRedstoneSignal, maxHealthOverride, kind, yaw, trusted, claimId);
    }

    public Mannequin withSkinSource(UUID skin) {
        return new Mannequin(id, owner, world, x, y, z, displayName, loadout, skin,
                blocksWithShield, emitsRedstoneSignal, maxHealthOverride, kind, yaw, trusted, claimId);
    }

    public Mannequin withBlocksWithShield(boolean blocks) {
        return new Mannequin(id, owner, world, x, y, z, displayName, loadout, skinSource,
                blocks, emitsRedstoneSignal, maxHealthOverride, kind, yaw, trusted, claimId);
    }

    public Mannequin withEmitsRedstoneSignal(boolean emits) {
        return new Mannequin(id, owner, world, x, y, z, displayName, loadout, skinSource,
                blocksWithShield, emits, maxHealthOverride, kind, yaw, trusted, claimId);
    }

    /** @param health {@code null} to fall back to the server-wide default again */
    public Mannequin withMaxHealthOverride(Double health) {
        return new Mannequin(id, owner, world, x, y, z, displayName, loadout, skinSource,
                blocksWithShield, emitsRedstoneSignal, health, kind, yaw, trusted, claimId);
    }

    /** Which mob this is spawned as — see {@link MannequinKind}. */
    public Mannequin withKind(MannequinKind newKind) {
        return new Mannequin(id, owner, world, x, y, z, displayName, loadout, skinSource,
                blocksWithShield, emitsRedstoneSignal, maxHealthOverride, newKind, yaw, trusted, claimId);
    }

    /** Which way it faces, in degrees. */
    public Mannequin withYaw(float newYaw) {
        return new Mannequin(id, owner, world, x, y, z, displayName, loadout, skinSource,
                blocksWithShield, emitsRedstoneSignal, maxHealthOverride, kind, newYaw, trusted, claimId);
    }

    /** Trusts one more person with this mannequin — a no-op if they already own it or are trusted. */
    public Mannequin withTrusted(UUID player) {
        if (player == null || player.equals(owner) || trusted.contains(player)) {
            return this;
        }
        Set<UUID> next = new LinkedHashSet<>(trusted);
        next.add(player);
        return new Mannequin(id, owner, world, x, y, z, displayName, loadout, skinSource,
                blocksWithShield, emitsRedstoneSignal, maxHealthOverride, kind, yaw, next, claimId);
    }

    /** Withdraws trust from somebody — a no-op if they were never trusted. */
    public Mannequin withoutTrusted(UUID player) {
        if (player == null || !trusted.contains(player)) {
            return this;
        }
        Set<UUID> next = new LinkedHashSet<>(trusted);
        next.remove(player);
        return new Mannequin(id, owner, world, x, y, z, displayName, loadout, skinSource,
                blocksWithShield, emitsRedstoneSignal, maxHealthOverride, kind, yaw, next, claimId);
    }

    /** Whether this player owns the mannequin or has been trusted with it. */
    public boolean mayManage(UUID player) {
        return player != null && (owner.equals(player) || trusted.contains(player));
    }

    /**
     * Which claim this mannequin belongs to. {@code null} takes it off any claim it was on.
     *
     * <p>Deliberately not validated against anything here — the model does not reach for a claims
     * plugin, installed or not, so it cannot tell a live claim id from a stale one. That question
     * belongs to whoever is actually asking, which is {@code de.raindancer.modules.mannequin.claims}.
     */
    public Mannequin withClaimId(UUID newClaimId) {
        return new Mannequin(id, owner, world, x, y, z, displayName, loadout, skinSource,
                blocksWithShield, emitsRedstoneSignal, maxHealthOverride, kind, yaw, trusted, newClaimId);
    }

    public ItemSpec specFor(EquipmentSlot slot) {
        return loadout.get(slot);
    }
}
