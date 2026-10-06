package de.raindancer.modules.cosmetics.screen;

import de.raindancer.core.ui.choose.ParticleCatalogue;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.text.NameStyle;
import de.raindancer.modules.cosmetics.CosmeticsServices;
import de.raindancer.modules.cosmetics.model.ParticleChoice;
import de.raindancer.modules.cosmetics.service.NameStyleService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** {@code /cosmetics}: the doors to your name and your particle, each showing what you wear now. */
public final class CosmeticsMenu extends Menu implements ICosmeticsScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final CosmeticsServices services;

    public CosmeticsMenu(CosmeticsServices services, Player viewer) {
        super(viewer, services.brand(), null);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Cosmetics");
    }

    @Override
    public String breadcrumb() {
        return "Cosmetics";
    }

    @Override
    protected void render() {
        NameStyle style = services.names().current(viewer.getUniqueId());
        band(MenuLayout.WHO, 3, Icons.head(viewer.getUniqueId(),
                        MINI.serialize(NameStyleService.painted(viewer.getName(), style)),
                        "<gray>Your name", "<dark_gray>" + services.names().describe(style), "",
                        "<dark_gray>Click to change it."),
                click -> new NameStyleMenu(services, viewer, this).open());

        ParticleChoice particle = services.particles().current(viewer);
        Material icon = particle.isNone() ? Material.GLASS_BOTTLE
                : ParticleMenu.iconOf(particle.particle());
        band(MenuLayout.WHO, 5, Icons.of(icon, "<white>Particles",
                        particle.isNone() ? "<gray>None right now."
                                : "<gray>" + ParticleCatalogue.readable(particle.particle()) + ", "
                                        + particle.shape().title().toLowerCase(java.util.Locale.ROOT) + ".",
                        "", "<dark_gray>Click to pick one."),
                click -> new ParticleMenu(services, viewer, this).open());
    }

    @Override
    protected List<String> helpLines() {
        return services.messages().lines("cosmetics.help-hub").stream().map(MINI::serialize).toList();
    }

    @Override
    public String describe() {
        return "the cosmetics, and what you wear now";
    }
}
