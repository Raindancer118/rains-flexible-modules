package de.raindancer.modules.anticheat.screen;

import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.anticheat.AntiCheatServices;
import de.raindancer.modules.anticheat.model.CheckType;
import de.raindancer.modules.anticheat.model.Evidence;
import de.raindancer.modules.anticheat.model.PlayerTrack;
import de.raindancer.modules.anticheat.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** One player's level on every check, with the latest evidence behind each. */
public final class PlayerChecksMenu extends PaginatedMenu<CheckType> implements IAntiCheatScreen {

    private final AntiCheatServices services;
    private final PlayerTrack track;
    private final List<CheckType> checks;

    public PlayerChecksMenu(AntiCheatServices services, Player viewer, Menu parent, PlayerTrack track) {
        super(viewer, services.chat().brand(), parent);
        this.services = services;
        this.track = track;
        this.checks = new ArrayList<>(Arrays.asList(CheckType.values()));
        checks.sort((one, other) -> Double.compare(track.violations().level(other), track.violations().level(one)));
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Anti-cheat — <white>" + track.name() + "</white>");
    }

    @Override
    public String breadcrumb() {
        return track.name();
    }

    @Override
    protected List<CheckType> entries() {
        return checks;
    }

    @Override
    protected ItemStack icon(CheckType check) {
        double level = track.violations().level(check);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + check.category().title() + (check.experimental() ? " · experimental" : ""));
        lore.add((level > 0 ? "<yellow>" : "<green>") + "Level " + String.format(Locale.ROOT, "%.1f", level)
                + (check.kickAt() > 0 ? "<dark_gray> / kick at " + check.kickAt() : ""));
        lore.add("<dark_gray>" + check.description());
        int shown = 0;
        for (Evidence entry : services.evidence().of(track.id())) {
            if (!entry.check().equals(check.key())) {
                continue;
            }
            if (shown++ == 3) {
                break;
            }
            lore.add("<gray> · " + entry.detail());
        }
        lore.add("");
        lore.add("<dark_gray>Click to list its evidence in chat.");
        Material icon = level > 0 ? check.category().icon() : Material.GRAY_DYE;
        return Icons.of(icon, (level > 0 ? "<yellow>" : "<gray>") + check.title(), lore);
    }

    @Override
    protected void onClick(CheckType check, InventoryClickEvent event) {
        viewer.closeInventory();
        int listed = 0;
        services.messages().send(viewer, "anticheat.log-header", "player", track.name(), "page", 1, "pages", 1, "count", check.title());
        for (Evidence entry : services.evidence().of(track.id())) {
            if (entry.check().equals(check.key()) && listed++ < 15) {
                services.messages().send(viewer, "anticheat.log-line", "time", new java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT)
                                .format(new java.util.Date(entry.atMillis())), "check", check.title(),
                        "level", String.format(Locale.ROOT, "%.1f", entry.level()), "detail", entry.detail(),
                        "where", entry.world() + " " + entry.x() + " " + entry.y() + " " + entry.z(), "ping", entry.ping(),
                        "tps", String.format(Locale.ROOT, "%.1f", entry.tps()));
            }
        }
        if (listed == 0) {
            services.messages().send(viewer, "anticheat.log-empty", "player", track.name());
        }
    }

    @Override
    protected void render() {
        super.render();
        boolean manage = viewer.hasPermission(PermissionNodes.MANAGE);
        Player target = services.server().getPlayer(track.id());
        toolbar(2, target != null, Icons.of(Material.ENDER_PEARL, "<yellow>Go to them", "<gray>Teleports you to " + track.name() + ".",
                "<dark_gray>Watch them yourself."), "They are not online", click -> {
            Player now = services.server().getPlayer(track.id());
            if (now == null) {
                services.messages().send(viewer, "anticheat.not-online", "player", track.name());
                return;
            }
            viewer.teleportAsync(now.getLocation());
        });
        toolbar(4, manage, Icons.of(Material.CLOCK, "<yellow>Leave them alone for a minute",
                "<gray>No check judges " + track.name() + " for 60 seconds.", "<dark_gray>For a plugin that moves them oddly."),
                "Needs rainsanticheat.manage", click -> {
                    track.exempt(PlayerTrack.Exemption.MANUAL, 60_000);
                    services.messages().send(viewer, "anticheat.exempted", "player", track.name(), "seconds", 60);
                });
        if (manage) {
            danger(Icons.of(Material.LAVA_BUCKET, "<red>Clear their record", "<gray>Sets every violation level back to zero.",
                    "<dark_gray>Evidence already written is kept."), click -> new ConfirmMenu(viewer, services.chat().brand(), this,
                    "Clear " + track.name() + "'s violation levels?", List.of("<gray>Every check starts from zero for them."),
                    () -> {
                        track.violations().resetAll();
                        services.messages().send(viewer, "anticheat.reset", "player", track.name());
                    }).open());
        } else {
            danger(Icons.locked(Icons.of(Material.LAVA_BUCKET, "<red>Clear their record"), "Needs rainsanticheat.manage"), click -> {
                services.messages().send(viewer, "anticheat.no-permission");
            });
        }
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>Every check, the ones " + track.name() + " failed most first.",
                "<gray>Levels fall again on their own while nothing fails.");
    }

    @Override
    public String describe() {
        return "one player's level on every check";
    }
}
