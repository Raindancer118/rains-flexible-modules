package de.raindancer.modules.ctf.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.ctf.CtfServices;
import de.raindancer.modules.ctf.model.Phase;
import de.raindancer.modules.ctf.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Capture the Flag's front door: play, vote, class, side, looks, records, challenges — and staff's own door. */
public final class CtfMenu extends Menu implements ICtfScreen {

    private final CtfServices services;

    public CtfMenu(CtfServices services, Player viewer) {
        super(viewer, services.brand(), null);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_red>⚑ <dark_gray>Capture the Flag");
    }

    @Override
    protected void render() {
        boolean inside = services.lobby().isInside(viewer.getUniqueId());
        boolean inRound = services.rounds().isIn(viewer.getUniqueId());
        Phase phase = services.rounds().phase();
        String state = switch (phase) {
            case WAITING -> "<gray>Waiting for players.";
            case COUNTDOWN -> "<yellow>Starting in " + services.rounds().countdown() + "s.";
            case PREPARING -> "<yellow>The map is being built.";
            case RUNNING, OVERTIME -> "<green>A round is on.";
            case OVER -> "<gray>A round just ended.";
        };
        if (!inside) {
            band(1, 4, Icons.of(Material.LIME_BANNER, "<green><bold>Play", state, "<gray>Into the lobby — what you",
                    "<gray>carry is kept until you leave."), click -> {
                viewer.closeInventory();
                services.rounds().join(viewer);
            });
        } else {
            band(1, 2, Icons.of(Material.COMPASS, "<yellow>Vote", "<gray>Map, kind of round and twist.",
                    services.votes().isOpen() ? "<green>Open now." : "<gray>Opens when the countdown starts."),
                    click -> services.screens().vote(viewer));
            band(1, 3, Icons.of(Material.IRON_SWORD, "<aqua>Class", "<gray>What you fight with.",
                    "<gray>Now: <white>" + services.rounds().pickOf(viewer.getUniqueId()).title()),
                    click -> services.screens().classes(viewer));
            band(1, 4, Icons.of(Material.WHITE_BANNER, "<white>Side", "<gray>Which side you would like.",
                    "<gray>Kept as long as the sides stay even."), click -> services.screens().sides(viewer));
            band(1, 5, inRound || phase.isPlaying(), Icons.of(Material.ENDER_EYE, "<light_purple>Watch",
                    "<gray>Watch the round from above.", "<gray>A carrier is followed; sneak to look round."),
                    "No round is on.", click -> {
                        viewer.closeInventory();
                        services.rounds().watch(viewer);
                    });
            band(1, 6, Icons.of(Material.RED_BED, "<red>Leave", "<gray>Out of Capture the Flag,",
                    "<gray>with what you carried before."), click -> {
                viewer.closeInventory();
                services.rounds().leave(viewer);
            });
        }
        band(2, 2, Icons.of(Material.BOOK, "<gold>Your record", "<gray>Rounds, wins, captures, level."),
                click -> services.screens().stats(viewer, viewer.getUniqueId()));
        band(2, 3, Icons.of(Material.GOLDEN_HELMET, "<gold>The best", "<gray>All time and this week."),
                click -> services.screens().top(viewer));
        band(2, 4, Icons.of(Material.FIREWORK_ROCKET, "<light_purple>Your looks", "<gray>Flag trail and capture fireworks,",
                "<gray>unlocked by level."), click -> services.screens().looks(viewer));
        band(2, 5, services.config().get().challenges(), Icons.of(Material.WRITABLE_BOOK, "<green>Today's challenges",
                "<gray>Three things to do today, for experience."), "Challenges are switched off here.",
                click -> services.screens().challenges(viewer));
        band(2, 6, Icons.of(Material.PLAYER_HEAD, "<aqua>Party", "<gray>Play on the same side as friends:",
                "<white>/ctf party invite <player>"), click -> {
            viewer.closeInventory();
            services.messages().send(viewer, "ctf.party.how");
        });
        if (viewer.hasPermission(PermissionNodes.ADMIN)) {
            band(3, 4, Icons.of(Material.COMMAND_BLOCK, "<red>Staff", "<gray>Start and stop rounds, maps, lobby."),
                    click -> services.screens().admin(viewer));
        }
    }

    @Override
    public String breadcrumb() {
        return "capture the flag";
    }

    @Override
    public String describe() {
        return "the Capture the Flag menu";
    }
}
