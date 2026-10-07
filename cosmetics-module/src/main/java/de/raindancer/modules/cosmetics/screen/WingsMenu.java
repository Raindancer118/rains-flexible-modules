package de.raindancer.modules.cosmetics.screen;

import de.raindancer.core.ui.effect.ParticleShape;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.cosmetics.CosmeticsServices;
import de.raindancer.modules.cosmetics.service.ParticleSlot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/** The kinds of wings, for the particle page's shape row — there are too many shapes for one row. */
public final class WingsMenu extends Menu implements ICosmeticsScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final Map<ParticleShape, Material> ICONS = Map.of(
            ParticleShape.WINGS, Material.FEATHER,
            ParticleShape.BAT_WINGS, Material.PHANTOM_MEMBRANE,
            ParticleShape.BUTTERFLY_WINGS, Material.PINK_PETALS,
            ParticleShape.HUMMINGBIRD_WINGS, Material.SWEET_BERRIES);

    private static final Map<ParticleShape, String> ABOUT = Map.of(
            ParticleShape.WINGS, "<gray>Feathered, beating slowly.",
            ParticleShape.BAT_WINGS, "<gray>Pointed, with the finger bones showing.",
            ParticleShape.BUTTERFLY_WINGS, "<gray>Two round lobes a side, fluttering.",
            ParticleShape.HUMMINGBIRD_WINGS, "<gray>Long and narrow, beating in a blur.");

    private final ParticleSlot slot;

    public WingsMenu(CosmeticsServices services, Player viewer, Menu parent, ParticleSlot slot) {
        super(viewer, services.brand(), parent, 3);
        this.slot = slot;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Wings");
    }

    @Override
    public String breadcrumb() {
        return "Wings";
    }

    @Override
    protected void render() {
        ParticleShape now = slot.current(viewer).shape();
        int column = 1;
        for (ParticleShape kind : ParticleShape.wings()) {
            boolean on = kind == now;
            band(MenuLayout.WHO, column, Icons.of(ICONS.getOrDefault(kind, Material.FEATHER),
                            (on ? "<green>" : "<white>") + kind.title(),
                            List.of(ABOUT.getOrDefault(kind, ""), "",
                                    on ? "<green>Drawn like this now." : "<gray>Click to wear these.",
                                    "<dark_gray>Ultra density adds the bones and quills inside.")),
                    click -> {
                        slot.shape(viewer, kind);
                        leave();
                    });
            column += 2;
        }
    }

    @Override
    public String describe() {
        return "the kinds of wings";
    }
}
