package de.raindancer.modules.arena.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.arena.ArenaServices;
import de.raindancer.modules.arena.api.MatchSpec;
import de.raindancer.modules.arena.api.WaveSpec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.Map;

/** Mob waves: six quick presets, or a run set up by hand. */
public final class WavesMenu extends Menu implements IArenaScreen {

    private static final Map<String, Material> ICONS = Map.of("warmup", Material.WOODEN_SWORD, "undead",
            Material.ZOMBIE_HEAD, "nether", Material.BLAZE_ROD, "illagers", Material.CROSSBOW, "bossrush",
            Material.RAVAGER_SPAWN_EGG, "endless", Material.CLOCK);

    private final ArenaServices services;

    public WavesMenu(ArenaServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Mob waves");
    }

    @Override
    protected void render() {
        int column = 1;
        for (String name : java.util.List.of("warmup", "undead", "nether", "illagers", "bossrush", "endless")) {
            WaveSpec preset = WaveSpec.PRESETS.get(name);
            band(1, column++, Icons.of(ICONS.get(name), "<red>" + Character.toUpperCase(name.charAt(0)) + name.substring(1),
                    "<gray>" + (preset.isEndless() ? "Endless" : preset.waves() + " waves") + " of "
                            + String.join(", ", preset.mobs()).toLowerCase(java.util.Locale.ROOT).replace('_', ' '),
                    "<gray>Click to choose a kit and start."), click -> new KitPickMenu(services, viewer, this,
                    services.link().isUp(), kit -> new WaveBuilderMenu(services, viewer, this,
                    MatchSpec.waves(preset, kit)).open()).open());
        }
        band(2, 4, Icons.of(Material.CRAFTING_TABLE, "<yellow>Set it up yourself",
                        "<gray>How many waves, which mobs, how big, which kit."),
                click -> new WaveBuilderMenu(services, viewer, this,
                        MatchSpec.waves(WaveSpec.PRESETS.get("warmup").withWaves(5), "gladiator")).open());
    }

    @Override
    public String breadcrumb() {
        return "mob waves";
    }

    @Override
    public String describe() {
        return "choosing a mob run";
    }
}
