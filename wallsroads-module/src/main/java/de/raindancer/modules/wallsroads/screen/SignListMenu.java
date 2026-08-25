package de.raindancer.modules.wallsroads.screen;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.core.ui.prompt.ChatPrompts;
import de.raindancer.modules.wallsroads.WallsRoadsServices;
import de.raindancer.modules.wallsroads.model.RoadPath;
import de.raindancer.modules.wallsroads.model.RoadSign;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Every sign this road put up, and the wording on each.
 *
 * <p>The road already names them all after itself; this is where somebody changes one to say what
 * is actually down that way — which is the whole reason a junction sign is worth having.
 */
public final class SignListMenu extends PaginatedMenu<RoadSign> {

    private final WallsRoadsServices services;
    private final RoadPath road;

    public SignListMenu(WallsRoadsServices services, Player viewer, RoadPath road, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.road = road;
    }

    @Override
    protected Component title() {
        return Component.text(road.name() + " — Signs");
    }

    @Override
    protected List<RoadSign> entries() {
        return road.signs();
    }

    @Override
    protected ItemStack icon(RoadSign sign) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + sign.spot().x() + ", " + sign.spot().y() + ", " + sign.spot().z());
        lore.add("");
        for (String line : sign.lines()) {
            lore.add(line.isBlank() ? "<dark_gray>—" : "<white>" + line);
        }
        lore.add("");
        lore.add("<yellow>Click <gray>then type the new wording in chat");
        lore.add("<yellow>Right click <gray>to point people at a place");
        return Icons.of(Material.OAK_SIGN, "<white>Sign", lore);
    }

    @Override
    protected void onClick(RoadSign sign, InventoryClickEvent event) {
        if (event.isRightClick()) {
            askDestination(sign);
            return;
        }
        askWording(sign);
    }

    private void askWording(RoadSign sign) {
        viewer.closeInventory();
        services.messages().send(viewer, "wallsroads.sign.ask-lines");
        ChatPrompts prompts = services.core().prompts();
        prompts.ask(viewer.getUniqueId(), "WallsRoads", Duration.ofSeconds(60), typed ->
                Scheduling.entity(services.plugin(), viewer, () -> {
                    services.service().renameSign(road, sign.id(), splitIntoLines(typed));
                    new SignListMenu(services, viewer, road, parent()).open();
                }), () -> { });
    }

    /**
     * Points the sign at a claim, and says how far it is.
     *
     * <p>This is the one thing a road sign is really for, and the reason this module knows about
     * claims at all: a road that says "Eastgate — 320 blocks" is navigation, and one that says
     * "Eastgate Road" four times over is wallpaper.
     */
    private void askDestination(RoadSign sign) {
        viewer.closeInventory();
        services.messages().send(viewer, "wallsroads.sign.ask-destination");
        ChatPrompts prompts = services.core().prompts();
        prompts.ask(viewer.getUniqueId(), "WallsRoads", Duration.ofSeconds(60), typed ->
                Scheduling.entity(services.plugin(), viewer, () -> {
                    services.claimLink().entranceOf(typed).ifPresentOrElse(entrance -> {
                        int blocks = (int) Math.round(entrance.toVector().distance(
                                new org.bukkit.util.Vector(sign.spot().x(), sign.spot().y(), sign.spot().z())));
                        services.service().renameSign(road, sign.id(),
                                List.of(road.name(), "", typed, blocks + " blocks"));
                        services.messages().send(viewer, "wallsroads.sign.pointed",
                                "place", typed, "blocks", String.valueOf(blocks));
                    }, () -> services.messages().send(viewer, "wallsroads.sign.no-such-place",
                            "place", typed));
                    new SignListMenu(services, viewer, road, parent()).open();
                }), () -> { });
    }

    /** Four lines is what a sign has; anything past the fourth is dropped rather than silently lost. */
    private static List<String> splitIntoLines(String typed) {
        String[] parts = typed.split("\\|");
        List<String> lines = new ArrayList<>(4);
        for (int i = 0; i < Math.min(4, parts.length); i++) {
            lines.add(parts[i].trim());
        }
        return lines;
    }
}
