package de.raindancer.modules.mannequin.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.mannequin.MannequinServices;
import de.raindancer.modules.mannequin.model.Mannequin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * One mannequin, and everything that can be done to it — the same shape as {@code homes-module}'s
 * {@code HomeEditMenu}: three doors ({@link LoadoutScreen}, {@link SkinScreen}, {@link
 * StatsScreen}) plus what belongs on the page itself.
 *
 * <h2>Why the mannequin is held by id rather than as a value</h2>
 * Everything on this page changes it, and a page holding the value it was opened with would redraw
 * the version from before the click — a combat listener recording a hit, or a second window
 * changing the loadout, both happen while this page could be sitting open. Looked up fresh on every
 * draw instead, so a mannequin removed from underneath it says so rather than offering buttons for
 * something that has gone.
 *
 * <h2>Two toggles live here, not behind their own screen</h2>
 * Shield-blocking and the redstone signal are booleans — "flag toggles are clicks" is this
 * project's own rule, the same reason a claim's flags are a click rather than a submenu. Before
 * this page existed, {@code Mannequin#emitsRedstoneSignal} could never actually be turned on by
 * anybody — the model and the pulse logic were both real, but nothing ever called {@code
 * withEmitsRedstoneSignal(true)}, so every mannequin's barrel stayed unplaced and every hit's pulse
 * silently did nothing. This is the fix.
 */
public final class MannequinEditMenu extends Menu implements IMannequinScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final MannequinServices services;
    private final String id;

    public MannequinEditMenu(MannequinServices services, Player viewer, Mannequin mannequin, Menu parent) {
        super(viewer, services.brand(), parent, 3);
        this.services = services;
        this.id = mannequin.id();
    }

    private Mannequin mannequin() {
        return services.registry().get(id).orElse(null);
    }

    @Override
    protected Component title() {
        Mannequin mannequin = mannequin();
        return MINI.deserialize("<dark_gray>" + (mannequin == null ? id : mannequin.displayName()));
    }

    @Override
    public String breadcrumb() {
        Mannequin mannequin = mannequin();
        return mannequin == null ? id : mannequin.displayName();
    }

    @Override
    protected void render() {
        Mannequin mannequin = mannequin();
        if (mannequin == null) {
            // Removed from under this page — from a command, or a second window. Offering buttons
            // for it would be offering buttons for nothing.
            band(MenuLayout.WHO, 4, Icons.of(Material.BARRIER, "<red>This mannequin is gone",
                    "<gray>It was removed while this page was open."));
            return;
        }

        set(MenuLayout.HEADER_SUBJECT, headerIcon(mannequin));

        band(MenuLayout.WHO, 1, Icons.of(Material.IRON_CHESTPLATE, "<white>Loadout",
                        "<gray>Armor, weapons and enchants.",
                        "",
                        "<gray>Click to open."),
                click -> new LoadoutScreen(services, viewer, mannequin, this).open());

        band(MenuLayout.WHO, 3, Icons.of(Material.PLAYER_HEAD, "<white>Skin",
                        "<gray>Whose face this mannequin wears.",
                        "",
                        "<gray>Click to open."),
                click -> new SkinScreen(services, viewer, mannequin, this).open());

        band(MenuLayout.WHO, 5, Icons.of(Material.PAPER, "<white>Stats",
                        "<gray>Damage, hits and combo history.",
                        "",
                        "<gray>Click to open."),
                click -> new StatsScreen(services, viewer, mannequin, this).open());

        band(MenuLayout.WHO, 7, Icons.of(Material.GOLDEN_APPLE, "<white>Health",
                        "<gray>Currently <white>"
                                + String.format(Locale.ROOT, "%.0f",
                                        mannequin.resolvedMaxHealth(services.config().maxHealthClamped()))
                                + "<gray> max HP.",
                        "",
                        "<gray>Click to open."),
                click -> new HealthScreen(services, viewer, mannequin, this).open());

        band(MenuLayout.RULES, 1, shieldIcon(mannequin), click -> {
            Mannequin updated = mannequin.withBlocksWithShield(!mannequin.blocksWithShield());
            services.mannequins().save(updated);
            refresh();
        });

        band(MenuLayout.RULES, 3, redstoneIcon(mannequin), click -> {
            boolean nowOn = !mannequin.emitsRedstoneSignal();
            Mannequin updated = mannequin.withEmitsRedstoneSignal(nowOn);
            services.mannequins().save(updated);
            services.mannequins().ensureBarrel(updated);
            if (nowOn) {
                // The barrel is easy to build a comparator against wrong: it sits one full block
                // under where the mannequin stands, not beside its feet at the surface — a
                // comparator one level too high reads nothing at all. Told in exact coordinates
                // because "underneath it" is not specific enough to get right on the first try.
                services.messages().send(viewer, "mannequin.redstone.barrel-at",
                        "x", updated.x(), "y", updated.barrelY(), "z", updated.z());
            }
            refresh();
        });

        danger(Icons.of(Material.TNT, "<red>Remove this mannequin",
                        "<gray>The entity, its loadout and its file all go.",
                        "",
                        "<gray>You will be asked first."),
                click -> new ConfirmScreen(services, viewer, this,
                        "<red>Remove " + mannequin.displayName() + "?",
                        List.of("<gray>Everything about it — its loadout, its skin and",
                                "<gray>its training tally — goes with it. You can",
                                "<gray>make another, but not this one back."),
                        () -> {
                            services.mannequins().remove(id);
                            // Back to the list: the page this came from is about a mannequin that
                            // is now gone.
                            if (parent() != null) {
                                parent().open();
                            } else {
                                viewer.closeInventory();
                            }
                        }).open());
    }

    private ItemStack headerIcon(Mannequin mannequin) {
        List<String> lore = List.of(
                "<gray>" + mannequin.world() + " " + mannequin.x() + " "
                        + mannequin.y() + " " + mannequin.z(),
                "<dark_gray>id " + mannequin.id());
        return mannequin.skinSource() != null
                ? Icons.head(mannequin.skinSource(), "<white>" + mannequin.displayName(), lore)
                : Icons.of(Material.ARMOR_STAND, "<white>" + mannequin.displayName(), lore);
    }

    private ItemStack shieldIcon(Mannequin mannequin) {
        boolean on = mannequin.blocksWithShield();
        return Icons.of(Material.SHIELD, (on ? "<green>" : "<gray>") + "Blocks with a shield",
                on ? "<gray>Raises a shield it holds against nearby attackers."
                        : "<gray>A shield in its off hand is never raised.",
                "",
                "<gray>Click to " + (on ? "turn off" : "turn on") + ".");
    }

    private ItemStack redstoneIcon(Mannequin mannequin) {
        boolean on = mannequin.emitsRedstoneSignal();
        if (!on) {
            return Icons.of(Material.COMPARATOR, "<gray>Redstone signal",
                    "<gray>No barrel is placed or read for this one.",
                    "",
                    "<gray>Click to turn on.");
        }
        return Icons.of(Material.COMPARATOR, "<green>Redstone signal",
                "<gray>A barrel one block directly under it",
                "<gray>pulses 0-15, scaled to how hard the last",
                "<gray>hit was — build a comparator beside the",
                "<gray>barrel itself, at its own height, not at",
                "<gray>the mannequin's feet.",
                "",
                "<gray>" + mannequin.world() + " " + mannequin.x() + " "
                        + mannequin.barrelY() + " " + mannequin.z(),
                "",
                "<gray>Click to turn off.");
    }

    @Override
    public String describe() {
        return "one mannequin, and everything that can be done to it";
    }
}
