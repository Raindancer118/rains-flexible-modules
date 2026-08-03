package de.raindancer.modules.claims.model;

import de.raindancer.core.world.protection.LandAction;
import java.util.EnumSet;
import java.util.UUID;

/**
 * A player who has been explicitly trusted on a claim.
 * <p>
 * Three independent sets, because the plan asks for exactly that separation:
 * <ul>
 *   <li>{@link #permissions()} — what this player may do inside the claim.</li>
 *   <li>{@link #adminPermissions()} — what this player may change about the claim.</li>
 *   <li>{@link #grantablePermissions()} — which permissions this player may hand to others
 *       (e.g. "may grant the right to open doors").</li>
 * </ul>
 */
public final class ClaimMember {

    private final UUID uuid;
    private final EnumSet<LandAction> permissions;
    private final EnumSet<ClaimAdminPermission> adminPermissions;
    private final EnumSet<LandAction> grantablePermissions;
    private long addedAt;

    public ClaimMember(UUID uuid) {
        this(uuid, EnumSet.noneOf(LandAction.class), EnumSet.noneOf(ClaimAdminPermission.class),
                EnumSet.noneOf(LandAction.class), System.currentTimeMillis());
    }

    public ClaimMember(UUID uuid, EnumSet<LandAction> permissions,
                       EnumSet<ClaimAdminPermission> adminPermissions,
                       EnumSet<LandAction> grantablePermissions, long addedAt) {
        this.uuid = uuid;
        this.permissions = permissions;
        this.adminPermissions = adminPermissions;
        this.grantablePermissions = grantablePermissions;
        this.addedAt = addedAt;
    }

    public UUID uuid() {
        return uuid;
    }

    public EnumSet<LandAction> permissions() {
        return permissions;
    }

    public EnumSet<ClaimAdminPermission> adminPermissions() {
        return adminPermissions;
    }

    public EnumSet<LandAction> grantablePermissions() {
        return grantablePermissions;
    }

    public long addedAt() {
        return addedAt;
    }

    public void addedAt(long addedAt) {
        this.addedAt = addedAt;
    }

    public boolean has(LandAction permission) {
        return permissions.contains(permission);
    }

    public boolean has(ClaimAdminPermission permission) {
        return adminPermissions.contains(permission);
    }

    public boolean isClaimAdmin() {
        return !adminPermissions.isEmpty();
    }

    /** Grants the default trust package used by {@code /claim trust} without explicit permissions. */
    public void applyDefaultTrust() {
        permissions.addAll(EnumSet.of(
                LandAction.ENTER,
                LandAction.BUILD,
                LandAction.BREAK,
                LandAction.CONTAINERS,
                LandAction.DOORS,
                LandAction.REDSTONE,
                LandAction.BEDS,
                LandAction.WORKSTATIONS,
                LandAction.ANIMALS,
                LandAction.VEHICLES,
                LandAction.ITEM_FRAMES,
                LandAction.BUCKETS,
                LandAction.ITEM_PICKUP,
                LandAction.TRADE));
    }
}
