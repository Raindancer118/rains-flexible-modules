package de.raindancer.modules.claims.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.claims.Claim;
import de.raindancer.modules.claims.ClaimAdminPermission;
import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.StyledText;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;

/**
 * The words across the screen when somebody arrives and when they leave.
 *
 * <p>Four lines — a title and a subtitle each way — typed in chat. Chat rather than a book, because a title is one
 * short line and a book is four clicks to open and close.
 *
 * <p>What a player types is stored verbatim and never read as MiniMessage. That is deliberate: an owner who could
 * write markup into a title could smuggle a hover or a click event into something every visitor sees. Styling is
 * applied separately, by the buttons below.
 */
public final class TitlesMenu extends ClaimScreen {

    private static final Duration TYPING_TIMEOUT = Duration.ofSeconds(30);

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
                titles.enterTitle(), titles::enterTitle);
        line(MenuLayout.WHO, 5, allowed, Material.PAPER, "Arriving — small line",
                titles.enterSubtitle(), titles::enterSubtitle);
        line(MenuLayout.RULES, 2, allowed, Material.NAME_TAG, "Leaving — big line",
                titles.leaveTitle(), titles::leaveTitle);
        line(MenuLayout.RULES, 5, allowed, Material.PAPER, "Leaving — small line",
                titles.leaveSubtitle(), titles::leaveSubtitle);

        toolbar(4, Icons.of(Material.ENDER_EYE, "<white>Show me",
                        "<gray>Play the arrival titles at yourself."),
                click -> {
                    viewer.closeInventory();
                    if (titles.hasEnterTitle()) {
                        viewer.showTitle(titles.buildEnter());
                    } else {
                        tell("claim.titles-empty");
                    }
                });
    }

    /**
     * One of the four lines: what it says now, and asking for a new one.
     *
     * <p>Right click clears it, because a title you cannot remove is a title you have to set to a space.
     */
    private void line(int band, int column, boolean allowed, Material icon, String label,
                      StyledText current, java.util.function.Consumer<StyledText> set) {
        List<String> lore = new java.util.ArrayList<>();
        lore.add(current.isBlank() ? "<dark_gray>nothing set" : "<white>" + current.raw());
        lore.add("");
        lore.add("<dark_gray>click to type a new one");
        if (!current.isBlank()) {
            lore.add("<dark_gray>right click to clear it");
        }

        band(band, column, allowed, Icons.of(icon, "<green>" + label, lore),
                "The owner's to change",
                click -> {
                    if (click.isRightClick()) {
                        set.accept(StyledText.empty());
                        claim().markDirty();
                        services().claimService().saveAsync(claim());
                        refresh();
                        return;
                    }
                    viewer.closeInventory();
                    tell("claim.ask-title", "what", label);
                    boolean asked = services().prompts().ask(viewer.getUniqueId(), "Claims",
                            TYPING_TIMEOUT,
                            typed -> {
                                set.accept(new StyledText(typed,
                                        net.kyori.adventure.text.format.NamedTextColor.WHITE,
                                        false, false, false, false, false));
                                claim().markDirty();
                                services().claimService().saveAsync(claim());
                                tell("claim.title-set", "what", label);
                            },
                            () -> tell("claim.title-aborted"));
                    if (!asked) {
                        tell("selection.already-being-asked");
                    }
                });
    }
}
