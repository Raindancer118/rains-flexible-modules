package de.raindancer.modules.ctf.screen;

import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.ctf.CtfServices;
import de.raindancer.modules.ctf.model.Base;
import de.raindancer.modules.ctf.model.CtfMap;
import de.raindancer.modules.ctf.model.GameType;
import de.raindancer.modules.ctf.model.Point;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Marking a map while standing in it: each button marks where its user stands. Four colours of base; a right click on
 * a base's spawn button clears its spawns, on a power-up or drop button clears all of those.
 */
public final class SetupMenu extends Menu implements ICtfScreen {

    private static final List<TeamColour> COLOURS = List.of(TeamColour.RED, TeamColour.BLUE, TeamColour.LIME,
            TeamColour.YELLOW);

    private final CtfServices services;

    public SetupMenu(CtfServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Setting up " + services.setup().current(viewer)
                .map(CtfMap::id).orElse("?"));
    }

    @Override
    protected void render() {
        Optional<CtfMap> current = services.setup().current(viewer);
        if (current.isEmpty()) {
            band(2, 4, Icons.of(Material.BARRIER, "<red>No map being set up", "<gray>/ctf admin map setup <file>"));
            return;
        }
        CtfMap map = current.get();
        int column = 1;
        for (TeamColour colour : COLOURS) {
            Optional<Base> base = map.base(colour);
            String hex = colour.textColour().asHexString();
            String name = "<" + hex + ">" + colour.name().charAt(0) + colour.name().substring(1).toLowerCase();
            band(1, column, Icons.of(colour.bannerMaterial(), name + " flag here", base.map(Base::flag)
                    .map(at -> "<gray>Now at " + at.blockX() + " " + at.blockY() + " " + at.blockZ())
                    .orElse("<gray>Not set.")), click -> mark(at -> map.withBase(base.orElse(new Base(colour, null,
                    List.of())).withFlag(at))));
            band(2, column, Icons.of(Material.RESPAWN_ANCHOR, name + " spawn here", "<gray>"
                    + base.map(Base::spawns).map(List::size).orElse(0) + " spawn(s).", "<gray>Right click: clear them"),
                    click -> {
                        if (click.isRightClick()) {
                            change(now -> now.base(colour).map(found -> now.withBase(found.withoutSpawns())).orElse(now));
                        } else {
                            mark(at -> map.withBase(base.orElse(new Base(colour, null, List.of())).withSpawn(at)));
                        }
                    });
            column += 2;
        }
        band(3, 1, Icons.of(Material.WHITE_BANNER, "<white>Middle here", "<gray>The neutral flag, and where both",
                "<gray>flags go in sudden death."), click -> mark(map::withNeutral));
        band(3, 2, Icons.of(Material.BEACON, "<aqua>Control point here", map.control() == null ? "<gray>None."
                : "<gray>Set."), click -> mark(map::withControl));
        band(3, 3, Icons.of(Material.BLAZE_POWDER, "<gold>Power-up here", "<gray>" + map.powerUps().size()
                + " so far.", "<gray>Right click: clear them"), click -> {
            if (click.isRightClick()) {
                change(CtfMap::withoutPowerUps);
            } else {
                mark(map::withPowerUp);
            }
        });
        band(3, 4, Icons.of(Material.CHEST, "<gold>Supply drop here", "<gray>" + map.supplies().size() + " so far.",
                "<gray>Right click: clear them"), click -> {
            if (click.isRightClick()) {
                change(CtfMap::withoutSupplies);
            } else {
                mark(map::withSupply);
            }
        });
        band(3, 5, Icons.of(Material.LAVA_BUCKET, "<red>Floor here", "<gray>Below this height a player",
                "<gray>has fallen off: now " + map.floor()), click -> mark(at -> map.withFloor(at.blockY())));
        band(3, 6, Icons.of(Material.ENDER_EYE, "<light_purple>Spectators here", map.spectate() == null
                ? "<gray>Above the middle." : "<gray>Set."), click -> mark(map::withSpectate));
        List<String> missing = map.missingFor(GameType.CLASSIC);
        band(3, 7, Icons.of(missing.isEmpty() ? Material.LIME_DYE : Material.GRAY_DYE, missing.isEmpty()
                ? "<green>Playable" : "<yellow>Not playable yet", missing.isEmpty() ? "<gray>Click: done setting up"
                : "<gray>Missing: " + String.join(", ", missing)), click -> {
            services.setup().done(viewer.getUniqueId());
            services.messages().send(viewer, "ctf.admin.setup-done", "map", map.id());
            viewer.closeInventory();
        });
    }

    private void mark(java.util.function.Function<Point, CtfMap> changed) {
        Optional<Point> at = services.setup().here(viewer);
        if (at.isEmpty()) {
            services.messages().send(viewer, "ctf.admin.not-in-map");
            return;
        }
        change(now -> changed.apply(at.get()));
    }

    private void change(UnaryOperator<CtfMap> change) {
        if (services.setup().change(viewer, change)) {
            services.messages().send(viewer, "ctf.admin.marked");
        }
        refresh();
    }

    @Override
    public String breadcrumb() {
        return "set-up";
    }

    @Override
    public String describe() {
        return "setting up a map";
    }
}
