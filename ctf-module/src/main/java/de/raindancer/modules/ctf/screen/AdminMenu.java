package de.raindancer.modules.ctf.screen;

import de.raindancer.core.data.settings.SettingsMenu;
import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.ctf.CtfServices;
import de.raindancer.modules.ctf.model.CtfMap;
import de.raindancer.modules.ctf.model.GameType;
import de.raindancer.modules.ctf.model.Phase;
import de.raindancer.modules.ctf.model.Point;
import de.raindancer.modules.ctf.store.LobbyStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Staff's door: start and stop, the maps, where the lobby and its parkour are, the boards, every setting. */
public final class AdminMenu extends Menu implements ICtfScreen {

    private final CtfServices services;

    public AdminMenu(CtfServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_red>Capture the Flag <dark_gray>— staff");
    }

    @Override
    protected void render() {
        Phase phase = services.rounds().phase();
        band(1, 1, services.rounds().round().isEmpty(), Icons.of(Material.LIME_CONCRETE, "<green>Start now",
                "<gray>With whoever is in the lobby (two at least)."), "A round is on already.", click -> {
            services.rounds().startNow();
            services.messages().send(viewer, "ctf.admin.starting");
            viewer.closeInventory();
        });
        band(1, 2, phase.isPlaying(), Icons.of(Material.RED_CONCRETE, "<red>Stop the round",
                "<gray>Ends it as a draw; fees go back."), "No round is on.", click ->
                new ConfirmMenu(viewer, services.brand(), this, "Stop the round as a draw?",
                        List.of("Nobody wins; every entry fee goes back."), () -> {
                    services.rounds().stopNow();
                    viewer.closeInventory();
                }).open());
        List<CtfMap> maps = services.maps().all();
        long playable = maps.stream().filter(CtfMap::isPlayable).count();
        band(1, 4, Icons.of(Material.FILLED_MAP, "<aqua>Maps", "<gray>" + maps.size() + " set up, " + playable
                + " playable.", "<gray>" + services.maps().schematics().size() + " schematic(s) in the folder.",
                "<yellow>Click: list them"), click -> {
            viewer.closeInventory();
            listMaps();
        });
        band(1, 6, Icons.of(Material.COMPARATOR, "<gold>Settings", "<gray>Every rule, twist and reward."),
                click -> new SettingsMenu(viewer, services.brand(), services.core().chatFor(services.brand()),
                        services.core().settingsNavigation(), "ctf", this).open());

        LobbyStore.Lobby lobby = services.lobby().lobby();
        band(2, 1, Icons.of(Material.OAK_DOOR, "<white>Lobby spawn here", lobby.spawn() == null ? "<gray>Not set."
                : "<gray>Set in " + lobby.spawn().world() + "."), click -> {
            services.lobby().change(now -> now.withSpawn(here()));
            services.messages().send(viewer, "ctf.admin.lobby-set");
        });
        band(2, 3, Icons.of(Material.LIGHT_WEIGHTED_PRESSURE_PLATE, "<white>Parkour start here",
                lobby.parkourStart() == null ? "<gray>Not set." : "<gray>Set."), click -> {
            services.lobby().change(now -> now.withParkourStart(here()));
            services.messages().send(viewer, "ctf.admin.parkour-set", "what", "start");
        });
        band(2, 4, Icons.of(Material.STONE_PRESSURE_PLATE, "<white>Parkour checkpoint here",
                "<gray>" + lobby.checkpoints().size() + " so far, in order.", "<gray>Right click: remove them all"), click -> {
            if (click.isRightClick()) {
                services.lobby().change(LobbyStore.Lobby::withoutCheckpoints);
                services.messages().send(viewer, "ctf.admin.parkour-cleared");
            } else {
                services.lobby().change(now -> now.withCheckpoint(here()));
                services.messages().send(viewer, "ctf.admin.parkour-set", "what", "checkpoint");
            }
            refresh();
        });
        band(2, 5, Icons.of(Material.HEAVY_WEIGHTED_PRESSURE_PLATE, "<white>Parkour finish here",
                lobby.parkourFinish() == null ? "<gray>Not set." : "<gray>Set."), click -> {
            services.lobby().change(now -> now.withParkourFinish(here()));
            services.messages().send(viewer, "ctf.admin.parkour-set", "what", "finish");
        });
        band(2, 7, Icons.of(Material.WHITE_BANNER, "<white>Throwing line here",
                lobby.throwLine() == null ? "<gray>Not set." : "<gray>Set."), click -> {
            services.lobby().change(now -> now.withThrowLine(here()));
            services.messages().send(viewer, "ctf.admin.throw-line-set");
        });
        band(3, 3, Icons.of(Material.OAK_SIGN, "<gold>Leaderboard here", "<gray>" + services.boards().count()
                + " standing.", "<gray>Right click: take the nearest away"), click -> {
            if (click.isRightClick()) {
                services.messages().send(viewer, services.boards().removeNearest(viewer) ? "ctf.admin.board-removed"
                        : "ctf.admin.no-board-near");
            } else {
                services.boards().place(viewer.getLocation());
                services.messages().send(viewer, "ctf.admin.board-placed");
            }
            refresh();
        });
        band(3, 5, services.setup().editing(viewer.getUniqueId()).isPresent(), Icons.of(Material.BRUSH,
                "<aqua>Map set-up tools", "<gray>Mark flags, spawns, power-ups…"), "Start with /ctf admin map setup <file>.",
                click -> services.screens().setup(viewer));
    }

    private LobbyStore.Place here() {
        Location at = viewer.getLocation();
        return new LobbyStore.Place(at.getWorld().getName(), new Point(at.getX(), at.getY(), at.getZ(),
                at.getYaw(), at.getPitch()));
    }

    private void listMaps() {
        List<CtfMap> maps = services.maps().all();
        if (maps.isEmpty()) {
            services.messages().send(viewer, "ctf.admin.no-maps", "folder", services.setup().folder().toString());
        }
        for (CtfMap map : maps) {
            List<String> missing = map.missingFor(GameType.CLASSIC);
            services.messages().send(viewer, missing.isEmpty() ? "ctf.admin.map-line" : "ctf.admin.map-line-missing",
                    "map", map.id(), "title", map.title(), "bases", map.completeBases().size(),
                    "missing", String.join(", ", missing));
        }
        for (String file : services.maps().schematics()) {
            if (maps.stream().noneMatch(map -> map.file().equals(file))) {
                services.messages().send(viewer, "ctf.admin.schematic-line", "file", file);
            }
        }
    }

    @Override
    public String breadcrumb() {
        return "staff";
    }

    @Override
    public String describe() {
        return "Capture the Flag's staff tools";
    }
}
