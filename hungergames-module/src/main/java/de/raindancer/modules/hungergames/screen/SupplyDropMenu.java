package de.raindancer.modules.hungergames.screen;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.service.SupplyDropService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Supplier;

/**
 * Capitol supply drops: the schedule's status line, and a manual drop right now.
 *
 * <h2>Why there is no per-slot row here, unlike the source's schedule list</h2>
 * {@link SupplyDropService#schedule()} answers with a record nested inside the package-private
 * {@code EventEndpoints} — a type this screen cannot even name, by design: that record exists for the HTTP
 * API's JSON shape, not for a menu to draw rows from. {@link SupplyDropService#statusLine()} is the public,
 * screen-shaped answer to the same question ("how many of how many have gone out"), and it is what this
 * page shows instead of reinventing the per-slot detail Core has no stake in.
 *
 * <h2>Why a manual drop is not behind {@code danger()}</h2>
 * A supply drop is loot landing somewhere on the map with a warning first — inconvenient to have happened
 * by mistake, but nothing about it is one of the module's four irreversible public actions, and Core's
 * own crate is refillable. It still gets a confirmation, because "somewhere the console has to explain
 * moments later" is worth one extra click, just not the loudest slot on the page.
 */
public final class SupplyDropMenu extends Menu implements IHungerGamesScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final SupplyDropService supplyDrops;
    private final Supplier<HungerGamesSettings> settings;

    public SupplyDropMenu(Player viewer, Brand brand, Menu parent, SupplyDropService supplyDrops,
                          Supplier<HungerGamesSettings> settings) {
        super(viewer, brand, parent, 4);
        this.supplyDrops = supplyDrops;
        this.settings = settings;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<gold>Supply Drops");
    }

    @Override
    public String breadcrumb() {
        return "Supply Drops";
    }

    @Override
    protected void render() {
        HungerGamesSettings config = settings.get();
        boolean enabled = config.supplyDropsEnabled();

        set(4, Icons.of(Material.CHEST_MINECART, "<gold>Capitol supply drops",
                "<gray>" + supplyDrops.statusLine(),
                "<gray>Landing spread: " + config.supplyDropRadiusMin() + "-" + config.supplyDropRadiusMax()
                        + " blocks",
                "<gray>Warning: " + config.supplyDropWarningSeconds() + "s"));

        var button = Icons.of(enabled ? Material.FIREWORK_ROCKET : Material.GRAY_DYE,
                (enabled ? "<yellow>" : "<dark_gray>") + "Trigger a drop now",
                enabled
                        ? "<gray>Warns everybody, then lands after "
                        + config.supplyDropWarningSeconds() + "s."
                        : "<gray>Disabled (events.supply-drops.enabled).");
        set(11, enabled ? button : Icons.locked(button, "Disabled in the settings"),
                click -> {
                    if (!enabled) {
                        return;
                    }
                    new ConfirmScreen(viewer, brand(), this, "<yellow>Trigger a supply drop now?",
                            List.of("<gray>A warning goes out to everybody watching.",
                                    "<gray>Loot lands somewhere inside the current border."),
                            () -> {
                                supplyDrops.triggerNow(viewer.getName());
                                refresh();
                            }).open();
                });
    }

    @Override
    public String describe() {
        return "Capitol supply drops: status, and a manual drop right now";
    }
}
