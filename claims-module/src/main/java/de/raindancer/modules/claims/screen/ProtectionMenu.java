package de.raindancer.modules.claims.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;

import java.util.List;
import java.util.function.Consumer;

/** What the world may do inside the border — and, for an owner, whether it applies to them. */
public final class ProtectionMenu extends ClaimScreen {

    private final Consumer<Claim> openTheRules;
    private final int changedFlags;

    public ProtectionMenu(ClaimServices services, org.bukkit.entity.Player viewer, Claim claim, Menu parent,
                          Consumer<Claim> openTheRules, int changedFlags) {
        super(services, viewer, claim, parent);
        this.openTheRules = openTheRules;
        this.changedFlags = changedFlags;
    }

    @Override
    protected Component title() {
        return Component.text("Protection — " + claim().name());
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>What happens on this ground, and who decides.",
                "",
                "<white>Rules</white> <dark_gray>·</dark_gray> <gray>fire, mobs, explosions, PvP",
                "<white>What this claim can do</white> <dark_gray>·</dark_gray> <gray>the server's list",
                "",
                "<dark_gray>A rule the server fixed is shown but cannot be changed.");
    }

    @Override
    protected void render() {
        Claim claim = claim();

        band(MenuLayout.RULES, 2, may(ClaimAdminPermission.MANAGE_FLAGS),
                Icons.of(Material.REDSTONE_TORCH, "<gold>Rules",
                        "<gray>Fire, mobs, explosions, PvP —",
                        "<gray>what the world does inside your border.",
                        "<dark_gray>" + changedFlags + " changed from the server default"),
                "The owner's to change",
                click -> openTheRules.accept(claim));

        band(MenuLayout.RULES, 4, true,
                Icons.of(Material.COMPARATOR, "<gold>What this claim can do",
                        "<gray>Which perks this claim is allowed at all.",
                        "<dark_gray>set by the server, shown here so you know"),
                "",
                click -> new FeaturesMenu(services(), viewer, claim, this).open());

        // Only for an owner, and only on their own claim: this is not the server-wide bypass, and somebody
        // who is merely trusted has no rules of their own here to be excused from.
        if (claim.isOwner(viewer.getUniqueId())) {
            boolean ignoring = claim.isIgnoringOwnRules(viewer.getUniqueId());
            band(MenuLayout.RULES, 6,
                    Icons.of(ignoring ? Material.ENDER_EYE : Material.ENDER_PEARL,
                            ignoring ? "<green>Ignoring your own rules" : "<gray>Following your own rules",
                            "<gray>Excuses you from this claim's flags while you work on it.",
                            "<dark_gray>this claim only, and only until the server restarts",
                            "",
                            "<dark_gray>click to " + (ignoring ? "follow them again" : "ignore them")),
                    click -> {
                        claim.toggleIgnoringOwnRules(viewer.getUniqueId());
                        refresh();
                    });
        }
    }
}
