package de.raindancer.modules.arena.screen;

import de.raindancer.core.ui.choose.AmountChooser;
import de.raindancer.core.ui.choose.PlayerChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.arena.ArenaServices;
import de.raindancer.modules.arena.api.MatchSpec;
import de.raindancer.modules.arena.api.WaveSpec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/** A mob run, set up: how many waves, which mobs and how they are drawn, how big, which kit — then go. */
public final class WaveBuilderMenu extends Menu implements IArenaScreen {

    private final ArenaServices services;
    private MatchSpec spec;

    public WaveBuilderMenu(ArenaServices services, Player viewer, Menu parent, MatchSpec spec) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.spec = spec;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Mob run · " + spec.waves().preset());
    }

    private WaveSpec waves() {
        return spec.waves();
    }

    @Override
    protected void render() {
        band(0, 0, Icons.of(Material.CLOCK, "<yellow>" + (waves().isEndless() ? "Endless" : waves().waves() + " waves"),
                "<gray>Click to set. 0 is endless."), click -> new AmountChooser(viewer, services.brand(), this,
                "Waves (0 = endless)", waves().waves(), 0, WaveSpec.MOST_WAVES, value -> {
                    spec = spec.withWaves(waves().withWaves(value));
                    reopen();
                }).open());
        band(0, 1, Icons.of(Material.ZOMBIE_SPAWN_EGG, "<red>Mobs: " + mobs(),
                "<gray>Click to choose which."), click -> new MobPickMenu(services, viewer, this, waves().mobs(), chosen -> {
                    spec = spec.withWaves(waves().withMobs(chosen));
                    reopen();
                }).open());
        band(0, 2, Icons.of(Material.HOPPER, "<aqua>Drawn: " + switch (waves().selection()) {
                    case RANDOM -> "at random from the list";
                    case CYCLE -> "one kind per wave, in turn";
                    case ONE -> "only the first kind";
                }, "<gray>Click for the next way."), click -> {
                    WaveSpec.Selection[] all = WaveSpec.Selection.values();
                    spec = spec.withWaves(waves().withSelection(all[(waves().selection().ordinal() + 1) % all.length]));
                    refresh();
                });
        band(0, 3, Icons.of(Material.SLIME_BALL, "<green>First wave: " + waves().size(), "<gray>Click to set."),
                click -> new AmountChooser(viewer, services.brand(), this, "Mobs in the first wave", waves().size(), 1,
                        WaveSpec.MOST_SIZE, value -> {
                            spec = spec.withWaves(waves().withSize(value));
                            reopen();
                        }).open());
        band(0, 4, Icons.of(Material.EXPERIENCE_BOTTLE, "<green>Each wave adds " + waves().growth(), "<gray>Click to set."),
                click -> new AmountChooser(viewer, services.brand(), this, "Mobs added per wave", waves().growth(), 0, 10,
                        value -> {
                            spec = spec.withWaves(waves().withGrowth(value));
                            reopen();
                        }).open());
        band(1, 0, Icons.of(Material.IRON_CHESTPLATE, "<gold>Kit: " + (spec.isOwnGear() ? "own gear" : spec.kit()),
                "<gray>Click to change."), click -> new KitPickMenu(services, viewer, this, services.link().isUp(), kit -> {
                    spec = spec.withKit(kit);
                    reopen();
                }).open());
        toolbar(3, Icons.of(Material.LIME_CONCRETE, "<green>Start alone", "<gray>Straight into a free pit."), click -> {
            viewer.closeInventory();
            services.queue().join(viewer, spec);
        });
        toolbar(5, Icons.of(Material.PAPER, "<aqua>Ask a friend", "<gray>Fight the waves together."),
                click -> new PlayerChooser(viewer, services.brand(), this, "Ask whom?", de.raindancer.modules.arena.util.OnlinePlayers.of(services.server()),
                        List.of(viewer.getUniqueId()), entry -> {
                            viewer.closeInventory();
                            services.invites().invite(viewer, entry.name(), spec);
                        }).open());
    }

    private String mobs() {
        String list = String.join(", ", waves().mobs()).toLowerCase(Locale.ROOT).replace('_', ' ');
        return list.length() > 40 ? list.substring(0, 37) + "…" : list;
    }

    @Override
    public String breadcrumb() {
        return "the run";
    }

    @Override
    public String describe() {
        return "setting up a mob run";
    }
}
