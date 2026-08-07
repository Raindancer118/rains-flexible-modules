package de.raindancer.modules.hungergames.screen;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.choose.MobChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.hungergames.service.MonsterWaveService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Monster waves, spawned at wherever the viewer is standing.
 *
 * <h2>Why the mob choice is Core's {@link MobChooser} rather than a curated row of buttons</h2>
 * The source engine hard-coded six quick buttons — zombies, skeletons, spiders, creepers, blazes,
 * piglin brutes — and anything else needed the command line. {@link MobChooser#toFight} already knows
 * every hostile, spawnable entity type on this server and how to group them, so this page offers the one
 * genuinely common case (start with the configured default mob) as a single button and reaches for Core's
 * chooser for everything else, rather than maintaining a second, smaller copy of the catalogue it already
 * has.
 */
public final class MonsterWaveMenu extends Menu implements IHungerGamesScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final MonsterWaveService monsterWaves;
    private final Supplier<Duration> elapsedNow;

    /**
     * @param elapsedNow the round's elapsed time right now — {@code VirtualTime::elapsed}, in practice.
     *                    A wave started at {@code Duration.ZERO} instead has every pack's due time computed
     *                    relative to the round's beginning rather than to this moment, so the first tick
     *                    after starting one mid-round finds every pack already overdue and fires them all
     *                    in one burst instead of spreading them out.
     */
    public MonsterWaveMenu(Player viewer, Brand brand, Menu parent, MonsterWaveService monsterWaves,
                           Supplier<Duration> elapsedNow) {
        super(viewer, brand, parent, 4);
        this.monsterWaves = monsterWaves;
        this.elapsedNow = elapsedNow;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<red>Monster Waves");
    }

    @Override
    public String breadcrumb() {
        return "Monster Waves";
    }

    @Override
    protected void render() {
        set(4, Icons.of(Material.ZOMBIE_HEAD, "<red>Monster waves",
                "<gray>Default: " + monsterWaves.defaultWaves() + " wave(s) of "
                        + monsterWaves.defaultCount() + " " + monsterWaves.defaultMob(),
                "<gray>Every " + monsterWaves.defaultInterval() + "s",
                "<gray>Running series: " + monsterWaves.activeSeries(),
                "",
                "<dark_gray>Spawns at your position."));

        set(10, Icons.of(Material.ZOMBIE_SPAWN_EGG, "<green>Start here (default mob)",
                        "<gray>" + monsterWaves.defaultMob() + " · " + monsterWaves.defaultWaves()
                                + " wave(s)"),
                click -> startHere(monsterWaves.defaultMob()));

        set(12, Icons.of(Material.SPYGLASS, "<green>Start here (choose a mob)",
                        "<gray>Every hostile mob on this server."),
                click -> MobChooser.toFight(viewer, brand(), this, "Monster wave", this::startHere).open());

        set(16, Icons.of(Material.BARRIER, "<red>Stop every wave",
                        "<gray>" + monsterWaves.activeSeries() + " running series."),
                click -> {
                    monsterWaves.stopAll();
                    refresh();
                });
    }

    private void startHere(String mob) {
        monsterWaves.start(viewer.getLocation(), mob, monsterWaves.defaultCount(),
                monsterWaves.defaultWaves(), monsterWaves.defaultInterval(), viewer.getName(),
                elapsedNow.get());
        refresh();
    }

    @Override
    public String describe() {
        return "monster waves, started wherever the viewer is standing";
    }
}
