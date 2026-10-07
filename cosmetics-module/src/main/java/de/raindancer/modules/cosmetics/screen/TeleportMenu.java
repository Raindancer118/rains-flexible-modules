package de.raindancer.modules.cosmetics.screen;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.choose.SoundChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.cosmetics.CosmeticsServices;
import de.raindancer.modules.cosmetics.model.TeleportLookChoice;
import de.raindancer.modules.cosmetics.model.TeleportPart;
import de.raindancer.modules.cosmetics.service.TeleportLookService;
import de.raindancer.modules.cosmetics.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Your teleports: the sound as you set off, the sound as you land, and the particles while you wait —
 * one row each, with the server's own and nothing at all beside every one. Every word is in messages.yml.
 */
public final class TeleportMenu extends Menu implements ICosmeticsScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final CosmeticsServices services;

    public TeleportMenu(CosmeticsServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    private String words(String key, Object... values) {
        return MINI.serialize(services.messages().get(key, values));
    }

    @Override
    protected Component title() {
        return services.messages().get("cosmetics.teleport.menu.title");
    }

    @Override
    public String breadcrumb() {
        return services.messages().raw("cosmetics.teleport.menu.breadcrumb");
    }

    @Override
    protected void render() {
        TeleportLookService looks = services.teleports();
        TeleportLookChoice choice = looks.current(viewer);
        boolean may = looks.mayUse(viewer);
        String locked = words("cosmetics.teleport.menu.locked", "permission", PermissionNodes.TELEPORT);
        // In the order they happen: setting off, the countdown, standing in the particles, landing.
        button(MenuLayout.WHO, 2, TeleportPart.DEPART, Material.NOTE_BLOCK, choice, may, locked);
        button(MenuLayout.WHO, 6, TeleportPart.TICK, Material.CLOCK, choice, may, locked);
        button(MenuLayout.RULES, 2, TeleportPart.WAIT, Material.ENDER_EYE, choice, may, locked);
        button(MenuLayout.RULES, 6, TeleportPart.ARRIVE, Material.BELL, choice, may, locked);
    }

    /** One part: left-click picks, right-click puts the server's back, shift-click makes it nothing. */
    private void button(int band, int column, TeleportPart part, Material icon, TeleportLookChoice choice,
                        boolean may, String locked) {
        TeleportLookService looks = services.teleports();
        String name = looks.partName(part);
        List<String> lore = new ArrayList<>();
        lore.add(words("cosmetics.teleport.menu.now", "value", looks.readable(part, choice.of(part))));
        lore.add(words(switch (part) {
            case DEPART -> "cosmetics.teleport.menu.about.depart";
            case ARRIVE -> "cosmetics.teleport.menu.about.arrive";
            case WAIT -> "cosmetics.teleport.menu.about.wait";
            case TICK -> "cosmetics.teleport.menu.about.tick";
        }));
        lore.add("");
        lore.add(words("cosmetics.teleport.menu.pick-hint"));
        lore.add(words("cosmetics.teleport.menu.servers-hint"));
        lore.add(words("cosmetics.teleport.menu.nothing-hint"));
        band(band, column, may, Icons.of(icon, words("cosmetics.teleport.menu.pick", "part", name), lore), locked,
                click -> {
                    if (click.isShiftClick()) {
                        chooseAndRedraw(part, TeleportLookChoice.NONE);
                    } else if (click.isRightClick()) {
                        chooseAndRedraw(part, null);
                    } else {
                        pick(part);
                    }
                });
    }

    private void pick(TeleportPart part) {
        TeleportLookService looks = services.teleports();
        String heading = words("cosmetics.teleport.menu.pick", "part", looks.partName(part));
        if (part.isSound()) {
            new SoundChooser(viewer, services.brand(), this, heading,
                    sound -> chooseLater(part, sound), looks.sounds(viewer)).open();
        } else {
            // The same page as the particle you wear: pick it, then its shape, colour and density.
            new ParticleMenu(services, viewer, this, looks.waitParticle()).open();
        }
    }

    private void chooseAndRedraw(TeleportPart part, String value) {
        services.teleports().choose(viewer, part, value);
        refresh();
    }

    /** The chooser closes itself before answering; this page comes back a tick later, showing the choice. */
    private void chooseLater(TeleportPart part, String value) {
        services.teleports().choose(viewer, part, value);
        Scheduling.entityLater(services.plugin(), viewer, 1L, this::reopen);
    }

    @Override
    protected List<String> helpLines() {
        return services.messages().lines("cosmetics.help-teleport").stream().map(MINI::serialize).toList();
    }

    @Override
    public String describe() {
        return "your teleport sounds and waiting particles";
    }
}
