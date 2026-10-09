package de.raindancer.modules.economy.service;

import de.raindancer.core.content.items.NonIngredients;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.choose.Catalogue;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.Pack;
import de.raindancer.modules.economy.model.PackItem;
import de.raindancer.modules.economy.model.PackPrice;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.model.YourPrice;
import de.raindancer.modules.economy.rules.PackPriceRule;
import de.raindancer.modules.economy.store.PackBook;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Selling packs and unpacking them.
 *
 * <p>A pack is a paper item dressed as its icon (the item model), carrying its contents in its data — so a
 * pack bought yesterday unpacks to what was paid for, whatever {@code packs.yml} says today. Paper does
 * nothing on a right click, so no icon (a bed, a block, food) is ever placed or eaten instead of unpacked.
 */
public final class PackService implements IEconomyService {

    public static final NamespacedKey PACK = key("pack");
    public static final NamespacedKey CONTENTS = key("pack-contents");
    public static final NamespacedKey TITLE = key("pack-title");

    private final ShopService shop;
    private final RainEconomy economy;
    private final PackBook book;
    private final Messages messages;
    private final Effects effects;
    private final PackPriceRule rule = new PackPriceRule();
    private volatile EconomySettings settings;

    public PackService(ShopService shop, RainEconomy economy, PackBook book, Messages messages, Effects effects,
                       EconomySettings settings) {
        this.shop = shop;
        this.economy = economy;
        this.book = book;
        this.messages = messages;
        this.effects = effects;
        this.settings = settings == null ? EconomySettings.DEFAULTS : settings;
    }

    private static NamespacedKey key(String name) {
        return Objects.requireNonNull(NamespacedKey.fromString("rainseconomy:" + name));
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
    }

    public boolean enabled() {
        EconomySettings live = settings;
        return live.packsEnabled() && live.shopEnabled();
    }

    public List<Pack> packs() {
        return book.all();
    }

    public Optional<Pack> pack(String id) {
        return book.find(id);
    }

    /** Reads packs.yml again. @return how many packs it holds */
    public int reload() {
        return book.reload();
    }

    public List<String> problems() {
        return book.problems();
    }

    /** What the pack costs this player, or empty when it is not for sale (something in it has no price). */
    public Optional<PackPrice> price(UUID buyer, Pack pack) {
        return rule.price(pack, line -> linePrice(buyer, line), settings.packDiscountPercent());
    }

    private Optional<Money> linePrice(UUID buyer, PackItem line) {
        Material type = Material.matchMaterial(line.material());
        if (type == null || !type.isItem()) {
            return Optional.empty();
        }
        YourPrice yours = shop.priceFor(buyer, type);
        return yours.shop().buyable() ? yours.buyFor(line.amount()) : Optional.empty();
    }

    /** Whether this player has had a pack that is one each. */
    public boolean hadIt(Player player, Pack pack) {
        return pack.once() && player.getPersistentDataContainer().has(onceKey(pack), PersistentDataType.BYTE);
    }

    private static NamespacedKey onceKey(Pack pack) {
        return key("pack-once." + pack.id());
    }

    public void buy(Player player, Pack pack) {
        if (!enabled()) {
            refuse(player, "economy.packs.off");
            return;
        }
        if (hadIt(player, pack)) {
            refuse(player, "economy.packs.once", "pack", pack.title());
            return;
        }
        Optional<PackPrice> price = price(player.getUniqueId(), pack);
        if (price.isEmpty()) {
            refuse(player, "economy.packs.not-for-sale", "pack", pack.title());
            return;
        }
        ItemStack item = item(pack);
        if (!CashService.fits(player.getInventory(), List.of(item))) {
            refuse(player, "economy.shop.no-room");
            return;
        }
        Currency currency = settings.currency();
        Money cost = price.get().price();
        EconomyResult result = economy.move(player.getUniqueId(), cost.negate(), TransactionKind.BUY, pack.title());
        if (!result.succeeded()) {
            Outcomes.tell(messages, effects, player, EconomyResult.failed(result.outcome(), cost, result.balance()),
                    currency, "");
            return;
        }
        if (pack.once()) {
            player.getPersistentDataContainer().set(onceKey(pack), PersistentDataType.BYTE, (byte) 1);
        }
        player.getInventory().addItem(item).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        if (pack.ownPrice().isEmpty()) {
            shop.boughtInPack(pack.contents());
        }
        effects.play(player.getUniqueId(), Cues.REWARD);
        messages.send(player, "economy.packs.bought", "pack", pack.title(), "amount", currency.render(cost));
    }

    /** The pack as an item: what it unpacks to is written on it. */
    public ItemStack item(Pack pack) {
        List<String> lore = new ArrayList<>();
        pack.description().forEach(line -> lore.add("<gray>" + line));
        if (!lore.isEmpty()) {
            lore.add("");
        }
        for (PackItem each : pack.contents()) {
            lore.add("<dark_gray>• <gray>" + each.amount() + " × <white>" + Catalogue.readable(each.material()));
        }
        lore.add("");
        lore.add("<yellow>Right click<gray> to unpack");
        ItemStack item = Icons.of(Material.PAPER, "<gold>" + escape(pack.title()), lore);
        item.editMeta(meta -> {
            Material icon = Material.matchMaterial(pack.icon());
            if (icon != null && icon.isItem()) {
                meta.setItemModel(icon.getKey());
            }
            meta.setEnchantmentGlintOverride(true);
            var data = meta.getPersistentDataContainer();
            data.set(PACK, PersistentDataType.STRING, pack.id());
            data.set(CONTENTS, PersistentDataType.STRING, PackItem.encode(pack.contents()));
            data.set(TITLE, PersistentDataType.STRING, pack.title());
        });
        return NonIngredients.mark(item);
    }

    private static String escape(String text) {
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(text);
    }

    public static boolean isPack(ItemStack stack) {
        return stack != null && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(PACK, PersistentDataType.STRING);
    }

    /** Unpacks one of the packs in this hand. Refuses rather than drops when the contents do not fit. */
    public void unpack(Player player, EquipmentSlot hand) {
        ItemStack held = player.getInventory().getItem(hand);
        if (!isPack(held)) {
            return;
        }
        var data = held.getItemMeta().getPersistentDataContainer();
        String title = Optional.ofNullable(data.get(TITLE, PersistentDataType.STRING)).orElse("pack");
        List<ItemStack> contents = new ArrayList<>();
        for (PackItem each : PackItem.decode(data.get(CONTENTS, PersistentDataType.STRING))) {
            Material material = Material.matchMaterial(each.material());
            if (material == null || !material.isItem()) {
                continue;
            }
            int max = Math.max(1, material.getMaxStackSize());
            for (int left = each.amount(); left > 0; left -= max) {
                contents.add(new ItemStack(material, Math.min(max, left)));
            }
        }
        if (contents.isEmpty()) {
            refuse(player, "economy.packs.empty");
            return;
        }
        ItemStack rest = held.clone();
        rest.setAmount(held.getAmount() - 1);
        player.getInventory().setItem(hand, rest.getAmount() > 0 ? rest : null);
        if (!CashService.fits(player.getInventory(), contents)) {
            player.getInventory().setItem(hand, held);
            refuse(player, "economy.packs.no-room", "stacks", String.valueOf(contents.size()));
            return;
        }
        player.getInventory().addItem(contents.toArray(ItemStack[]::new)).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        effects.play(player.getUniqueId(), Cues.REWARD);
        messages.send(player, "economy.packs.unpacked", "pack", title.toLowerCase(Locale.ROOT).startsWith("the ")
                ? title.substring(4) : title);
    }

    private void refuse(Player player, String key, Object... values) {
        messages.send(player, key, values);
        effects.play(player.getUniqueId(), Cues.NO);
    }
}
