package de.raindancer.modules.essentials.screen;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.ui.choose.AmountChooser;
import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.util.Enchantments;
import de.raindancer.modules.essentials.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * What bare {@code /enchant} opens: every enchantment the server knows, those that fit what is in the
 * hand first, the rest greyed with the reason. Clicking one asks for a level; a right click takes it off.
 *
 * <p>Every answer here comes from {@code EnchantService.check}, the same call the command makes, so a
 * button that looks live is one that works.
 */
public final class EnchantMenu extends PaginatedMenu<Enchantment> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final EssentialsServices services;

    public EnchantMenu(EssentialsServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Enchant");
    }

    @Override
    public String breadcrumb() {
        return "Enchant";
    }

    private ItemStack held() {
        return viewer.getInventory().getItemInMainHand();
    }

    private boolean holding() {
        return !held().isEmpty();
    }

    @Override
    protected List<Enchantment> entries() {
        if (!holding()) {
            return List.of();
        }
        List<Enchantment> fitting = new ArrayList<>();
        List<Enchantment> others = new ArrayList<>();
        for (Enchantment enchantment : Enchantments.all()) {
            (enchantment.canEnchantItem(held()) ? fitting : others).add(enchantment);
        }
        fitting.addAll(others);
        return fitting;
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BARRIER, "<gray>Your hands are empty",
                "<gray>Hold the item you want to enchant,", "<gray>then open this again.");
    }

    @Override
    protected ItemStack icon(Enchantment enchantment) {
        int present = levelOnItem(enchantment);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Vanilla maximum: <white>" + enchantment.getMaxLevel());
        lore.add(present > 0 ? "<green>On your item: " + present : "<gray>Not on your item yet");
        lore.add("");
        lore.add("<dark_gray>Click to choose a level.");
        if (present > 0) {
            lore.add("<dark_gray>Right click to take it off.");
        }
        ItemStack icon = Icons.of(present > 0 ? Material.ENCHANTED_BOOK : Material.BOOK,
                "<white>" + Enchantments.readable(enchantment), lore);
        Verdict verdict = services.enchanting().check(viewer, viewer, enchantment, 1);
        return verdict.isRefused() ? Icons.locked(icon, reasonFor(verdict)) : icon;
    }

    @Override
    protected void onClick(Enchantment enchantment, InventoryClickEvent event) {
        if (event.isRightClick() && levelOnItem(enchantment) > 0) {
            services.enchanting().apply(viewer, viewer, enchantment, 0);
            refresh();
            return;
        }
        Verdict verdict = services.enchanting().check(viewer, viewer, enchantment, 1);
        if (verdict.isRefused()) {
            services.messages().send(viewer, verdict.reason(), "detail", verdict.detail(),
                    "enchantment", Enchantments.readable(enchantment));
            return;
        }
        int ceiling = services.enchanting().ceiling(viewer, enchantment);
        new AmountChooser(viewer, services.brand(), this, Enchantments.readable(enchantment) + " level",
                Math.max(1, Math.min(levelOnItem(enchantment), ceiling)), 1, ceiling,
                level -> services.enchanting().apply(viewer, viewer, enchantment, level)).open();
    }

    @Override
    protected void render() {
        super.render();
        boolean enchanted = holding() && hasAny();
        toolbar(4, Icons.of(holding() ? held().getType() : Material.BARRIER,
                holding() ? "<white>Holding: " + Enchantments.readable(held().getType().name())
                        : "<gray>Nothing in your hand",
                "<gray>Enchantments go on this item.", "",
                "<dark_gray>Hold something else and reopen to change it."), click -> { });
        ItemStack strip = Icons.of(Material.GRINDSTONE, "<red>Strip every enchantment",
                "<gray>Takes all of them off what you hold.", "", "<dark_gray>Asks first.");
        danger(enchanted ? strip : Icons.locked(strip, holding() ? "Nothing on it to strip" : "Hold something first"),
                click -> {
                    if (!enchanted) {
                        return;
                    }
                    new ConfirmMenu(viewer, services.brand(), this, "<red>Strip every enchantment?",
                            List.of("<gray>Everything on what you hold comes off."),
                            () -> services.enchanting().clear(viewer, viewer)).open();
                });
    }

    @Override
    protected boolean hasDanger() {
        return true;
    }

    private boolean hasAny() {
        return !held().getEnchantments().isEmpty()
                || (held().getItemMeta() instanceof EnchantmentStorageMeta storage && storage.hasStoredEnchants());
    }

    private int levelOnItem(Enchantment enchantment) {
        if (held().getItemMeta() instanceof EnchantmentStorageMeta storage) {
            return storage.getStoredEnchantLevel(enchantment);
        }
        return held().getEnchantmentLevel(enchantment);
    }

    private static String reasonFor(Verdict verdict) {
        return switch (verdict.reason()) {
            case "essentials.enchant.wrong-item" ->
                    "Does not fit this item — needs " + PermissionNodes.ENCHANT_ANY_ITEM;
            case "essentials.enchant.nothing-held" -> "Hold something first";
            default -> "Not allowed right now";
        };
    }
}
