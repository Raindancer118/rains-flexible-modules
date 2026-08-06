package de.raindancer.modules.hungergames.screen;

import de.raindancer.core.content.items.CustomItem;
import de.raindancer.core.content.items.CustomItems;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.choose.AmountChooser;
import de.raindancer.core.ui.choose.EffectChooser;
import de.raindancer.core.ui.choose.ItemChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.core.ui.prompt.ChatPrompts;
import de.raindancer.modules.hungergames.store.SponsorShopStore;
import de.raindancer.modules.hungergames.store.SponsorShopStore.CustomItemReward;
import de.raindancer.modules.hungergames.store.SponsorShopStore.EffectReward;
import de.raindancer.modules.hungergames.store.SponsorShopStore.MaterialReward;
import de.raindancer.modules.hungergames.store.SponsorShopStore.PotionReward;
import de.raindancer.modules.hungergames.store.SponsorShopStore.PotionVariant;
import de.raindancer.modules.hungergames.store.SponsorShopStore.Reward;
import de.raindancer.modules.hungergames.store.SponsorShopStore.ShopItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One shop offer, built or edited on a single page.
 *
 * <h2>Why this is one page where the source plugin needed three</h2>
 * {@code ShopItemBuilderMenu} picked what to sell, {@code ShopCostMenu} picked the price and amount, and
 * {@code ShopEnchantMenu} picked its enchantments — three pages because the old menu framework had no
 * chooser to pick a reward <em>and</em> a number on the same screen. Core's {@link ItemChooser} and
 * {@link AmountChooser} are exactly that: this page opens either one over itself and comes straight back
 * with an answer, so everything the three source pages did fits on one.
 */
public final class ShopEntryMenu extends Menu implements IHungerGamesScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** What kind of reward this offer hands out — chosen when the offer is created, fixed after that. */
    public enum Kind { MATERIAL, CUSTOM, POTION, EFFECT }

    private final SponsorShopStore shopStore;
    private final CustomItems customItems;
    private final ChatPrompts prompts;
    private final Integer editIndex;
    private final Kind kind;

    private Material material = Material.DIAMOND;
    private String customItemId;
    private PotionVariant potionVariant = PotionVariant.NORMAL;
    private String potionType = "STRONG_HEALING";
    private String effectName = "SPEED";
    private int durationSeconds = 15;
    private int amplifier = 0;
    private int amount = 1;
    private int cost = 5;
    private String displayName = "New offer";
    private List<String> enchantments = List.of();

    /** Creating a new offer of a given kind. */
    public ShopEntryMenu(Player viewer, Brand brand, Menu parent, SponsorShopStore shopStore,
                         CustomItems customItems, ChatPrompts prompts, Kind kind) {
        super(viewer, brand, parent);
        this.shopStore = shopStore;
        this.customItems = customItems;
        this.prompts = prompts;
        this.editIndex = null;
        this.kind = kind;
        this.customItemId = firstCustomItemId();
    }

    /** Editing the offer currently at {@code index} in the shop's list. */
    public ShopEntryMenu(Player viewer, Brand brand, Menu parent, SponsorShopStore shopStore,
                         CustomItems customItems, ChatPrompts prompts, int index) {
        super(viewer, brand, parent);
        this.shopStore = shopStore;
        this.customItems = customItems;
        this.prompts = prompts;
        this.editIndex = index;
        ShopItem existing = currentItems().get(index);
        this.kind = kindOf(existing.reward());
        this.cost = existing.cost();
        this.displayName = existing.displayName();
        this.enchantments = existing.enchantments();
        switch (existing.reward()) {
            case MaterialReward m -> {
                material = m.material();
                amount = m.amount();
            }
            case CustomItemReward c -> {
                customItemId = c.customId();
                amount = c.amount();
            }
            case PotionReward p -> {
                potionVariant = p.variant();
                potionType = p.potionType();
                amount = p.amount();
            }
            case EffectReward e -> {
                effectName = e.effectName();
                durationSeconds = e.durationSeconds();
                amplifier = e.amplifier();
            }
        }
    }

    static Kind kindOf(Reward reward) {
        return switch (reward) {
            case MaterialReward ignored -> Kind.MATERIAL;
            case CustomItemReward ignored -> Kind.CUSTOM;
            case PotionReward ignored -> Kind.POTION;
            case EffectReward ignored -> Kind.EFFECT;
        };
    }

    private String firstCustomItemId() {
        return customItems.all().isEmpty() ? "" : customItems.all().get(0).id();
    }

    private Set<String> knownCustomItemIds() {
        return customItems.all().stream().map(CustomItem::id)
                .map(id -> id.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private List<ShopItem> currentItems() {
        return new ArrayList<>(shopStore.load(knownCustomItemIds()));
    }

    @Override
    protected Component title() {
        return MINI.deserialize(editIndex == null ? "<dark_gray>New offer" : "<dark_gray>Edit offer");
    }

    @Override
    public String breadcrumb() {
        return editIndex == null ? "New offer" : "Edit offer";
    }

    @Override
    protected void render() {
        band(MenuLayout.WHO, 4, Icons.of(previewMaterial(), "<yellow>" + displayName,
                "<gray>" + previewLine()));

        switch (kind) {
            case MATERIAL -> band(MenuLayout.RULES, 2, Icons.of(material, "<yellow>Material: " + material.name(),
                            "<dark_gray>Click to choose."),
                    click -> new ItemChooser(viewer, brand(), this, "Pick a material",
                            picked -> {
                                material = picked;
                                refresh();
                            }).open());
            case CUSTOM -> band(MenuLayout.RULES, 2, Icons.of(Material.NETHER_STAR,
                            "<light_purple>Custom item: " + (customItemId == null || customItemId.isBlank()
                                    ? "none defined" : customItemId),
                            "<dark_gray>Click to choose."),
                    click -> new CustomItemPicker().open());
            case POTION -> {
                band(MenuLayout.RULES, 2, Icons.of(potionVariant.material(),
                        "<light_purple>Variant: " + potionVariant.name(),
                        "<dark_gray>Left: next  Right: previous"),
                        click -> {
                            PotionVariant[] all = PotionVariant.values();
                            int i = potionVariant.ordinal();
                            potionVariant = all[click.isRightClick() ? Math.floorMod(i - 1, all.length)
                                    : Math.floorMod(i + 1, all.length)];
                            refresh();
                        });
                band(MenuLayout.RULES, 3, Icons.of(Material.BREWING_STAND, "<light_purple>Type: " + potionType,
                                "<dark_gray>Click to type a new one."),
                        click -> askText("Potion type (e.g. STRONG_HEALING)", potionType,
                                typed -> potionType = typed.toUpperCase(Locale.ROOT)));
            }
            case EFFECT -> {
                band(MenuLayout.RULES, 2, Icons.of(Material.POTION, "<yellow>Effect: " + effectName,
                                "<dark_gray>Click to choose."),
                        click -> new EffectChooser(viewer, brand(), this, "Pick an effect",
                                picked -> {
                                    effectName = picked.toUpperCase(Locale.ROOT);
                                    refresh();
                                }).open());
                band(MenuLayout.RULES, 3, Icons.of(Material.CLOCK, "<yellow>Duration: " + durationSeconds + "s",
                                "<dark_gray>Click to change."),
                        click -> new AmountChooser(viewer, brand(), this, "Duration (seconds)", durationSeconds,
                                1, 3_600, value -> {
                                    durationSeconds = value;
                                    refresh();
                                }).open());
                band(MenuLayout.RULES, 4, Icons.of(Material.GLOWSTONE_DUST, "<yellow>Level: " + (amplifier + 1),
                                "<dark_gray>Click to change."),
                        click -> new AmountChooser(viewer, brand(), this, "Level", amplifier + 1, 1, 10,
                                value -> {
                                    amplifier = value - 1;
                                    refresh();
                                }).open());
            }
        }

        band(MenuLayout.LAND, 2, Icons.of(Material.GOLD_NUGGET, "<gold>Cost: " + cost + " token(s)",
                        "<dark_gray>Click to change."),
                click -> new AmountChooser(viewer, brand(), this, "Cost (tokens)", cost, 1, 1_000, value -> {
                    cost = value;
                    refresh();
                }).open());

        if (kind != Kind.EFFECT) {
            band(MenuLayout.LAND, 3, Icons.of(Material.REPEATER, "<yellow>Amount: " + amount,
                            "<dark_gray>Click to change."),
                    click -> new AmountChooser(viewer, brand(), this, "Amount", amount, 1, 64, value -> {
                        amount = value;
                        refresh();
                    }).open());
            band(MenuLayout.LAND, 4, Icons.of(Material.ENCHANTED_BOOK, "<light_purple>Enchantments: "
                                    + (enchantments.isEmpty() ? "none" : String.join(", ", enchantments)),
                            "<dark_gray>Click to type, e.g. sharpness:5,unbreaking:3"),
                    click -> askText("Enchantments (name:level, comma separated)",
                            String.join(",", enchantments), typed -> {
                                enchantments = typed.isBlank() ? List.of()
                                        : List.of(typed.split(",")).stream().map(String::trim)
                                                .filter(s -> !s.isBlank()).toList();
                            }));
        }

        band(MenuLayout.LAND, 6, Icons.of(Material.NAME_TAG, "<yellow>Name: " + displayName,
                        "<dark_gray>Click to type a new one."),
                click -> askText("Display name", displayName, typed -> displayName = typed));

        danger(Icons.of(Material.EMERALD_BLOCK, "<green>Save this offer",
                        "<gray>" + (editIndex == null ? "Adds it to the shop." : "Replaces the current offer."),
                        "<dark_gray>Asks first."),
                click -> new ConfirmScreen(viewer, brand(), this, "<yellow>Save \"" + displayName + "\"?",
                        List.of("<gray>" + previewLine(), "<gray>Cost: " + cost + " token(s)"),
                        this::save).open());
    }

    private void askText(String label, String initial, java.util.function.Consumer<String> onAnswer) {
        viewer.closeInventory();
        viewer.sendMessage(MINI.deserialize("<yellow>" + label + " — type it in chat (current: " + initial
                + ")."));
        prompts.ask(viewer.getUniqueId(), "hungergames-shop", Duration.ofSeconds(60),
                typed -> {
                    if (typed != null && !typed.isBlank()) {
                        onAnswer.accept(typed.trim());
                    }
                    open();
                },
                this::open);
    }

    private void save() {
        Reward reward = switch (kind) {
            case MATERIAL -> new MaterialReward(material, amount);
            case CUSTOM -> new CustomItemReward(customItemId, amount);
            case POTION -> new PotionReward(potionVariant, potionType, amount);
            case EFFECT -> new EffectReward(effectName, durationSeconds, amplifier);
        };
        List<ShopItem> items = currentItems();
        String id = editIndex != null ? items.get(editIndex).id() : uniqueId(items);
        ShopItem item = new ShopItem(id, reward, cost, displayName, true, enchantments);
        if (editIndex != null) {
            items.set(editIndex, item);
        } else {
            items.add(item);
        }
        shopStore.save(items);
        if (parent() != null) {
            parent().open();
        } else {
            viewer.closeInventory();
        }
    }

    private String uniqueId(List<ShopItem> items) {
        String base = switch (kind) {
            case MATERIAL -> material.name().toLowerCase(Locale.ROOT);
            case CUSTOM -> customItemId == null ? "custom" : customItemId.toLowerCase(Locale.ROOT);
            case POTION -> potionType.toLowerCase(Locale.ROOT);
            case EFFECT -> effectName.toLowerCase(Locale.ROOT);
        };
        String id = base;
        int n = 2;
        while (idTaken(id, items)) {
            id = base + n++;
        }
        return id;
    }

    private static boolean idTaken(String id, List<ShopItem> items) {
        for (ShopItem item : items) {
            if (item.id().equalsIgnoreCase(id)) {
                return true;
            }
        }
        return false;
    }

    private Material previewMaterial() {
        return switch (kind) {
            case MATERIAL -> material;
            case CUSTOM -> Material.NETHER_STAR;
            case POTION -> potionVariant.material();
            case EFFECT -> Material.POTION;
        };
    }

    private String previewLine() {
        return switch (kind) {
            case MATERIAL -> amount + "x " + material.name();
            case CUSTOM -> amount + "x " + customItemId;
            case POTION -> amount + "x potion (" + potionVariant.name().toLowerCase(Locale.ROOT) + ", "
                    + potionType + ")";
            case EFFECT -> "Effect " + effectName + " " + durationSeconds + "s (level " + (amplifier + 1) + ")";
        };
    }

    /** This module's own small list of custom items — see {@code LootEntryMenu}'s identical picker for why. */
    private final class CustomItemPicker extends PaginatedMenu<CustomItem> implements IHungerGamesScreen {

        CustomItemPicker() {
            super(ShopEntryMenu.this.viewer, ShopEntryMenu.this.brand(), ShopEntryMenu.this);
        }

        @Override
        protected Component title() {
            return MINI.deserialize("<dark_gray>Custom items");
        }

        @Override
        public String breadcrumb() {
            return "Custom items";
        }

        @Override
        protected List<CustomItem> entries() {
            return customItems.all();
        }

        @Override
        protected ItemStack emptyIcon() {
            return Icons.of(Material.BARRIER, "<gray>None defined");
        }

        @Override
        protected ItemStack icon(CustomItem item) {
            return Icons.of(item.material(), "<light_purple>" + item.nameOrId(), "<dark_gray>" + item.key());
        }

        @Override
        protected void onClick(CustomItem item, InventoryClickEvent event) {
            customItemId = item.id();
            open();
        }

        @Override
        public String describe() {
            return "this server's custom items, for a shop offer that rewards one";
        }
    }

    @Override
    public String describe() {
        return "one sponsor shop offer, built or edited on a single page";
    }
}
