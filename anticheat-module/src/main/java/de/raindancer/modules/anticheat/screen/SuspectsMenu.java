package de.raindancer.modules.anticheat.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.anticheat.AntiCheatServices;
import de.raindancer.modules.anticheat.model.CheckType;
import de.raindancer.modules.anticheat.model.PlayerTrack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Everybody online, the most suspicious first. */
public final class SuspectsMenu extends PaginatedMenu<PlayerTrack> implements IAntiCheatScreen {

    private final AntiCheatServices services;
    private final List<PlayerTrack> ranked;

    public SuspectsMenu(AntiCheatServices services, Player viewer, Menu parent) {
        super(viewer, services.chat().brand(), parent);
        this.services = services;
        // Ranked once on open, so the order does not shuffle under the viewer while they read it.
        this.ranked = new ArrayList<>(services.tracks().all());
        ranked.sort(Comparator.comparingDouble((PlayerTrack track) -> track.violations().total()).reversed());
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Anti-cheat — <white>suspects</white>");
    }

    @Override
    public String breadcrumb() {
        return "Suspects";
    }

    @Override
    protected List<PlayerTrack> entries() {
        return ranked;
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nobody is online", "<gray>There is nobody to suspect.");
    }

    @Override
    protected ItemStack icon(PlayerTrack track) {
        double total = track.violations().total();
        List<String> lore = new ArrayList<>();
        String colour = total >= 20 ? "<red>" : total >= 5 ? "<yellow>" : "<green>";
        lore.add("<gray>Violation level: " + colour + String.format(Locale.ROOT, "%.1f", total));
        Map<CheckType, Double> levels = track.violations().snapshot();
        int shown = 0;
        for (Map.Entry<CheckType, Double> entry : levels.entrySet()) {
            if (shown++ == 4) {
                break;
            }
            lore.add("<dark_gray> · <white>" + entry.getKey().title() + "<gray> " + String.format(Locale.ROOT, "%.1f", entry.getValue()));
        }
        if (levels.isEmpty()) {
            lore.add("<dark_gray>Nothing failed lately.");
        }
        lore.add("<gray>Ping " + track.ping + " ms" + (track.packets.tapped ? "" : " · events only"));
        lore.add("");
        lore.add("<dark_gray>Click for every check.");
        return Icons.head(track.id(), "<yellow>" + track.name(), lore);
    }

    @Override
    protected void onClick(PlayerTrack track, InventoryClickEvent event) {
        new PlayerChecksMenu(services, viewer, this, track).open();
    }

    @Override
    protected void render() {
        super.render();
        toolbar(4, Icons.of(Material.BELL, "<yellow>Alerts", "<gray>Switch whether you are told when somebody fails a check.",
                "<dark_gray>Click to switch."), click -> {
            boolean on = services.alerts().toggle(viewer);
            services.messages().send(viewer, on ? "anticheat.alerts-on" : "anticheat.alerts-off");
        });
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>Everybody online, ranked by violation level.",
                "<gray>A level is a reason to watch somebody, not proof on its own.");
    }

    @Override
    public String describe() {
        return "everybody online, the most suspicious first";
    }
}
