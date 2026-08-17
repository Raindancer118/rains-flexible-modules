package de.raindancer.modules.claims.model;

import de.raindancer.modules.claims.rules.ClaimAreaRule;
import de.raindancer.core.world.protection.LandAction;
import de.raindancer.core.world.protection.LandAudience;
import de.raindancer.core.world.protection.LandFlag;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A claimed region. Mutable and only ever touched from region/global scheduler threads or during
 * synchronous load, so no internal locking is needed.
 */
public final class Claim {

    private final UUID id;
    private String name;
    private final UUID worldId;
    private String worldName;
    private ClaimShape shape;

    /** Equal co-owners. Never empty for a live claim. */
    // Concurrent throughout: the async saver walks these while a region thread is changing them,
    // and a ConcurrentModificationException there is a save that is lost *after* the dirty flag
    // was cleared — so the claim is never written again until somebody happens to touch it.
    // CopyOnWriteArraySet rather than newSetFromMap: owners are *ordered* — primaryOwner() is the
    // first one — and a hash-backed set would quietly reorder them, changing whose name a claim
    // reads as. There are never more than a handful, and they change almost never, which is
    // exactly the shape copy-on-write is for.
    private final Set<UUID> owners = new java.util.concurrent.CopyOnWriteArraySet<>();
    private final Map<UUID, ClaimMember> members = new ConcurrentHashMap<>();
    private final Map<UUID, ClaimBan> bans = new ConcurrentHashMap<>();

    /**
     * Only flags the owner deliberately set; everything else falls back to the server default.
     * <p>
     * One value per {@link LandAudience}, so a flag can be off for strangers and on for the owner. A flag
     * that is not {@link LandFlag#audienceAware()} always holds the same value for all three — see
     * {@link #setFlagOverride}.
     */
    private final EnumMap<LandFlag, EnumMap<LandAudience, Boolean>> flagOverrides =
            new EnumMap<>(LandFlag.class);
    private final EnumSet<LandAction> publicPermissions = EnumSet.noneOf(LandAction.class);

    /**
     * Who each audience-aware feature serves. Absent means everybody, which is what a claim that has
     * never been narrowed should do.
     */
    private final EnumMap<ClaimFeature, EnumSet<LandAudience>> featureAudiences =
            new EnumMap<>(ClaimFeature.class);

    private final ClaimTitles titles = new ClaimTitles();
    private final EntryFee entryFee = new EntryFee();
    private final ClaimBank bank = new ClaimBank();
    private final ClaimFence fence = new ClaimFence();
    private final ClaimPantry pantry = new ClaimPantry();
    private final PotionStore potionStore = new PotionStore();
    private final ClaimEquipment equipment = new ClaimEquipment();
    private final ClaimAtmosphere atmosphere = new ClaimAtmosphere();
    /** Potion effects granted to everybody inside, keyed by type so one effect cannot be added twice. */
    private final Map<org.bukkit.potion.PotionEffectType, ClaimEffect> effects =
            new ConcurrentHashMap<>();
    /** The owner's master switch for those effects; the list survives switching them off. */
    private boolean effectsEnabled = true;

    /** Made on first use; see area(). */
    private ClaimAreaRule area;

    /**
     * Owners who have excused themselves from this claim's own flags.
     *
     * <p>The case an owner actually asks for: they set no PvP, no elytra and no potions because that is how
     * they want the place, and then have to work on the build. Without this the only way is to turn each rule
     * off, do the work, and turn them all back on — and the one that stays off afterwards is the one nobody
     * notices for a month.
     *
     * <p>Per claim and per person, and it reaches no further than this claim: it is not the server-wide admin
     * bypass, which is a permission and covers every kind of protected ground. Only an owner may hold it, and
     * removing somebody as an owner takes it with them.
     *
     * <p>Not persisted deliberately — see {@link #ignoringOwnRules}. It lasts the session.
     */
    private final Set<UUID> ignoringOwnRules =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    private long createdAt = System.currentTimeMillis();
    private boolean dirty = true;

    /**
     * The item shown for this claim in every list. {@code null} falls back to the default per role.
     * <p>
     * A full stack rather than a bare {@link Material}, because the material alone throws away everything
     * that makes an item recognisable: a potion without its meta renders as a plain water bottle, and a
     * named or enchanted item loses its identity.
     */
    private ItemStack icon;

    /**
     * What the owner originally handed over for this claim, and for how much area.
     * <p>
     * Recorded so a resize can be settled strictly proportionally: paying 10 wither stars and then
     * shrinking the claim by a tenth gives exactly one star back, regardless of what the server admin has
     * changed the price to since.
     * <p>
     * This baseline is deliberately never scaled down. The price for a new size is always derived from the
     * original figures, and {@link #settledAmount} tracks what is currently invested. Deriving it from the
     * running total instead would let a player farm material: each shrink rounds the refund up in their
     * favour, so four small shrinks paid out more than one large one and growing back cost less than was
     * refunded.
     */
    private CostType paidCostType = CostType.NONE;
    private int paidAmount;
    private long paidArea;
    /** How much of the currency is invested in the claim at its current size. */
    private int settledAmount;
    private org.bukkit.inventory.ItemStack paidItem;

    public Claim(UUID id, String name, UUID worldId, String worldName, ClaimShape shape, UUID owner) {
        this.id = id;
        this.name = name;
        this.worldId = worldId;
        this.worldName = worldName;
        this.shape = shape;
        if (owner != null) {
            this.owners.add(owner);
        }
        for (LandAction permission : LandAction.values()) {
            if (permission.publicByDefault()) {
                publicPermissions.add(permission);
            }
        }
    }

    /**
     * This claim as the piece of protected ground Core enforces.
     *
     * <p>Held rather than made each time: it is asked for several times a tick by the protection listeners, and
     * it carries no state of its own beyond a reference back to here.
     */
    public ClaimAreaRule area() {
        ClaimAreaRule existing = area;
        if (existing == null) {
            existing = new ClaimAreaRule(this);
            area = existing;
        }
        return existing;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        this.name = name;
        markDirty();
    }

    public UUID worldId() {
        return worldId;
    }

    public String worldName() {
        return worldName;
    }

    public void worldName(String worldName) {
        this.worldName = worldName;
        markDirty();
    }

    public ClaimShape shape() {
        return shape;
    }

    public void shape(ClaimShape shape) {
        this.shape = shape;
        markDirty();
    }

    /**
     * Where visitors are meant to arrive — a claim's front door, set by its owner standing there and
     * pressing the button. {@code null} means unset, which is a claim's default: nothing about a
     * claim requires one.
     */
    private ClaimPoint entrance;
    private int entranceY;

    public Optional<ClaimPoint> entrance() {
        return Optional.ofNullable(entrance);
    }

    public int entranceY() {
        return entranceY;
    }

    /**
     * Sets the entrance, refusing a column outside this claim's own shape — the invariant lives here,
     * at the one place that changes it, rather than in whichever screen or command happens to call
     * this first. See {@code ClaimAreaRule}/{@code Claim.ban()} for the same rule elsewhere in this
     * class.
     *
     * @return false when the column is not inside this claim; the entrance is left unchanged
     */
    public boolean entrance(ClaimPoint point, int y) {
        if (point != null && !shape.containsColumn(point.x(), point.z())) {
            return false;
        }
        this.entrance = point;
        this.entranceY = y;
        markDirty();
        return true;
    }

    public void clearEntrance() {
        this.entrance = null;
        this.entranceY = 0;
        markDirty();
    }

    public Set<UUID> owners() {
        return Collections.unmodifiableSet(owners);
    }

    public UUID primaryOwner() {
        return owners.isEmpty() ? null : owners.iterator().next();
    }

    /**
     * Whether this owner is currently ignoring their own claim's flags.
     *
     * <p>Only ever true for an owner: the check is here rather than at the call site so that removing somebody
     * as an owner takes their exemption with them, whether or not whoever removed them remembered.
     */
    public boolean isIgnoringOwnRules(UUID who) {
        return who != null && owners.contains(who) && ignoringOwnRules.contains(who);
    }

    /**
     * Turns it on or off.
     *
     * <p>Deliberately not saved to disk. An exemption that survived a restart would be one somebody switched on
     * to hang a picture in March and is still running in June — and unlike a flag, nothing on the screen would
     * be showing them the claim behaving differently from how it reads. It lasts the session and then the claim
     * goes back to what its owner actually configured.
     *
     * @return whether it is now on
     */
    public boolean toggleIgnoringOwnRules(UUID who) {
        if (who == null || !owners.contains(who)) {
            return false;
        }
        if (ignoringOwnRules.remove(who)) {
            return false;
        }
        ignoringOwnRules.add(who);
        return true;
    }

    public boolean isOwner(UUID uuid) {
        return uuid != null && owners.contains(uuid);
    }

    public boolean addOwner(UUID uuid) {
        boolean added = owners.add(uuid);
        if (added) {
            members.remove(uuid);
            // The other order this goes wrong in: bar somebody, then hand them the claim. That would leave an
            // owner who cannot walk in, and the screen that lifts a ban is inside the claim they cannot reach.
            bans.remove(uuid);
            markDirty();
        }
        return added;
    }

    /**
     * Refuses to remove the last owner, and refuses to remove the primary one even when others remain.
     *
     * <p>An ownerless claim would be unmanageable, which covers the first case. The second is separate: since
     * {@link #primaryOwner()} is simply the first entry of an ordered set, a co-owner added later could
     * otherwise be used to strip the original owner out from under them while leaving the claim technically
     * owned — the claim would still work, but by whoever added themselves last rather than whoever made it.
     */
    public boolean removeOwner(UUID uuid) {
        if (owners.size() <= 1 || !owners.contains(uuid) || uuid.equals(primaryOwner())) {
            return false;
        }
        owners.remove(uuid);
        markDirty();
        return true;
    }

    /**
     * Hands the whole claim to somebody else, replacing every owner with exactly this one.
     *
     * <p>Deliberately not what {@link #addOwner}/{@link #removeOwner} are for. Those two protect a
     * co-owner from ever being able to strip the original owner out from under them — see
     * {@link #removeOwner}'s note — and that protection has to hold for players. An admin reassigning
     * an abandoned claim, or correcting one made in the wrong person's name, is not that: nobody is
     * being pushed out of a claim they still want, so the whole ownership list is simply replaced.
     *
     * <p>Trusted members and any ban on the new owner go with it, for the same reason {@link #addOwner}
     * already clears both: an owner who cannot walk into their own claim, or who is still listed as
     * trusted rather than owning it outright, is a claim that looks handed over and is not. Whatever
     * the previous owners had set aside for themselves — ignoring their own rules, most of all — goes
     * too; none of it means anything once they no longer own the ground it applied to.
     */
    public void transferTo(UUID newOwner) {
        if (newOwner == null) {
            return;
        }
        owners.clear();
        owners.add(newOwner);
        members.remove(newOwner);
        bans.remove(newOwner);
        ignoringOwnRules.clear();
        markDirty();
    }

    public Map<UUID, ClaimMember> members() {
        return Collections.unmodifiableMap(members);
    }

    public Optional<ClaimMember> member(UUID uuid) {
        return Optional.ofNullable(members.get(uuid));
    }

    public ClaimMember memberOrCreate(UUID uuid) {
        ClaimMember member = members.computeIfAbsent(uuid, ClaimMember::new);
        markDirty();
        return member;
    }

    public boolean removeMember(UUID uuid) {
        boolean removed = members.remove(uuid) != null;
        if (removed) {
            markDirty();
        }
        return removed;
    }

    public void putMember(ClaimMember member) {
        members.put(member.uuid(), member);
        markDirty();
    }

    public EnumSet<LandAction> publicPermissions() {
        return publicPermissions;
    }

    public boolean publicHas(LandAction permission) {
        return publicPermissions.contains(permission);
    }

    public void setPublic(LandAction permission, boolean allowed) {
        if (allowed) {
            publicPermissions.add(permission);
        } else {
            publicPermissions.remove(permission);
        }
        markDirty();
    }

    /** Every override the owner has set, keyed by flag and then by audience. Never contains empty maps. */
    public Map<LandFlag, Map<LandAudience, Boolean>> flagOverrides() {
        Map<LandFlag, Map<LandAudience, Boolean>> copy = new EnumMap<>(LandFlag.class);
        flagOverrides.forEach((flag, values) -> copy.put(flag, Collections.unmodifiableMap(values)));
        return Collections.unmodifiableMap(copy);
    }

    public Optional<Boolean> flagOverride(LandFlag flag, LandAudience audience) {
        EnumMap<LandAudience, Boolean> values = flagOverrides.get(flag);
        if (values == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(values.get(audience == null ? LandAudience.OWNER : audience));
    }

    /** True when the owner has set this flag for every audience to the same value. */
    public boolean flagIsUniform(LandFlag flag) {
        EnumMap<LandAudience, Boolean> values = flagOverrides.get(flag);
        if (values == null || values.size() < LandAudience.values().length) {
            return false;
        }
        return values.values().stream().distinct().count() == 1L;
    }

    /**
     * Sets a flag for one audience, or for all three when {@code audience} is {@code null}.
     * <p>
     * A flag that is not audience aware is always written to all three regardless of what was asked, so
     * the "one value for everybody" promise cannot be broken by a caller that forgot to check.
     *
     * @param value {@code null} clears the override and falls back to the server default
     */
    public void setFlagOverride(LandFlag flag, LandAudience audience, Boolean value) {
        if (audience == null || !flag.audienceAware()) {
            for (LandAudience each : LandAudience.values()) {
                writeFlag(flag, each, value);
            }
        } else {
            writeFlag(flag, audience, value);
        }
        markDirty();
    }

    private void writeFlag(LandFlag flag, LandAudience audience, Boolean value) {
        if (value == null) {
            EnumMap<LandAudience, Boolean> values = flagOverrides.get(flag);
            if (values != null) {
                values.remove(audience);
                if (values.isEmpty()) {
                    flagOverrides.remove(flag);
                }
            }
            return;
        }
        flagOverrides.computeIfAbsent(flag, key -> new EnumMap<>(LandAudience.class)).put(audience, value);
    }

    // ------------------------------------------------------------ who a feature serves

    /**
     * Whether this feature reaches that group. A feature nobody has narrowed serves everybody.
     * <p>
     * The owner is never actually locked out of their own perks by accident: they can take themselves
     * out of the list deliberately, which is what somebody running a pantry purely for guests wants.
     */
    public boolean featureServes(ClaimFeature feature, LandAudience audience) {
        if (!feature.audienceAware()) {
            return true;
        }
        EnumSet<LandAudience> served = featureAudiences.get(feature);
        return served == null || served.contains(audience);
    }

    /** The groups a feature serves, always a full set when the owner has never narrowed it. */
    public EnumSet<LandAudience> featureAudiences(ClaimFeature feature) {
        EnumSet<LandAudience> served = featureAudiences.get(feature);
        return served == null ? EnumSet.allOf(LandAudience.class) : EnumSet.copyOf(served);
    }

    public void setFeatureAudience(ClaimFeature feature, LandAudience audience, boolean served) {
        if (!feature.audienceAware()) {
            return;
        }
        EnumSet<LandAudience> current = featureAudiences(feature);
        if (served) {
            current.add(audience);
        } else {
            current.remove(audience);
        }
        if (current.size() == LandAudience.values().length) {
            featureAudiences.remove(feature);
        } else {
            featureAudiences.put(feature, current);
        }
        markDirty();
    }

    /** Used by the loader; an empty or full set is stored as "everybody". */
    public void restoreFeatureAudiences(Map<ClaimFeature, EnumSet<LandAudience>> loaded) {
        featureAudiences.clear();
        loaded.forEach((feature, served) -> {
            if (feature.audienceAware() && served.size() < LandAudience.values().length) {
                featureAudiences.put(feature, EnumSet.copyOf(served));
            }
        });
    }

    /** Only the narrowed ones, so the claim file stays quiet about defaults. */
    public Map<ClaimFeature, EnumSet<LandAudience>> narrowedFeatureAudiences() {
        return Collections.unmodifiableMap(featureAudiences);
    }

    public Map<UUID, ClaimBan> bans() {
        return Collections.unmodifiableMap(bans);
    }

    /**
     * Bars somebody, unless they own the place.
     *
     * <p>The rule used to live at each call site — the command checked and refused, the screen checked and
     * refused. When the screen moved to a chooser that never offers an owner, the check went with it, on the
     * argument that an impossible input needs no guard. True today, and the wrong place to keep an invariant:
     * an exclusion list is recomputed on every open, is easy to get subtly wrong, and the next screen that
     * wants to bar somebody starts from nothing. This is the one line every path goes through.
     *
     * <p>Worth guarding because of how it fails. A barred owner is refused entry to their own claim, and the
     * screen that lifts a ban is inside it — so they cannot undo it themselves, and the claim has to go to an
     * admin to be rescued.
     *
     * @return whether they were barred; false means they own it
     */
    public boolean ban(ClaimBan ban) {
        if (ban == null || isOwner(ban.uuid())) {
            return false;
        }
        bans.put(ban.uuid(), ban);
        markDirty();
        return true;
    }

    public boolean unban(UUID uuid) {
        boolean removed = bans.remove(uuid) != null;
        if (removed) {
            markDirty();
        }
        return removed;
    }

    /** Returns the active ban for a player, pruning it when a timeout has run out. */
    public Optional<ClaimBan> activeBan(UUID uuid) {
        ClaimBan ban = bans.get(uuid);
        if (ban == null) {
            return Optional.empty();
        }
        if (ban.expired()) {
            bans.remove(uuid);
            markDirty();
            return Optional.empty();
        }
        return Optional.of(ban);
    }

    public void restoreBans(Map<UUID, ClaimBan> loaded) {
        bans.clear();
        bans.putAll(loaded);
    }

    public ClaimTitles titles() {
        return titles;
    }

    public EntryFee entryFee() {
        return entryFee;
    }

    public ClaimBank bank() {
        return bank;
    }

    public ClaimFence fence() {
        return fence;
    }

    public ClaimPantry pantry() {
        return pantry;
    }

    public PotionStore potionStore() {
        return potionStore;
    }

    public ClaimEquipment equipment() {
        return equipment;
    }

    public ClaimAtmosphere atmosphere() {
        return atmosphere;
    }

    public Map<org.bukkit.potion.PotionEffectType, ClaimEffect> effects() {
        return Collections.unmodifiableMap(effects);
    }

    /**
     * Whether the granted effects actually run, so an owner can pause them without losing the list.
     * <p>
     * On by default: a claim that has effects configured is expected to grant them, and claims written
     * before this switch existed have no stored value.
     */
    public boolean effectsEnabled() {
        return effectsEnabled;
    }

    public void effectsEnabled(boolean effectsEnabled) {
        this.effectsEnabled = effectsEnabled;
        markDirty();
    }

    public void addEffect(ClaimEffect effect) {
        effects.put(effect.type(), effect);
        markDirty();
    }

    public boolean removeEffect(org.bukkit.potion.PotionEffectType type) {
        boolean removed = effects.remove(type) != null;
        if (removed) {
            markDirty();
        }
        return removed;
    }

    public Optional<ClaimEffect> effect(org.bukkit.potion.PotionEffectType type) {
        return Optional.ofNullable(effects.get(type));
    }

    public void clearEffects() {
        effects.clear();
        markDirty();
    }

    public void restoreEffects(Map<org.bukkit.potion.PotionEffectType, ClaimEffect> loaded) {
        effects.clear();
        effects.putAll(loaded);
    }

    /** The configured icon, or {@code null} when the claim uses the default. */
    public ItemStack icon() {
        return icon == null ? null : icon.clone();
    }

    /** Accepts any item, keeping its meta; {@code null} restores the default. */
    public void icon(ItemStack icon) {
        if (icon == null || icon.getType().isAir()) {
            this.icon = null;
        } else {
            ItemStack copy = icon.clone();
            copy.setAmount(1);
            this.icon = copy;
        }
        markDirty();
    }

    /** The icon to render, falling back to grass for owned claims and dirt for trusted ones. */
    public ItemStack iconOr(boolean owner) {
        if (icon != null) {
            return icon.clone();
        }
        return new ItemStack(owner ? Material.GRASS_BLOCK : Material.DIRT);
    }

    /** Just the material of the icon, for the few places that need a plain block type. */
    public Material iconMaterial(boolean owner) {
        return iconOr(owner).getType();
    }

    /**
     * Replaces only the vertical range, keeping the footprint.
     * <p>
     * Separate from {@link #shape(ClaimShape)} because changing the height must not go through a fresh
     * selection — an owner who just wants their claim to reach deeper should not have to redraw it.
     */
    public void verticalRange(int minY, int maxY) {
        shape(shape.withVerticalRange(minY, maxY));
    }

    public CostType paidCostType() {
        return paidCostType;
    }

    public int paidAmount() {
        return paidAmount;
    }

    public long paidArea() {
        return paidArea;
    }

    public int settledAmount() {
        return settledAmount;
    }

    public org.bukkit.inventory.ItemStack paidItem() {
        return paidItem == null ? null : paidItem.clone();
    }

    /** Records a payment. {@code area} is the footprint the payment corresponds to. */
    public void recordPayment(CostType type, int amount, long area, org.bukkit.inventory.ItemStack item) {
        this.paidCostType = type == null ? CostType.NONE : type;
        this.paidAmount = Math.max(0, amount);
        this.paidArea = Math.max(1L, area);
        this.settledAmount = this.paidAmount;
        this.paidItem = item == null ? null : item.clone();
        markDirty();
    }

    /** Restores a loaded payment, keeping the baseline and the invested amount apart. */
    public void restorePayment(CostType type, int baseAmount, long baseArea, int settled,
                               org.bukkit.inventory.ItemStack item) {
        recordPayment(type, baseAmount, baseArea, item);
        this.settledAmount = Math.max(0, settled);
    }

    /**
     * The price this claim's current footprint corresponds to, derived from the original payment.
     * <p>
     * Always computed from the untouched baseline so a sequence of resizes settles to the same total as a
     * single one.
     */
    public int targetAmountFor(long area) {
        if (paidArea <= 0) {
            return 0;
        }
        return (int) Math.floor(paidAmount * (double) Math.max(0L, area) / paidArea);
    }

    /** Records how much is invested after a resize was settled. */
    public void settledAmount(int amount) {
        this.settledAmount = Math.max(0, amount);
        markDirty();
    }

    public boolean hasRecordedPayment() {
        return paidCostType != CostType.NONE && paidAmount > 0 && paidArea > 0;
    }

    public long createdAt() {
        return createdAt;
    }

    public void createdAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public boolean dirty() {
        return dirty;
    }

    public void markDirty() {
        this.dirty = true;
    }

    public void clearDirty() {
        this.dirty = false;
    }

    /** Everybody with a stake in the claim — owners and trusted members. */
    public Set<UUID> allInvolved() {
        Set<UUID> involved = new LinkedHashSet<>(owners);
        involved.addAll(members.keySet());
        return involved;
    }

    @Override
    public String toString() {
        return "Claim[" + name + " (" + id + ") in " + worldName + " " + shape + "]";
    }
}
