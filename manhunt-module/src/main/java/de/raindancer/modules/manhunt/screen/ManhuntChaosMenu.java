package de.raindancer.modules.manhunt.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.manhunt.ManhuntServices;
import de.raindancer.modules.manhunt.model.ChaosAction;
import de.raindancer.modules.manhunt.service.ChaosService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * One button per {@link ChaosAction} — a Runner's or an admin's own console command
 * ({@code /manhunt chaos <action>}) does exactly the same thing this does, through the same
 * {@link ChaosService}; this is the GUI half of the request, not a second implementation of it.
 */
public final class ManhuntChaosMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final Material[] ICONS = {
            Material.ENDER_PEARL,      // SWAP_POSITIONS
            Material.GLOW_INK_SAC,     // REVEAL_RUNNERS
            Material.SUGAR,            // HASTE_HUNTERS
            Material.HONEY_BOTTLE,     // SLOW_RUNNERS
            Material.TRIDENT,          // LIGHTNING_ON_A_RUNNER
            Material.PRISMARINE_SHARD, // FLIP_WEATHER
    };

    private final ManhuntServices services;

    public ManhuntChaosMenu(ManhuntServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_red>Chaos");
    }

    @Override
    public String breadcrumb() {
        return "Chaos";
    }

    @Override
    protected void render() {
        ChaosService chaos = services.chaos();
        ChaosAction[] actions = ChaosAction.values();
        int[] columns = {1, 2, 3, 4, 5, 6};

        for (int i = 0; i < actions.length && i < columns.length; i++) {
            ChaosAction action = actions[i];
            Material icon = i < ICONS.length ? ICONS[i] : Material.FIREWORK_STAR;
            band(MenuLayout.WHO, columns[i], Icons.of(icon, "<gold>" + action.label(),
                            "<gray>" + action.description(),
                            "<dark_gray>Click to throw this at the running hunt."),
                    click -> {
                        ChaosService.Result result = chaos.apply(action);
                        services.messages().send(viewer,
                                "manhunt.chaos." + result.name().toLowerCase(Locale.ROOT),
                                "action", action.label());
                        refresh();
                    });
        }
    }

    public String describe() {
        return "one button per chaos action, throwing it at whatever hunt is running";
    }
}
