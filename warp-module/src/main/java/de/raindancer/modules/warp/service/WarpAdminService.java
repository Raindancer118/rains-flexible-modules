package de.raindancer.modules.warp.service;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.warp.model.Warp;
import de.raindancer.modules.warp.WarpSettings;
import de.raindancer.modules.warp.model.WarpAccess;
import de.raindancer.modules.warp.rules.WarpAccessRule;
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

    private final WarpCatalogue catalogue;
    private final WarpAccessRule access;
    private final Messages messages;

    private volatile WarpSettings settings;
    private volatile WarpNameRule names;

    public WarpAdminService(WarpCatalogue catalogue, WarpAccessRule access, Messages messages,
                            WarpSettings settings) {
        this.catalogue = catalogue;
        this.access = access;
        this.messages = messages;
        settings(settings);
    }

    @Override
    public void settings(WarpSettings fresh) {
        this.settings = fresh;
        // Rebuilt rather than told, because the name limit is the only thing the rule holds and a
        // rule you can change is one two callers can see differently.
        this.names = new WarpNameRule(fresh.nameLimit());
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
        if (!mayManage(maker)) {
            return Optional.empty();
        }
        WarpNameRule.Verdict verdict = names.check(name);
        if (!verdict.isFine()) {
            messages.send(maker, verdict.messageKey(),
                    "name", String.valueOf(name), "limit", names.longestName());
            return Optional.empty();
        }
        // Only when it would be a new one: replacing an existing warp is how a badly placed one is
        // corrected, and refusing that at the ceiling would mean a full server could never fix one.
        boolean replacing = catalogue.byName(name).isPresent();
        if (!replacing && !names.isRoomFor(catalogue.count(), settings.warpLimit())) {
            messages.send(maker, "warps.too-many", "limit", settings.warpLimit());
            return Optional.empty();
        }

        Optional<Warp> made = catalogue.create(name, maker.getLocation(), maker.getUniqueId());
        made.ifPresentOrElse(
                warp -> messages.send(maker, replacing ? "warps.replaced" : "warps.created",
                        "name", warp.name()),
                // No <name> given: the line is "a warp needs a name", so there is no name to put in
                // it. A value supplied to a message with nowhere to put it is usually the same typo
                // seen from the other side, which is why MessagesTest checks both directions.
                () -> messages.send(maker, "warps.name.empty"));
        return made;
    }

    /** Moves an existing warp to where this player is standing, keeping everything about it. */
    public boolean move(Player mover, String name) {
        if (!mayManage(mover)) {
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
        if (!mayManage(remover)) {
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
        if (!mayManage(changer)) {
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
        if (!mayManage(changer)) {
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
        if (!mayManage(changer)) {
            return false;
        }
        if (!catalogue.setIcon(name, icon)) {
            messages.send(changer, "warps.unknown", "name", String.valueOf(name));
            return false;
        }
        messages.send(changer, "warps.icon-set", "name", name, "icon", icon.name());
        return true;
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
