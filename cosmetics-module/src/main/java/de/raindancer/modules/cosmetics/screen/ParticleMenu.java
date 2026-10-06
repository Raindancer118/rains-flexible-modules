package de.raindancer.modules.cosmetics.screen;

import de.raindancer.core.ui.choose.ParticleCatalogue;
import de.raindancer.core.ui.choose.ParticleChooser;
import de.raindancer.core.ui.effect.ParticleShape;
import de.raindancer.core.ui.effect.ParticleShows;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.text.NameStyle;
import de.raindancer.modules.cosmetics.CosmeticsServices;
import de.raindancer.modules.cosmetics.model.ParticleChoice;
import de.raindancer.modules.cosmetics.model.ParticleDensity;
import de.raindancer.modules.cosmetics.service.NameStyleService;
import de.raindancer.modules.cosmetics.service.ParticleService;
import de.raindancer.modules.cosmetics.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Your particle: pick one of the vanilla particles (Core's chooser — left-click previews it, right-click
 * takes it), the shape it is drawn in, a colour where the particle takes one, and whether you see
 * other people's.
 */
public final class ParticleMenu extends Menu implements ICosmeticsScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final CosmeticsServices services;

    public ParticleMenu(CosmeticsServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Your particles");
    }

    @Override
    public String breadcrumb() {
        return "Particles";
    }

    static Material iconOf(String particle) {
        Material material = Material.getMaterial(ParticleCatalogue.iconFor(particle));
        return material == null ? Material.GLASS : material;
    }

    @Override
    protected void render() {
        ParticleService particles = services.particles();
        ParticleChoice choice = particles.current(viewer);
        boolean may = particles.mayUse(viewer);

        band(MenuLayout.WHO, 2, may,
                Icons.of(Material.BLAZE_POWDER, "<green>Pick a particle",
                        "<gray>Every vanilla particle that can be worn.", "",
                        "<dark_gray>Left-click one to see it, right-click to wear it."),
                "Needs " + PermissionNodes.PARTICLES,
                click -> new ParticleChooser(viewer, services.brand(), this, "Pick a particle",
                        particle -> particles.wear(viewer, particle, false),
                        new ParticleCatalogue(particles::offered)).open());

        band(MenuLayout.WHO, 4, choice.isNone()
                ? Icons.of(Material.GLASS_BOTTLE, "<gray>No particle", "<dark_gray>Pick one on the left.")
                : Icons.of(iconOf(choice.particle()), "<white>" + ParticleCatalogue.readable(choice.particle()),
                        "<gray>" + choice.shape().title() + ".",
                        "<dark_gray>Everybody near you sees it,", "<dark_gray>unless they turned particles off."));

        band(MenuLayout.WHO, 6, !choice.isNone(),
                Icons.of(Material.BUCKET, "<white>Take it off", "<gray>No particle any more."),
                "Not wearing one",
                click -> {
                    particles.takeOff(viewer, false);
                    refresh();
                });

        int column = 2;
        for (ParticleShape shape : ParticleShape.values()) {
            boolean on = shape == choice.shape() && !choice.isNone();
            band(MenuLayout.RULES, column, !choice.isNone(),
                    Icons.of(on ? Material.LIME_DYE : Material.GRAY_DYE, (on ? "<green>" : "<white>") + shape.title(),
                            on ? "<green>Drawn like this now." : "<gray>Click to draw it like this."),
                    "Pick a particle first",
                    click -> {
                        particles.shape(viewer, shape);
                        refresh();
                    });
            column++;
        }

        boolean coloured = !choice.isNone() && ParticleShows.takesColour(choice.particle());
        String colourName = choice.colour() == null ? "" : services.offered().nameOf(TextColor.color(choice.colour()));
        ParticleDensity density = particles.densityOf(viewer);
        String capped = particles.isCapped(density)
                ? "<yellow>This server draws at most a little less than that." : "<dark_gray>Drawn as chosen.";
        band(MenuLayout.LAND, 3, !choice.isNone(),
                Icons.of(Material.GLOWSTONE_DUST, "<white>Density: " + density.title(),
                        "<gray>How many particles at a time.", capped, "",
                        "<dark_gray>Click for denser, right click for lighter."),
                "Pick a particle first",
                click -> {
                    particles.density(viewer, click.isRightClick() ? density.lighter() : density.denser());
                    refresh();
                });

        band(MenuLayout.LAND, 4, !choice.isNone(),
                Icons.of(Material.SPYGLASS, "<white>Preview",
                        "<gray>Closes this and draws your particle", "<gray>in front of you for a moment.", "",
                        "<dark_gray>Click to see it."),
                "Pick a particle first",
                click -> {
                    viewer.closeInventory();
                    particles.preview(viewer);
                });

        band(MenuLayout.LAND, 2, coloured,
                Icons.of(Material.RED_DYE, coloured
                                ? MINI.serialize(NameStyleService.painted("Colour: " + colourName,
                                NameStyle.NONE.withColour(TextColor.color(choice.colour()))))
                                : "<white>Colour",
                        "<gray>For dust and the tinted particles.", "", "<dark_gray>Click to pick one."),
                "This particle has no colour",
                click -> new ColourPickMenu(services, viewer, this,
                        colour -> particles.colour(viewer, colour.value())).open());

        boolean sees = particles.sees(viewer);
        band(MenuLayout.LAND, 6, Icons.of(sees ? Material.ENDER_EYE : Material.ENDER_PEARL,
                        sees ? "<green>You see other players' particles" : "<gray>You do not see other players' particles",
                        "<gray>Only changes what you see.", "", "<dark_gray>Click to switch."),
                click -> {
                    particles.toggleSeeing(viewer);
                    refresh();
                });
    }

    @Override
    protected List<String> helpLines() {
        return services.messages().lines("cosmetics.help-particles").stream().map(MINI::serialize).toList();
    }

    @Override
    public String describe() {
        return "your particle, its shape and colour";
    }
}
