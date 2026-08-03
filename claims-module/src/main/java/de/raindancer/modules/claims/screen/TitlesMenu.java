package de.raindancer.modules.claims.screen;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.claims.ClaimServices;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * The words across the screen when somebody arrives and when they leave.
 *
 * <p>Four lines — a title and a subtitle each way. Each opens {@link TitleLineMenu}, which is where the
 * text, the colour and the four decorations actually live; this screen is the overview and the two "show
 * me" buttons, one per direction, because a preview that could only ever play the enter titles could not
 * answer "what does leaving look like" at all.
 */
public final class TitlesMenu extends ClaimScreen {

    public TitlesMenu(ClaimServices services, Player viewer, Claim claim, Menu parent) {
        super(services, viewer, claim, parent);
    }

    @Override
    protected Component title() {
        return Component.text("Arriving and leaving");
    }

    @Override
    protected void render() {
        boolean allowed = may(ClaimAdminPermission.MANAGE_TITLES);
        var titles = claim().titles();

        line(MenuLayout.WHO, 2, allowed, Material.NAME_TAG, "Arriving — big line",
                titles.enterTitle());
        line(MenuLayout.WHO, 5, allowed, Material.PAPER, "Arriving — small line",
                titles.enterSubtitle());
        line(MenuLayout.RULES, 2, allowed, Material.NAME_TAG, "Leaving — big line",
                titles.leaveTitle());
        line(MenuLayout.RULES, 5, allowed, Material.PAPER, "Leaving — small line",
                titles.leaveSubtitle());

        toolbar(3, Icons.of(Material.ENDER_EYE, "<white>Show me — arriving",
                        "<gray>Play the arrival titles at yourself."),
                click -> {
                    viewer.closeInventory();
                    if (titles.hasEnterTitle()) {
                        viewer.showTitle(titles.buildEnter());
                    } else {
                        tell("claim.titles-empty");
                    }
                });

        toolbar(5, Icons.of(Material.ENDER_PEARL, "<white>Show me — leaving",
                        "<gray>Play the leaving titles at yourself."),
                click -> {
                    viewer.closeInventory();
                    if (titles.hasLeaveTitle()) {
                        viewer.showTitle(titles.buildLeave());
                    } else {
                        tell("claim.titles-empty");
                    }
                });
    }

    /**
     * One of the four lines: what it says now, and the door into styling it.
     *
     * <p>Right click clears it without a trip to the editor, because a title an owner just wants gone
     * should not cost a second screen.
     */
    private void line(int band, int column, boolean allowed, Material icon, String label,
                      de.raindancer.modules.claims.model.StyledText current) {
        List<String> lore = List.of(
                current.isBlank() ? "<dark_gray>nothing set" : current.raw(),
                "<dark_gray>" + current.colorKey(),
                "",
                "<dark_gray>click to open",
                "<dark_gray>right click to clear it");

        band(band, column, allowed, Icons.of(icon, "<green>" + label, lore),
                "The owner's to change",
                click -> {
                    if (click.isRightClick()) {
                        current.raw("");
                        claim().markDirty();
                        services().claimService().saveAsync(claim());
                        refresh();
                        return;
                    }
                    new TitleLineMenu(services(), viewer, claim(), this, current, label).open();
                });
    }
}
