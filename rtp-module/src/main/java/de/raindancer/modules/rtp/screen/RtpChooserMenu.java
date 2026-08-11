package de.raindancer.modules.rtp.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.rtp.RtpServices;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Safe landing, or take your chances.
 *
 * <h2>Why this only opens when there is a real choice</h2>
 * {@code RtpCommand} opens this only when {@code RtpSettings#safeArrivalPolicy()} says
 * {@code AVAILABLE} — under any other policy the owner has already decided, and a menu offering a
 * choice that does nothing is worse than not asking. See {@code RtpService#playerMayChoose}.
 *
 * <p>Nothing on this page can be greyed the way a warp's settings can: both buttons always work, for
 * everybody who reaches this page, which is the whole reason it is two buttons rather than one with a
 * confirmation — a random teleport with the safety off is not irreversible in the way a claim's
 * fence coming down is, it is simply a choice somebody is allowed to make lightly.
 */
public final class RtpChooserMenu extends Menu implements IRtpScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final RtpServices services;
    private final Integer minDistance;

    public RtpChooserMenu(RtpServices services, Player viewer, Integer minDistance, Menu parent) {
        super(viewer, services.brand(), parent, 3);
        this.services = services;
        this.minDistance = minDistance;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Where to?");
    }

    @Override
    public String breadcrumb() {
        return "Where to?";
    }

    @Override
    protected void render() {
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.ENDER_PEARL, "<white>Random teleport",
                "<gray>Choose how you want to land.",
                "<dark_gray>This choice is this trip's only — it is not remembered."));

        band(MenuLayout.WHO, 3, Icons.of(Material.SLIME_BLOCK, "<green>Safe landing",
                        "<gray>Looks for solid ground near the point,",
                        "<gray>the same way a warp or a home does.",
                        "",
                        "<dark_gray>Refuses rather than dropping you",
                        "<dark_gray>somewhere already known to be bad."),
                click -> choose(true));

        band(MenuLayout.WHO, 5, Icons.of(Material.MAGMA_BLOCK, "<red>Into the unknown",
                        "<gray>No search at all — wherever the point",
                        "<gray>happens to be is where you go.",
                        "",
                        "<dark_gray>Lava, a cliff, open water, or the void.",
                        "<dark_gray>Nobody is stopping you."),
                click -> choose(false));
    }

    private void choose(boolean safe) {
        viewer.closeInventory();
        services.rtp().go(viewer, safe, minDistance);
    }

    @Override
    public String describe() {
        return "choosing whether this trip's landing is checked for safety";
    }
}
