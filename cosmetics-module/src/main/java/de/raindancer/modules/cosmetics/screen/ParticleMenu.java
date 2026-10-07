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
import de.raindancer.modules.cosmetics.model.ParticleSpeed;
import de.raindancer.modules.cosmetics.service.NameStyleService;
import de.raindancer.modules.cosmetics.service.ParticleSlot;
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
    private final ParticleSlot slot;

    public ParticleMenu(CosmeticsServices services, Player viewer, Menu parent) {
        this(services, viewer, parent, services.particles());
    }

    /** The same page for another particle — the one around you while a teleport waits. */
    public ParticleMenu(CosmeticsServices services, Player viewer, Menu parent, ParticleSlot slot) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.slot = slot;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>" + slot.heading());
    }

    @Override
    public String breadcrumb() {
        return slot.isWorn() ? "Particles" : slot.heading();
    }

    static Material iconOf(String particle) {
        Material material = Material.getMaterial(ParticleCatalogue.iconFor(particle));
        return material == null ? Material.GLASS : material;
    }

    @Override
    protected void render() {
        ParticleSlot particles = slot;
        ParticleChoice choice = particles.current(viewer);
        boolean may = particles.mayUse(viewer);

        band(MenuLayout.WHO, 2, may,
                Icons.of(Material.BLAZE_POWDER, "<green>Pick a particle",
                        "<gray>Every vanilla particle that can be worn.", "",
                        "<dark_gray>Left-click one to see it, right-click to wear it."),
                particles.locked(),
                click -> new ParticleChooser(viewer, services.brand(), this, "Pick a particle",
                        particle -> particles.wear(viewer, particle), particles.catalogue()).open());

        band(MenuLayout.WHO, 4, choice.isNone()
                ? Icons.of(Material.GLASS_BOTTLE, "<gray>No particle", "<dark_gray>Pick one on the left.")
                : Icons.of(iconOf(choice.particle()), "<white>" + ParticleCatalogue.readable(choice.particle()),
                        "<gray>" + choice.shape().title() + ".",
                        "<dark_gray>Everybody near you sees it,", "<dark_gray>unless they turned particles off."));

        band(MenuLayout.WHO, 6, !choice.isNone(),
                Icons.of(Material.BUCKET, "<white>" + particles.takeOffTitle(), "<gray>No particle of your own any more."),
                "Not wearing one",
                click -> {
                    particles.takeOff(viewer);
                    refresh();
                });

        int column = 2;
        for (ParticleShape shape : ParticleShape.values()) {
            if (shape.isWings()) {
                continue;
            }
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
        boolean winged = choice.shape().isWings() && !choice.isNone();
        band(MenuLayout.RULES, column, !choice.isNone(),
                Icons.of(winged ? Material.ELYTRA : Material.FEATHER,
                        (winged ? "<green>" : "<white>") + (winged ? choice.shape().title() : "Wings…"),
                        winged ? "<green>Drawn like this now." : "<gray>Angel, bat, butterfly or hummingbird.",
                        "", "<dark_gray>Click to pick a kind."),
                "Pick a particle first",
                click -> new WingsMenu(services, viewer, this, particles).open());

        boolean coloured = !choice.isNone() && ParticleShows.takesColour(choice.particle());
        String colourName = choice.colour() == null ? "" : services.offered().nameOf(TextColor.color(choice.colour()));
        ParticleDensity density = particles.densityOf(viewer);
        String capped = particles.isCapped(density)
                ? "<yellow>This server draws at most a little less than that." : "<dark_gray>Drawn as chosen.";
        boolean mayUltra = particles.mayUltra(viewer);
        band(MenuLayout.LAND, 3, !choice.isNone(),
                Icons.of(Material.GLOWSTONE_DUST, "<white>Density: " + density.title(),
                        "<gray>How finely the shape is drawn.", capped,
                        mayUltra ? "<light_purple>Ultra is yours: wings get their bones and quills."
                                : "<dark_gray>Ultra needs " + PermissionNodes.PARTICLES_ULTRA + ".",
                        "", "<dark_gray>Click for denser, right click for lighter."),
                "Pick a particle first",
                click -> {
                    particles.density(viewer, click.isRightClick() ? density.lighter() : density.denser());
                    refresh();
                });

        ParticleSpeed speed = particles.speedOf(viewer);
        band(MenuLayout.LAND, 5, !choice.isNone() && particles.hasSpeed(),
                Icons.of(Material.SUGAR, "<white>Speed: " + speed.title(),
                        "<gray>How fast it moves round you.", "",
                        "<dark_gray>Click for faster, right click for slower."),
                particles.hasSpeed() ? "Pick a particle first" : "A teleport's particles move at one speed",
                click -> {
                    particles.speed(viewer, click.isRightClick() ? speed.slower() : speed.faster());
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

        String gradientName = choice.colourTo() == null ? ""
                : services.offered().nameOf(TextColor.color(choice.colourTo()));
        band(MenuLayout.LAND, 6, coloured,
                Icons.of(Material.PRISMARINE_CRYSTALS, choice.colourTo() == null || !coloured
                                ? "<white>Gradient"
                                : MINI.serialize(NameStyleService.painted("Gradient to " + gradientName,
                                NameStyle.NONE.withColour(TextColor.color(choice.colourTo())))),
                        "<gray>Blends from your colour into this one",
                        "<gray>along the shape — spine to tip on wings.", "",
                        "<dark_gray>Click to pick the colour it ends in.",
                        "<dark_gray>Right click for one colour again."),
                "This particle has no colour",
                click -> {
                    if (click.isRightClick()) {
                        particles.colourTo(viewer, null);
                        refresh();
                        return;
                    }
                    new ColourPickMenu(services, viewer, this,
                            colour -> particles.colourTo(viewer, colour.value())).open();
                });

        if (!particles.isWorn()) {
            return;
        }
        boolean sees = services.particles().sees(viewer);
        band(MenuLayout.LAND, 7, Icons.of(sees ? Material.ENDER_EYE : Material.ENDER_PEARL,
                        sees ? "<green>You see other players' particles" : "<gray>You do not see other players' particles",
                        "<gray>Only changes what you see.", "", "<dark_gray>Click to switch."),
                click -> {
                    services.particles().toggleSeeing(viewer);
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
