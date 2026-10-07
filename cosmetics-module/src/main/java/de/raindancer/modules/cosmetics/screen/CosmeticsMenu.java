package de.raindancer.modules.cosmetics.screen;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.choose.ParticleCatalogue;
import de.raindancer.core.ui.choose.PlayerChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.text.NameStyle;
import de.raindancer.modules.cosmetics.CosmeticsServices;
import de.raindancer.modules.cosmetics.model.ClearScope;
import de.raindancer.modules.cosmetics.model.ParticleChoice;
import de.raindancer.modules.cosmetics.service.NameStyleService;
import de.raindancer.modules.cosmetics.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

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
        band(MenuLayout.WHO, 1, Icons.head(viewer.getUniqueId(),
                        MINI.serialize(NameStyleService.painted(viewer.getName(), style)),
                        "<gray>Your name", "<dark_gray>" + services.names().describe(style), "",
                        "<dark_gray>Click to change it."),
                click -> new NameStyleMenu(services, viewer, this).open());

        ParticleChoice particle = services.particles().current(viewer);
        Material icon = particle.isNone() ? Material.GLASS_BOTTLE
                : ParticleMenu.iconOf(particle.particle());
        band(MenuLayout.WHO, 3, Icons.of(icon, "<white>Particles",
                        particle.isNone() ? "<gray>None right now."
                                : "<gray>" + ParticleCatalogue.readable(particle.particle()) + ", "
                                        + particle.shape().title().toLowerCase(java.util.Locale.ROOT) + ".",
                        "", "<dark_gray>Click to pick one."),
                click -> new ParticleMenu(services, viewer, this).open());

        ParticleChoice wings = services.particles().wings().current(viewer);
        band(MenuLayout.WHO, 5, Icons.of(wings.isNone() ? Material.FEATHER : Material.ELYTRA, "<white>Wings",
                        wings.isNone() ? "<gray>None right now."
                                : "<gray>" + wings.shape().title() + ", " + ParticleCatalogue.readable(wings.particle())
                                        .toLowerCase(java.util.Locale.ROOT) + ".",
                        "<dark_gray>Worn on top of your particle.", "", "<dark_gray>Click to pick some."),
                click -> new ParticleMenu(services, viewer, this, services.particles().wings()).open());

        band(MenuLayout.WHO, 7, Icons.of(Material.ENDER_PEARL,
                        MINI.serialize(services.messages().get("cosmetics.teleport.menu.door")),
                        MINI.serialize(services.messages().get("cosmetics.teleport.menu.door-lore"))),
                click -> new TeleportMenu(services, viewer, this).open());

        boolean mayClearOthers = services.clearing().may(viewer, false);
        toolbar(4, mayClearOthers,
                Icons.of(Material.SPONGE, "<white>Clear somebody else's",
                        "<gray>Takes their name style and particle off.",
                        "<gray>Pick them from the list; you are asked first."),
                "Needs " + PermissionNodes.CLEAR_OTHERS,
                click -> new PlayerChooser(viewer, services.brand(), this, "Clear whose cosmetics?",
                        java.util.List.of(), person -> confirmClearing(person.id())).open());

        boolean mayClear = services.clearing().may(viewer, true);
        boolean wearing = !style.isEmpty() || !particle.isNone() || !wings.isNone();
        ItemStack clearIcon = Icons.of(Material.BARRIER, "<red>Clear my cosmetics",
                "<gray>Takes your name style and your", "<gray>particle off.", "", "<dark_gray>Asks first.");
        danger(mayClear && wearing ? clearIcon
                        : Icons.locked(clearIcon, !mayClear ? "Needs " + PermissionNodes.CLEAR
                                : "You are not wearing anything"),
                click -> {
                    if (!mayClear || !wearing) {
                        return;
                    }
                    new ConfirmScreen(services, viewer, this, "Clear your cosmetics?",
                            List.of("<gray>Your name goes back to plain and", "<gray>your particle is taken off.",
                                    "<gray>Presets and colours have to be picked again."),
                            () -> services.clearing().clear(viewer, viewer, ClearScope.ALL)).open();
                });
    }

    /** Opened a tick late: the chooser goes back to this page right after answering, and would cover the question. */
    private void confirmClearing(UUID who) {
        OfflinePlayer target = services.server().getOfflinePlayer(who);
        String name = target.getName() == null ? who.toString() : target.getName();
        Scheduling.entityLater(services.plugin(), viewer, 1L, () ->
                new ConfirmScreen(services, viewer, this, "Clear " + name + "'s cosmetics?",
                        List.of("<gray>Their name goes back to plain and their",
                                "<gray>particle is taken off, if they are here.",
                                "<gray>It goes in the audit log."),
                        () -> services.clearing().clear(viewer, target, ClearScope.ALL)).open());
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
