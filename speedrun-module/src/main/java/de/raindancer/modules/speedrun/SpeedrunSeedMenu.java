package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

/**
 * Where the next world comes from: a new seed each time, always one, or one of a pool — and the
 * seed of the world there is now, to replay or to copy. Set-seed and replayed runs are ranked apart
 * from random ones automatically.
 */
public final class SpeedrunSeedMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final SpeedrunLobby lobby;
    private final SpeedrunActions actions;

    public SpeedrunSeedMenu(SpeedrunLobby lobby, Player viewer, Menu parent) {
        super(viewer, SpeedrunScreens.brandOf(lobby), parent);
        this.lobby = lobby;
        this.actions = new SpeedrunActions(lobby);
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Seeds");
    }

    /** Every click asks again: a page left open outlives a permission taken away in between. */
    @Override
    public void handleClick(InventoryClickEvent event) {
        if (!SpeedrunAccess.SEEDS.allows(lobby, viewer)) {
            event.setCancelled(true);
            viewer.closeInventory();
            return;
        }
        super.handleClick(event);
    }

    @Override
    public String breadcrumb() {
        return "Seeds";
    }

    @Override
    protected void render() {
        SpeedrunSettings config = lobby.config();
        World world = Bukkit.getWorld(config.worldName());
        SpeedrunHistory history = lobby.toolkit().map(SpeedrunToolkit::history).orElse(null);
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.WHEAT_SEEDS,
                "<white>This world: " + (world == null ? "not loaded" : String.valueOf(world.getSeed())),
                world == null ? "<gray>No lobby world to read a seed from."
                        : "<gray>" + SpeedrunSeeds.typeOf(world.getSeed(), config, history).label()
                        + " — ranked as such.",
                "<dark_gray>Click to get it in chat, to copy."),
                click -> {
                    if (world != null) {
                        viewer.closeInventory();
                        lobby.toolkit().map(SpeedrunToolkit::messages).ifPresent(messages ->
                                messages.send(viewer, "speedrun.seed.this-world", "seed", String.valueOf(world.getSeed())));
                    }
                });
        mode(1, SpeedrunSeedMode.RANDOM, Material.ENDER_EYE, "A new seed every time",
                "<gray>Nobody has seen the next map.");
        mode(3, SpeedrunSeedMode.FIXED, Material.FILLED_MAP, "Always the same seed",
                "<gray>Seed: <white>" + (config.seed().isBlank() ? "not set" : SpeedrunScreens.text(config.seed())));
        mode(5, SpeedrunSeedMode.POOL, Material.CHEST, "One of a pool",
                "<gray>" + SpeedrunSeeds.pool(config.seedPool()).size() + " seed(s) in the pool");
        band(MenuLayout.LAND, 2, Icons.of(Material.NAME_TAG, "<white>Type the seed",
                        "<gray>A number, or a word like the create-world", "<gray>screen takes. Also sets FIXED."),
                click -> actions.ask(viewer, "speedrun.seed.ask", typed -> {
                    if (!SpeedrunAccess.SEEDS.allows(lobby, viewer)) {
                        return;
                    }
                    lobby.settings().set("seed", typed);
                    lobby.settings().set("seed-mode", SpeedrunSeedMode.FIXED.name());
                    lobby.toolkit().map(SpeedrunToolkit::messages)
                            .ifPresent(messages -> messages.send(viewer, "speedrun.seed.set", "seed", typed));
                    open();
                }));
        band(MenuLayout.LAND, 4, Icons.of(Material.BUNDLE, "<white>Type the pool",
                        "<gray>Seeds separated by commas.", "<gray>Also sets POOL."),
                click -> actions.ask(viewer, "speedrun.seed.ask-pool", typed -> {
                    if (!SpeedrunAccess.SEEDS.allows(lobby, viewer)) {
                        return;
                    }
                    lobby.settings().set("seed-pool", typed);
                    lobby.settings().set("seed-mode", SpeedrunSeedMode.POOL.name());
                    lobby.toolkit().map(SpeedrunToolkit::messages).ifPresent(messages -> messages.send(viewer,
                            "speedrun.seed.pool-set", "count", String.valueOf(SpeedrunSeeds.pool(typed).size())));
                    open();
                }));
        boolean replaying = lobby.replayingSeed();
        band(MenuLayout.LAND, 6, Icons.of(replaying ? Material.LIME_DYE : Material.RECOVERY_COMPASS,
                        replaying ? "<green>Same seed at the next reset" : "<white>Same seed again",
                        "<gray>The next reset remakes this exact map,",
                        "<gray>once. It is ranked as a set seed.",
                        replaying ? "<dark_gray>Already on." : "<dark_gray>Click to do it."),
                click -> {
                    lobby.replaySeedNextReset();
                    refresh();
                });
    }

    private void mode(int column, SpeedrunSeedMode mode, Material icon, String name, String detail) {
        boolean on = lobby.config().seedMode() == mode;
        band(MenuLayout.WHO, column, Icons.of(on ? Material.LIME_DYE : icon, (on ? "<green>" : "<white>") + name,
                        detail, on ? "<dark_gray>In use." : "<dark_gray>Click to use this."),
                click -> {
                    lobby.settings().set("seed-mode", mode.name());
                    refresh();
                });
    }
}
