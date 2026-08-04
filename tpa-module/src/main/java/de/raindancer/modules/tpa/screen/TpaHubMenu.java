package de.raindancer.modules.tpa.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.tpa.TpaServices;
import de.raindancer.modules.tpa.model.TpaKind;
import de.raindancer.modules.tpa.model.TpaPrefs;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * What bare {@code /tpa} opens: everything this module can do, as buttons.
 *
 * <h2>Why a hub at all</h2>
 * Because the feature is nine commands with names that all begin the same way, and nobody remembers
 * which of {@code /tpdeny} and {@code /tpadeny} their server has. The commands stay — typing
 * {@code /tpa Bob} is faster than any number of clicks — but somebody who does not already know them
 * has one thing to type and can see the rest.
 *
 * <p>Buttons two columns apart, so a pane falls between each pair. A wall of adjacent buttons is
 * unreadable.
 */
public final class TpaHubMenu extends Menu implements ITpaScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final TpaServices services;

    public TpaHubMenu(TpaServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent, 3);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Teleport requests");
    }

    @Override
    public String breadcrumb() {
        return "Teleport requests";
    }

    @Override
    protected void render() {
        band(MenuLayout.WHO, 1, Icons.of(Material.ENDER_PEARL, "<white>Ask to go to somebody",
                        "<gray>They are asked; you are the one who travels.",
                        "",
                        "<gray>Click to pick who."),
                click -> services.screens().whoToAsk(viewer, TpaKind.TO));

        band(MenuLayout.WHO, 3, Icons.of(Material.ENDER_EYE, "<white>Ask somebody to come to you",
                        "<gray>They are asked; they are the one who travels.",
                        "",
                        "<gray>Click to pick who."),
                click -> services.screens().whoToAsk(viewer, TpaKind.HERE));

        int waiting = services.requests().to(viewer.getUniqueId()).size();
        band(MenuLayout.WHO, 5, Icons.of(Material.PAPER, "<white>Requests",
                        "<gray>" + (waiting == 0 ? "Nobody is waiting on you."
                                : waiting + " waiting on you."),
                        "<dark_gray>And whatever you have asked, to take back.",
                        "",
                        "<gray>Click to see them."),
                click -> services.screens().requests(viewer));

        // Whether it exists at all is the owner's decision, and a button for something the server does
        // not have is worse than no button. Greyed rather than gone would be worse still: it would say
        // the feature exists and is being withheld from this player, which is not true.
        if (services.back().isEnabled()) {
            boolean somewhere = services.back().waiting(viewer).isPresent();
            List<String> lore = new ArrayList<>();
            lore.add(somewhere
                    ? "<gray>" + services.back().waiting(viewer).orElseThrow().cause().describe()
                            + "."
                    : "<gray>Nowhere to go back to just yet.");
            lore.add("<dark_gray>Set by any teleport — a warp, a home, a request.");
            lore.add("");
            lore.add(somewhere ? "<gray>Click to go. This closes the menu." : "");

            band(MenuLayout.RULES, 1, somewhere,
                    Icons.of(Material.COMPASS, "<white>Go back", lore),
                    "You have not been sent anywhere yet",
                    click -> {
                        viewer.closeInventory();
                        services.back().go(viewer);
                    });
        }

        TpaPrefs prefs = services.prefs().of(viewer.getUniqueId());
        band(MenuLayout.RULES, 3, Icons.of(prefs.accepting() ? Material.LEVER : Material.GRAY_DYE,
                        "<white>People may ask you",
                        prefs.accepting() ? "<green>On" : "<red>Off",
                        "<dark_gray>Off, nobody can ask — a blanket switch,",
                        "<dark_gray>kept apart from who you have blocked.",
                        "",
                        "<gray>Click to turn it " + (prefs.accepting() ? "off." : "on.")),
                click -> {
                    services.prefs().toggle(viewer);
                    refresh();
                });

        int blocked = prefs.blocked().size();
        band(MenuLayout.RULES, 5, Icons.of(Material.IRON_DOOR, "<white>Blocked",
                        "<gray>" + (blocked == 0 ? "Nobody." : blocked + " blocked."),
                        "<dark_gray>They are told the same thing as somebody",
                        "<dark_gray>you have simply switched off — a block is",
                        "<dark_gray>not something the other person can see.",
                        "",
                        "<gray>Click to see the list."),
                click -> services.screens().blocked(viewer));
    }

    @Override
    protected List<String> helpLines() {
        return services.messages().lines("tpa.manual.using",
                        "seconds", services.config().requestStanding(),
                        "warmup", services.config().warmup())
                .stream().map(MINI::serialize).toList();
    }

    @Override
    public String describe() {
        return "everything this module can do, as buttons";
    }
}
