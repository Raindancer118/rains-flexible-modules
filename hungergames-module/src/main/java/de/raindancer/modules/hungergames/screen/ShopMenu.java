package de.raindancer.modules.hungergames.screen;

import de.raindancer.core.content.items.CustomItem;
import de.raindancer.core.content.items.CustomItems;
import de.raindancer.core.content.items.ItemFactory;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.hungergames.service.AnnouncementService;
import de.raindancer.modules.hungergames.service.AnnouncementService.Style;
import de.raindancer.modules.hungergames.service.SponsorTokenService;
import de.raindancer.modules.hungergames.store.SponsorShopStore;
import de.raindancer.modules.hungergames.store.SponsorShopStore.CustomItemReward;
import de.raindancer.modules.hungergames.store.SponsorShopStore.EffectReward;
import de.raindancer.modules.hungergames.store.SponsorShopStore.MaterialReward;
import de.raindancer.modules.hungergames.store.SponsorShopStore.PotionReward;
import de.raindancer.modules.hungergames.store.SponsorShopStore.Reward;
import de.raindancer.modules.hungergames.store.SponsorShopStore.ShopItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a tribute sees at a sponsor beacon: what tokens buy, and whether they can afford it.
 *
 * <h2>Why this class cannot build a plain material or potion stack itself</h2>
 * {@code ScreenGrammarTest} keeps {@code new ItemStack(} out of every file in this package, on purpose: it
 * is the same rule that stops a page rebuilding what {@link Icons} already owns. A custom item comes from
 * {@link ItemFactory}, which already knows how to build one — but a plain {@code DIAMOND_SWORD:1} or a
 * {@code POTION:STRONG_HEALING} reward has no registry to ask, because it is not a registered item at all,
 * only a material and an amount. So this page takes the one seam that has to exist somewhere —
 * {@link PlainStack} — and calls it exactly where the source engine called {@code new ItemStack(...)}
 * directly. Whoever wires this screen up implements it in three lines; nothing here pretends the seam does
 * not exist.
 *
 * <h2>No parent, by design</h2>
 * Reached from a sponsor beacon in the world or from the admin suite's preview, never from a click inside
 * another one of this module's own pages — see {@code ScreenGrammarTest}'s exempt list.
 */
public final class ShopMenu extends PaginatedMenu<ShopItem> implements IHungerGamesScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /**
     * Builds a plain stack for a reward Core's registries do not own — a material or a potion. The one
     * Bukkit-only seam this screen needs and will not build for itself; see the class javadoc.
     */
    @FunctionalInterface
    public interface PlainStack {
        ItemStack build(Reward reward, int amount);
    }

    private final SponsorShopStore shopStore;
    private final SponsorTokenService tokens;
    private final AnnouncementService announcements;
    private final CustomItems customItems;
    private final ItemFactory itemFactory;
    private final PlainStack plainStack;
    private final boolean previewOnly;

    public ShopMenu(Player viewer, Brand brand, SponsorShopStore shopStore, SponsorTokenService tokens,
                    AnnouncementService announcements, CustomItems customItems, ItemFactory itemFactory,
                    PlainStack plainStack, boolean previewOnly) {
        super(viewer, brand, null);
        this.shopStore = shopStore;
        this.tokens = tokens;
        this.announcements = announcements;
        this.customItems = customItems;
        this.itemFactory = itemFactory;
        this.plainStack = plainStack;
        this.previewOnly = previewOnly;
    }

    @Override
    protected Component title() {
        return MINI.deserialize(previewOnly ? "<dark_gray>Sponsor shop (preview)" : "<dark_gray>Sponsor shop");
    }

    @Override
    public String breadcrumb() {
        return "Shop";
    }

    private Set<String> knownCustomItemIds() {
        return customItems.all().stream().map(CustomItem::id)
                .map(id -> id.toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
    }

    @Override
    protected List<ShopItem> entries() {
        return shopStore.load(knownCustomItemIds()).stream().filter(ShopItem::enabled).toList();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BARRIER, "<gray>Nothing for sale",
                "<gray>No sponsor has stocked this shop yet.");
    }

    @Override
    protected ItemStack icon(ShopItem item) {
        int balance = tokens.countTokens(viewer);
        boolean affordable = balance >= item.cost();

        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + describe(item.reward()));
        if (!item.enchantments().isEmpty()) {
            lore.add("<light_purple>Enchanted: " + String.join(", ", item.enchantments()));
        }
        lore.add((affordable ? "<gold>" : "<red>") + "Cost: " + item.cost() + " token(s)");
        lore.add("<dark_gray>Your balance: " + balance);
        lore.add("");
        if (previewOnly) {
            lore.add("<dark_gray>Preview — buy at a real beacon.");
        } else if (!affordable) {
            lore.add("<red>Not enough sponsor tokens.");
        } else {
            lore.add("<aqua>Click to buy.");
        }

        return Icons.of(iconMaterial(item.reward()), (affordable || previewOnly ? "<yellow>" : "<red>")
                + item.displayName(), lore);
    }

    @Override
    protected void onClick(ShopItem item, InventoryClickEvent event) {
        purchase(item);
    }

    private void purchase(ShopItem item) {
        if (previewOnly) {
            announce("sponsor-shop-disabled");
            return;
        }
        if (!tokens.removeTokens(viewer, item.cost())) {
            announcements.send(viewer.getUniqueId(), viewer, "sponsor-not-enough",
                    new Style[]{Style.CHAT}, "cost", String.valueOf(item.cost()));
            return;
        }
        if (!grant(item.reward(), item.enchantments())) {
            tokens.giveManually("shop-refund", viewer, item.cost());
            viewer.sendMessage(MINI.deserialize("<red>\"" + item.displayName()
                    + "\" could not be granted — your tokens were refunded."));
            return;
        }
        announcements.send(viewer.getUniqueId(), viewer, "sponsor-purchase",
                new Style[]{Style.CHAT}, "item", item.displayName(), "cost", String.valueOf(item.cost()));
        refresh();
    }

    /** @return {@code false} if the reward could not be resolved — the purchase is refunded in that case */
    private boolean grant(Reward reward, List<String> enchantments) {
        if (reward instanceof EffectReward effect) {
            PotionEffectType type = Registry.EFFECT.get(
                    org.bukkit.NamespacedKey.minecraft(effect.effectName().toLowerCase(Locale.ROOT)));
            if (type == null) {
                return false;
            }
            viewer.addPotionEffect(new PotionEffect(type, effect.durationSeconds() * 20, effect.amplifier()));
            return true;
        }
        ItemStack stack = buildStack(reward);
        if (stack == null) {
            return false;
        }
        for (String spec : enchantments) {
            applyEnchantment(stack, spec);
        }
        giveToInventory(stack);
        return true;
    }

    private ItemStack buildStack(Reward reward) {
        return switch (reward) {
            case CustomItemReward custom -> customItems.byKey(customIdToKey(custom.customId()))
                    .flatMap(item -> itemFactory.create(item, Math.max(1, custom.amount())))
                    .orElse(null);
            case MaterialReward material -> plainStack.build(reward, material.amount());
            case PotionReward potion -> plainStack.build(reward, potion.amount());
            case EffectReward ignored -> null; // handled in grant() before this is ever reached
        };
    }

    /** {@code customId} is bare (e.g. {@code FIENDFINDER}); Core's registry keys are {@code plugin:id}. */
    private String customIdToKey(String customId) {
        return customItems.all().stream()
                .filter(item -> item.id().equalsIgnoreCase(customId))
                .findFirst()
                .map(CustomItem::key)
                .orElse(customId);
    }

    private static void applyEnchantment(ItemStack stack, String spec) {
        String[] parts = spec.split(":", 2);
        var enchant = Registry.ENCHANTMENT.get(
                org.bukkit.NamespacedKey.minecraft(parts[0].trim().toLowerCase(Locale.ROOT)));
        if (enchant == null) {
            return;
        }
        int level = 1;
        if (parts.length == 2) {
            try {
                level = Math.max(1, Integer.parseInt(parts[1].trim()));
            } catch (NumberFormatException ignored) {
                // stays at 1
            }
        }
        stack.addUnsafeEnchantment(enchant, level);
    }

    private void giveToInventory(ItemStack stack) {
        viewer.getInventory().addItem(stack).values()
                .forEach(rest -> viewer.getWorld().dropItemNaturally(viewer.getLocation(), rest));
    }

    private void announce(String key) {
        announcements.send(viewer.getUniqueId(), viewer, key, new Style[]{Style.CHAT});
    }

    /** Pure, for {@code ShopMenuTest}: what a reward's lore line says, without a server. */
    static String describe(Reward reward) {
        return switch (reward) {
            case MaterialReward m -> m.amount() + "x " + m.material().name();
            case EffectReward e -> "Effect: " + e.effectName() + " " + e.durationSeconds()
                    + "s (level " + (e.amplifier() + 1) + ")";
            case CustomItemReward c -> c.amount() + "x " + c.customId();
            case PotionReward p -> p.amount() + "x potion (" + p.variant().name().toLowerCase(Locale.ROOT)
                    + ", " + p.potionType() + ")";
        };
    }

    /** Pure, for {@code ShopMenuTest}: the icon a reward is shown with. */
    static Material iconMaterial(Reward reward) {
        return switch (reward) {
            case MaterialReward m -> m.material();
            case EffectReward ignored -> Material.POTION;
            case CustomItemReward ignored -> Material.NETHER_STAR;
            case PotionReward p -> p.variant().material();
        };
    }

    @Override
    public String describe() {
        return "the sponsor shop, as a tribute spending tokens sees it";
    }
}
