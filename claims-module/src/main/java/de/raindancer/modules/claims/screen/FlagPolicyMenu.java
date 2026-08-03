package de.raindancer.modules.claims.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.core.world.protection.FlagPolicy;
import de.raindancer.core.world.protection.LandFlag;
import de.raindancer.core.world.protection.LandFlagGroup;
import de.raindancer.core.world.protection.LandPolicies;
import de.raindancer.modules.claims.ClaimServices;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Which flags owners may touch, and what a new claim starts with — the server's side of the flags.
 *
 * <p>Before this there was nowhere to set either. The policies lived in memory, built fresh from each flag's
 * own default at every start, so an admin could change nothing; the old standalone plugin at least had a
 * {@code flags:} block in its config. Both halves are here now, and they are written to disk the moment they
 * change.
 *
 * <h2>Grouped and ordered the way Core hands them over</h2>
 * {@link LandFlagGroup#occupied()} and then each flag in declaration order, which is the same order the owner's
 * flag chooser uses and the same order the file is written in. An admin who has just used one screen can find
 * the same flag in the same place in the other, and in the file.
 *
 * <h2>Two clicks, because there are two decisions</h2>
 * Left cycles the policy — available, forced on, forced off, disabled. Right toggles what a new claim starts
 * with, which only means anything while the policy is 'available'; under any other policy the starting value
 * is never consulted, so the screen greys it rather than showing a number that does nothing.
 */
public final class FlagPolicyMenu extends PaginatedMenu<LandFlagGroup> implements IClaimScreen {

    private final ClaimServices services;

    public FlagPolicyMenu(ClaimServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return Component.text("Flags — what owners may change");
    }

    @Override
    protected List<LandFlagGroup> entries() {
        // Core's own grouping and order, not one of this module's making. Two screens that disagree about
        // where a flag lives is two screens somebody has to search separately.
        return new ArrayList<>(LandFlagGroup.occupied());
    }

    @Override
    protected ItemStack icon(LandFlagGroup group) {
        LandPolicies policies = services.core().landPolicies();
        List<LandFlag> flags = group.flags();

        int changed = 0;
        for (LandFlag flag : flags) {
            if (policies.policy(flag) != FlagPolicy.AVAILABLE
                    || policies.flagDefault(flag) != flag.builtInDefault()) {
                changed++;
            }
        }

        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + flags.size() + " flag(s)");
        lore.add(changed == 0
                ? "<dark_gray>all as they ship"
                : "<yellow>" + changed + " changed from the default");
        lore.add("");
        lore.add("<dark_gray>click to open");
        return Icons.of(group.icon(), "<white>" + services.messages().raw(group.nameKey()), lore);
    }

    @Override
    protected void onClick(LandFlagGroup group, InventoryClickEvent event) {
        new GroupPage(services, viewer, this, group).open();
    }

    /** One group's flags, each with its policy and its starting value. */
    private static final class GroupPage extends PaginatedMenu<LandFlag> implements IClaimScreen {

        private final ClaimServices services;
        private final LandFlagGroup group;

        private GroupPage(ClaimServices services, Player viewer, Menu parent, LandFlagGroup group) {
            super(viewer, services.brand(), parent);
            this.services = services;
            this.group = group;
        }

        @Override
        protected Component title() {
            return Component.text(services.messages().raw(group.nameKey()) + " — flags");
        }

        @Override
        protected List<LandFlag> entries() {
            return new ArrayList<>(group.flags());
        }

        @Override
        protected ItemStack icon(LandFlag flag) {
            LandPolicies policies = services.core().landPolicies();
            FlagPolicy policy = policies.policy(flag);
            boolean starts = policies.flagDefault(flag);

            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + services.messages().raw(flag.descriptionKey()));
            lore.add("");
            lore.add(switch (policy) {
                case AVAILABLE -> "<green>Available <dark_gray>· owners decide for their own claim";
                case FORCED_ON -> "<gold>Forced on <dark_gray>· always allowed, owners cannot change it";
                case FORCED_OFF -> "<gold>Forced off <dark_gray>· always denied, owners cannot change it";
                case DISABLED -> "<red>Disabled <dark_gray>· not offered, and never enforced";
            });

            if (policy == FlagPolicy.AVAILABLE) {
                lore.add((starts ? "<green>" : "<red>") + "New claims start "
                        + (starts ? "allowed" : "denied"));
            } else {
                // Greyed rather than hidden: an admin who set a starting value and then forced the flag
                // should be able to see that the value is still there and simply not being asked.
                lore.add("<dark_gray>starting value not used under this policy");
            }
            if (policies.flagDefault(flag) != flag.builtInDefault()) {
                lore.add("<dark_gray>(ships as " + (flag.builtInDefault() ? "allowed" : "denied") + ")");
            }
            lore.add("");
            lore.add("<yellow>left click <dark_gray>· next policy");
            lore.add(policy == FlagPolicy.AVAILABLE
                    ? "<yellow>right click <dark_gray>· flip what new claims start with"
                    : "<dark_gray>right click · nothing to set under this policy");

            Material material = policy == FlagPolicy.DISABLED ? Material.GRAY_DYE : flag.icon();
            return Icons.of(material,
                    (policy == FlagPolicy.DISABLED ? "<dark_gray>" : "<white>")
                            + services.messages().raw(flag.nameKey()), lore);
        }

        @Override
        protected void onClick(LandFlag flag, InventoryClickEvent event) {
            LandPolicies policies = services.core().landPolicies();

            if (event.isRightClick()) {
                if (policies.policy(flag) != FlagPolicy.AVAILABLE) {
                    services.messages().send(viewer, "admin.flag-default-not-used",
                            "flag", services.messages().raw(flag.nameKey()));
                    return;
                }
                boolean now = !policies.flagDefault(flag);
                policies.flagDefault(flag, now);
                persist();
                services.messages().send(viewer, "admin.flag-default-changed",
                        "flag", services.messages().raw(flag.nameKey()),
                        "value", now ? "allowed" : "denied");
                refresh();
                return;
            }

            FlagPolicy next = policies.policy(flag).next();
            policies.policy(flag, next);
            persist();
            services.messages().send(viewer, "admin.flag-policy-changed",
                    "flag", services.messages().raw(flag.nameKey()),
                    "policy", next.displayName());
            refresh();
        }

        /**
         * Straight to disk, on the click.
         *
         * <p>Not batched behind a save timer: this is a protection setting, and one that is in force now but
         * gone after the next restart is the kind of thing an admin only discovers weeks later when somebody
         * blows a hole in a claim.
         */
        private void persist() {
            if (!services.core().saveLandPolicies()) {
                services.messages().send(viewer, "admin.flag-not-saved");
            }
        }
    }
}
