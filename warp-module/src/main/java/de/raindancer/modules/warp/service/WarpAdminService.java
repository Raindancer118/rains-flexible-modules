package de.raindancer.modules.warp.service;

import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.warp.model.Warp;
import de.raindancer.modules.warp.WarpSettings;
import de.raindancer.modules.warp.model.WarpAccess;
import de.raindancer.modules.warp.rules.WarpAccessRule;
import de.raindancer.modules.warp.rules.WarpFeeRule;
import de.raindancer.modules.warp.rules.WarpNameRule;
import de.raindancer.modules.warp.store.WarpCatalogue;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Optional;

/**
 * Making, moving, retagging and deleting warps.
 *
 * <h2>Why every entrance comes through here</h2>
 * Because there are two of them — the commands and the admin menu — and an invariant guarded at one
 * is not guarded. "May this person change warps", "is this a name a warp can have" and "is the
 * server already at its ceiling" are asked here, once, so the menu and the command cannot come to
 * disagree about any of them.
 *
 * <p>Everything it decides it asks a rule for. Everything it changes goes through the catalogue,
 * which writes straight to disk — see there for why an access change that is only in memory is a
 * hole nobody finds until somebody walks into the staff room.
 */
public final class WarpAdminService implements IWarpService {

    /** The economy source of making a warp. */
    public static final String SOURCE = "warp.create";

    private final WarpCatalogue catalogue;
    private final WarpAccessRule access;
    private final Messages messages;

    private final WarpFeeRule fees = new WarpFeeRule();
    /** Puts new warps on rent; null for a host that does not charge any. */
    private final WarpRentService rent;

    private volatile WarpSettings settings;
    private volatile WarpNameRule names;

    public WarpAdminService(WarpCatalogue catalogue, WarpAccessRule access, Messages messages,
                            WarpSettings settings) {
        this(catalogue, access, messages, settings, null);
    }

    public WarpAdminService(WarpCatalogue catalogue, WarpAccessRule access, Messages messages,
                            WarpSettings settings, WarpRentService rent) {
        this.catalogue = catalogue;
        this.access = access;
        this.messages = messages;
        this.rent = rent;
        settings(settings);
    }

    @Override
    public void settings(WarpSettings fresh) {
        this.settings = fresh;
        // Rebuilt rather than told, because the name limit is the only thing the rule holds and a
        // rule you can change is one two callers can see differently.
        this.names = new WarpNameRule(fresh.nameLimit());
    }

    /** What making a warp would cost this player now: zero for staff and when it is free. */
    public Money createPriceFor(Player maker) {
        return fees.bypasses(maker::hasPermission) ? Money.ZERO
                : Fees.quote(SOURCE, Fees.amount(settings.createPrice()));
    }

    /** The name rule as it is now, for a screen that wants to refuse before asking. */
    public WarpNameRule names() {
        return names;
    }

    // ------------------------------------------------------------------------ making one

    /**
     * Makes a warp where this player is standing.
     *
     * @return the warp, or empty when something refused it — which has already been said
     */
    public Optional<Warp> create(Player maker, String name) {
        return create(maker, name, false);
    }

    /**
     * The same, saying whether a warp token pays for it: a token lets somebody without the create node
     * set one, and is a warp on top of their limit. The token itself is taken by whoever holds it, and
     * only once this has answered with a warp.
     */
    public Optional<Warp> create(Player maker, String name, boolean withAToken) {
        if (!access.mayCreate(maker::hasPermission, withAToken)) {
            messages.send(maker, "warps.cannot-make");
            return Optional.empty();
        }
        WarpNameRule.Verdict verdict = names.check(name);
        if (!verdict.isFine()) {
            messages.send(maker, verdict.messageKey(),
                    "name", String.valueOf(name), "limit", names.longestName());
            return Optional.empty();
        }
        Optional<Warp> existing = catalogue.byName(name);
        if (existing.isPresent() && !access.mayReplace(maker::hasPermission, maker.getUniqueId(),
                existing.get().owner().orElse(null))) {
            messages.send(maker, "warps.name-taken", "name", existing.get().name());
            return Optional.empty();
        }
        // Only when it would be a new one: replacing an existing warp is how a badly placed one is
        // corrected, and refusing that at the ceiling would mean a full server could never fix one.
        boolean replacing = existing.isPresent();
        if (!replacing && !names.isRoomFor(catalogue.count(), settings.warpLimit())) {
            messages.send(maker, "warps.too-many", "limit", settings.warpLimit());
            return Optional.empty();
        }
        if (!replacing && !access.hasRoomForOwn(catalogue.ownedBy(maker.getUniqueId()).size(),
                settings.ownWarpLimit(), maker::hasPermission, withAToken)) {
            messages.send(maker, "warps.own-limit", "limit", settings.ownWarpLimit());
            return Optional.empty();
        }

        // Charged before it is made, and never for replacing one: that is how a badly placed warp is
        // corrected. A token is the payment for its warp, and staff skip every warp fee.
        Money taken = Money.ZERO;
        if (!replacing && !withAToken && !fees.bypasses(maker::hasPermission)) {
            Money written = Fees.amount(settings.createPrice());
            EconomyResult paid = Fees.charge(maker.getUniqueId(), written, "Making warp " + name, SOURCE);
            if (!paid.succeeded()) {
                switch (paid.outcome()) {
                    case NOT_ENOUGH -> messages.send(maker, "warps.create.cannot-afford",
                            "price", Fees.format(paid.amount()));
                    case UNAVAILABLE -> messages.send(maker, "warps.create.no-economy");
                    default -> messages.send(maker, "warps.create.refused");
                }
                return Optional.empty();
            }
            taken = paid.amount();
        }
        final Money charged = taken;

        // Replacing keeps whose it is, who it is for and who was let in: a moved-by-recreating warp must not
        // quietly open a private one to the whole server.
        WarpAccess keptAccess = existing.map(catalogue::accessOf).orElse(null);
        java.util.Set<java.util.UUID> keptMembers = existing.map(Warp::members).orElse(java.util.Set.of());
        java.util.UUID keptOwner = existing.flatMap(Warp::owner).orElse(null);
        // The money side goes with it too: re-making a warp must not wipe its fee or hand back its rent.
        Money keptFee = existing.map(Warp::visitFee).orElse(Money.ZERO);
        java.util.OptionalLong keptRent = existing.map(Warp::rentPaidUntil).orElse(java.util.OptionalLong.empty());
        boolean keptClosed = existing.map(Warp::isClosedForRent).orElse(false);
        Optional<Warp> made = catalogue.create(name, maker.getLocation(), maker.getUniqueId());
        if (made.isEmpty() && charged.isPositive()) {
            Fees.refund(maker.getUniqueId(), charged, "Warp " + name + " was not made", SOURCE);
        }
        made.ifPresentOrElse(
                warp -> {
                    if (keptAccess != null) {
                        catalogue.setAccess(warp.name(), keptAccess);
                    }
                    keptMembers.forEach(member -> catalogue.addMember(warp.name(), member));
                    if (keptOwner != null) {
                        catalogue.setOwner(warp.name(), keptOwner);
                    }
                    if (keptFee.isPositive()) {
                        catalogue.setVisitFee(warp.name(), keptFee);
                    }
                    if (keptRent.isPresent()) {
                        catalogue.setRent(warp.name(), keptRent.getAsLong(), keptClosed);
                    } else if (!replacing && rent != null) {
                        rent.enroll(warp, fees.bypasses(maker::hasPermission));
                    }
                    messages.send(maker, replacing ? "warps.replaced" : "warps.created", "name", warp.name());
                    if (charged.isPositive()) {
                        messages.send(maker, "warps.create.paid", "price", Fees.format(charged));
                    }
                },
                // No <name> given: the line is "a warp needs a name", so there is no name to put in
                // it. A value supplied to a message with nowhere to put it is usually the same typo
                // seen from the other side, which is why MessagesTest checks both directions.
                () -> messages.send(maker, "warps.name.empty"));
        return made.flatMap(warp -> catalogue.byName(warp.name()));
    }

    /**
     * What visiting this warp costs, as its owner writes it: an amount, or {@code off}.
     *
     * <p>Read against the server's cap every time it is used, so lowering the cap needs no rewriting
     * of the warps.
     */
    public boolean setVisitFee(CommandSender changer, String name, String written) {
        Optional<Warp> warp = changeable(changer, name);
        if (warp.isEmpty()) {
            return false;
        }
        Money cap = Fees.amount(settings.mostVisitFee());
        if (!cap.isPositive()) {
            messages.send(changer, "warps.fee.disabled");
            return false;
        }
        String typed = written == null ? "off" : written.strip();
        if (typed.equalsIgnoreCase("off") || typed.equalsIgnoreCase("none") || typed.equals("0")) {
            catalogue.setVisitFee(warp.get().name(), Money.ZERO);
            messages.send(changer, "warps.fee.cleared", "name", warp.get().label());
            return true;
        }
        Money fee = Fees.amount(typed);
        if (!fee.isPositive()) {
            messages.send(changer, "warps.fee.bad-amount", "amount", typed);
            return false;
        }
        if (fee.isMoreThan(cap)) {
            messages.send(changer, "warps.fee.too-high", "max", Fees.format(cap));
            return false;
        }
        catalogue.setVisitFee(warp.get().name(), fee);
        messages.send(changer, "warps.fee.set", "name", warp.get().label(), "price", Fees.format(fee),
                "cut", settings.visitCutPercent());
        return true;
    }

    /** Moves an existing warp to where this player is standing, keeping everything about it. */
    public boolean move(Player mover, String name) {
        if (changeable(mover, name).isEmpty()) {
            return false;
        }
        if (!catalogue.move(name, mover.getLocation())) {
            messages.send(mover, "warps.unknown", "name", String.valueOf(name));
            return false;
        }
        messages.send(mover, "warps.moved", "name", name);
        return true;
    }

    public boolean delete(CommandSender remover, String name) {
        if (changeable(remover, name).isEmpty()) {
            return false;
        }
        if (!catalogue.delete(name)) {
            messages.send(remover, "warps.unknown", "name", String.valueOf(name));
            return false;
        }
        messages.send(remover, "warps.deleted", "name", name);
        return true;
    }

    // ------------------------------------------------------------------------ changing one

    /** Who a warp is for. */
    public boolean setAccess(CommandSender changer, String name, WarpAccess wanted) {
        if (changeable(changer, name).isEmpty()) {
            return false;
        }
        if (!access.mayChooseAccess(wanted, changer::hasPermission)) {
            messages.send(changer, "warps.access-not-yours");
            return false;
        }
        if (!catalogue.setAccess(name, wanted)) {
            messages.send(changer, "warps.unknown", "name", String.valueOf(name));
            return false;
        }
        messages.send(changer, "warps.access-set", "name", name, "access", wanted.describe());
        return true;
    }

    /** What it is filed under; null takes it out of every category. */
    public boolean setCategory(CommandSender changer, String name, String category) {
        if (!mayManage(changer)) {
            return false;
        }
        if (!catalogue.setCategory(name, category)) {
            messages.send(changer, "warps.unknown", "name", String.valueOf(name));
            return false;
        }
        // Two calls rather than one with a ternary key: the two lines ask for different things —
        // "out of every category" has no category to name — and one call passing both would be
        // handing a message a value it never uses.
        if (category == null) {
            messages.send(changer, "warps.uncategorised", "name", name);
        } else {
            messages.send(changer, "warps.categorised", "name", name, "category", category);
        }
        return true;
    }

    /** What a menu calls it; null puts it back to being called by its name. */
    public boolean setLabel(CommandSender changer, String name, String label) {
        if (changeable(changer, name).isEmpty()) {
            return false;
        }
        if (!catalogue.setLabel(name, label)) {
            messages.send(changer, "warps.unknown", "name", String.valueOf(name));
            return false;
        }
        if (label == null) {
            messages.send(changer, "warps.unlabelled", "name", name);
        } else {
            messages.send(changer, "warps.labelled", "name", name, "label", label);
        }
        return true;
    }

    public boolean setIcon(CommandSender changer, String name, Material icon) {
        if (changeable(changer, name).isEmpty()) {
            return false;
        }
        if (!catalogue.setIcon(name, icon)) {
            messages.send(changer, "warps.unknown", "name", String.valueOf(name));
            return false;
        }
        messages.send(changer, "warps.icon-set", "name", name, "icon", icon.name());
        return true;
    }

    // ------------------------------------------------------------------------ owners

    /** Hands a warp to somebody else. Staff only: it decides about warps that are not theirs. */
    public boolean giveTo(CommandSender giver, String name, java.util.UUID owner, String ownerName) {
        if (!access.mayGive(giver::hasPermission)) {
            messages.send(giver, "warps.not-yours");
            return false;
        }
        if (owner == null || !catalogue.setOwner(name, owner)) {
            messages.send(giver, "warps.unknown", "name", String.valueOf(name));
            return false;
        }
        messages.send(giver, "warps.given", "name", name, "player", String.valueOf(ownerName));
        return true;
    }

    /** Lets somebody into a private warp. */
    public boolean addMember(CommandSender changer, String name, java.util.UUID member, String memberName) {
        if (changeable(changer, name).isEmpty() || member == null) {
            return false;
        }
        catalogue.addMember(name, member);
        messages.send(changer, "warps.member-added", "name", name, "player", String.valueOf(memberName));
        return true;
    }

    /** Takes somebody off a private warp's list. */
    public boolean removeMember(CommandSender changer, String name, java.util.UUID member, String memberName) {
        if (changeable(changer, name).isEmpty() || member == null) {
            return false;
        }
        catalogue.removeMember(name, member);
        messages.send(changer, "warps.member-removed", "name", name, "player", String.valueOf(memberName));
        return true;
    }

    /**
     * The warp of this name, if this person may change it — saying why not otherwise.
     *
     * <p>Somebody who may not even see a warp is told there is none, as {@code /warp <name>} does:
     * "not yours" about a private warp is telling them it exists.
     */
    private Optional<Warp> changeable(CommandSender who, String name) {
        java.util.UUID id = who instanceof Player player ? player.getUniqueId() : null;
        Optional<Warp> found = catalogue.byName(name);
        if (found.isEmpty() || !access.maySee(catalogue.accessOf(found.get()), who::hasPermission, id,
                found.get().owner().orElse(null), found.get().members())) {
            messages.send(who, "warps.unknown", "name", String.valueOf(name));
            return Optional.empty();
        }
        if (!access.mayChange(who::hasPermission, id, found.get().owner().orElse(null))) {
            messages.send(who, "warps.not-your-warp", "name", found.get().name());
            return Optional.empty();
        }
        return found;
    }

    /** Whether this player may change this one warp — for a screen deciding which buttons to offer. */
    public boolean mayChange(Player who, Warp warp) {
        return warp != null && access.mayChange(who::hasPermission, who.getUniqueId(), warp.owner().orElse(null));
    }

    /**
     * Whether this person may change warps, saying so if not.
     *
     * <p>Refusals are said rather than silent even from the menu, where the button should not have
     * been offered at all: a permission changed while a window is open is exactly when this fires,
     * and the click that then does nothing looks like a broken plugin.
     */
    private boolean mayManage(CommandSender who) {
        if (access.mayManage(who::hasPermission)) {
            return true;
        }
        messages.send(who, "warps.not-yours");
        return false;
    }

    @Override
    public String describe() {
        return "making, moving, retagging and deleting warps";
    }
}
