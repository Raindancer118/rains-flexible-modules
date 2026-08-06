package de.raindancer.modules.hungergames.screen;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.service.DeathmatchService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The deathmatch's one button that matters, and the one that calls it off.
 *
 * <h2>Why calling it off is not confirmed and calling it is</h2>
 * A cancelled warning leaves the round exactly as it was — nobody has moved, the border has not started
 * shrinking. Starting it is one of this module's four irreversible public actions: the border commits to a
 * target size the moment the warning runs out, in front of everybody watching it tick down.
 *
 * <h2>Why the settings arrive as a {@link Supplier} rather than a field</h2>
 * {@link DeathmatchService} takes a fresh {@code HungerGamesSettings} through
 * {@link de.raindancer.modules.hungergames.service.IHungerGamesService#settings} on every reload but never
 * hands one back out — it has no getter, on purpose, since nothing about its own state machine needs to
 * read one. This page does, to show what the border is about to be told, so the settings arrive as the
 * same read seam {@code GameTimerService} takes for the border-phase list.
 */
public final class DeathmatchMenu extends Menu implements IHungerGamesScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final DeathmatchService deathmatch;
    private final Supplier<HungerGamesSettings> settings;

    public DeathmatchMenu(Player viewer, Brand brand, Menu parent, DeathmatchService deathmatch,
                          Supplier<HungerGamesSettings> settings) {
        super(viewer, brand, parent, 4);
        this.deathmatch = deathmatch;
        this.settings = settings;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_red>Deathmatch");
    }

    @Override
    public String breadcrumb() {
        return "Deathmatch";
    }

    @Override
    protected void render() {
        HungerGamesSettings config = settings.get();
        DeathmatchService.State state = deathmatch.state();

        List<String> statusLore = new ArrayList<>();
        statusLore.add("<gray>State: " + state);
        statusLore.add("<gray>Border target: " + config.deathmatchTargetBorderSize() + " blocks");
        statusLore.add("<gray>Warning: " + config.deathmatchWarningSeconds() + "s");
        statusLore.add("<gray>Teleport to centre: " + (config.deathmatchTeleportToCenter() ? "yes" : "no"));
        set(4, Icons.of(Material.NETHERITE_SWORD, "<dark_red>Deathmatch", statusLore));

        if (state == DeathmatchService.State.IDLE) {
            boolean enabled = config.deathmatchEnabled();
            var button = Icons.of(enabled ? Material.RED_CONCRETE : Material.GRAY_DYE,
                    (enabled ? "<red>" : "<dark_gray>") + "Start the deathmatch",
                    enabled
                            ? "<gray>Runs the warning (" + config.deathmatchWarningSeconds() + "s), then "
                            + "shrinks the border to " + config.deathmatchTargetBorderSize() + " blocks."
                            : "<gray>Disabled (deathmatch.enabled).");
            danger(enabled ? button : Icons.locked(button, "Disabled in the settings"), click -> {
                if (!enabled) {
                    return;
                }
                new ConfirmScreen(viewer, brand(), this, "<red>Start the deathmatch?",
                        List.of("<gray>The border begins shrinking the moment the warning ends.",
                                "<gray>Every tribute watching sees it start.",
                                "<dark_gray>Can only be called off during the warning."),
                        () -> {
                            deathmatch.start();
                            refresh();
                        }).open();
            });
        } else if (state == DeathmatchService.State.WARNING) {
            set(13, Icons.of(Material.YELLOW_CONCRETE, "<yellow>Cancel the warning",
                            "<gray>Nothing has moved yet — this undoes it cleanly."),
                    click -> {
                        deathmatch.cancel();
                        refresh();
                    });
        } else {
            set(13, Icons.of(Material.GRAY_DYE, "<dark_gray>Already active",
                    "<gray>The border has committed — there is nothing left to cancel."));
        }
    }

    @Override
    public String describe() {
        return "the deathmatch: state, the one irreversible start, and the warning's cancel";
    }
}
