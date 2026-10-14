package de.raindancer.modules.moderation.screen;

import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.moderation.AntiCheatServices;
import de.raindancer.modules.moderation.model.CheckType;
import de.raindancer.modules.moderation.model.Evidence;
import de.raindancer.modules.moderation.model.PlayerTrack;
import de.raindancer.modules.moderation.util.CheatPermissions;
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
public final class PlayerChecksMenu extends PaginatedMenu<CheckType> implements IModerationScreen {

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
        return MiniMessage.miniMessage().deserialize("<white>" + track.name() + "</white>");
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
        lore.add("<gray>" + check.category().title());
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
            lore.add("<gray> · " + MiniMessage.miniMessage().escapeTags(entry.detail()));
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
        boolean manage = viewer.hasPermission(CheatPermissions.MANAGE);
        Player target = services.server().getPlayer(track.id());
        boolean teleport = viewer.hasPermission("minecraft.command.teleport");
        toolbar(2, target != null && teleport, Icons.of(Material.ENDER_PEARL, "<yellow>Go to them", "<gray>Teleports you to " + track.name() + ".",
                "<dark_gray>Watch them yourself."), target == null ? "They are not online" : "For whoever may teleport", click -> {
            // Asked again on the click: this is no way round the teleport permission.
            if (!viewer.hasPermission("minecraft.command.teleport")) {
                services.messages().send(viewer, "anticheat.no-permission");
                return;
            }
            Player now = services.server().getPlayer(track.id());
            if (now == null) {
                services.messages().send(viewer, "anticheat.not-online", "player", track.name());
                return;
            }
            viewer.teleportAsync(now.getLocation());
        });
        toolbar(6, manage, Icons.of(Material.MILK_BUCKET, "<yellow>Measure afresh",
                        "<gray>The checks see " + track.name() + " as new:", "<gray>levels and statistics start over.",
                        "<dark_gray>Evidence and punishments are kept;", "<dark_gray>the log marks what came before."),
                "Needs rainsanticheat.manage", click -> new ConfirmMenu(viewer, services.chat().brand(), this,
                        "Measure " + track.name() + " afresh?", List.of("<gray>Every check starts from zero for them.",
                        "<gray>Their evidence and punishments stay as history."),
                        () -> {
                            services.violations().measureAfresh(track.id(), track, System.currentTimeMillis());
                            services.alerts().freshStart(track.id());
                            services.messages().send(viewer, "anticheat.fresh", "player", track.name());
                        }).open());
        var clear = Icons.of(Material.LAVA_BUCKET, "<red>Clear their record", "<gray>A fresh start: violation levels",
                "<gray>and evidence are deleted.", "<dark_gray>Kicks and bans stay in their history.");
        danger(manage ? clear : Icons.locked(clear, "Needs rainsanticheat.manage"), click -> {
            if (!manage) {
                services.messages().send(viewer, "anticheat.no-permission");
                return;
            }
            new ConfirmScreen(services.chat().brand(), viewer, this, "Clear " + track.name() + "'s whole record?",
                    List.of("<gray>Every family starts from zero for them,", "<gray>and their evidence is gone for good."),
                    "<dark_gray>This cannot be undone.", () -> {
                        services.violations().freshStart(track.id(), track);
                        services.messages().send(viewer, "anticheat.reset", "player", track.name());
                    }).open();
        });
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
