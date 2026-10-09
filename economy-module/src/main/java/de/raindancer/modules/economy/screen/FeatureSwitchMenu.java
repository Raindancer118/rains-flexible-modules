package de.raindancer.modules.economy.screen;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.EconomySettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Every switch of the economy, a click each, written straight to the settings file. Pages, so none is ever hidden. */
public final class FeatureSwitchMenu extends PaginatedMenu<FeatureSwitchMenu.Switch> implements IEconomyScreen {

    record Switch(String key, String label, Material icon) {
    }

    static final List<Switch> SWITCHES = List.of(
            new Switch("features.pay", "Paying", Material.WRITABLE_BOOK),
            new Switch("features.bills", "Bills", Material.PAPER),
            new Switch("features.hire", "Hiring", Material.IRON_PICKAXE),
            new Switch("features.loans", "Loans", Material.GOLD_INGOT),
            new Switch("features.cash", "Coins", Material.GOLD_NUGGET),
            new Switch("features.cheques", "Cheques", Material.FILLED_MAP),
            new Switch("cash.right-click", "Right click pays in", Material.HOPPER),
            new Switch("features.shop", "Shop", Material.EMERALD),
            new Switch("features.selling", "Selling", Material.CHEST),
            new Switch("features.auctions", "Auctions", Material.BELL),
            new Switch("features.xp-trade", "Experience trading", Material.EXPERIENCE_BOTTLE),
            new Switch("shop.enchant-books", "Enchanted books for sale", Material.ENCHANTED_BOOK),
            new Switch("features.packs", "Packs for sale", Material.BUNDLE),
            new Switch("features.raffles", "Raffles", Material.NAME_TAG),
            new Switch("features.giveaways", "Giveaways", Material.CAKE),
            new Switch("features.wealth-tax", "Wealth tax", Material.IRON_BARS),
            new Switch("shop.enchanted-selling", "Enchanted items sell", Material.ENCHANTED_BOOK),
            new Switch("features.dynamic-prices", "Supply and demand", Material.COMPARATOR),
            new Switch("shop.derive-from-recipes", "Recipe prices", Material.CRAFTING_TABLE),
            new Switch("features.income", "Passive income", Material.CLOCK),
            new Switch("features.daily", "Daily reward", Material.SUNFLOWER),
            new Switch("features.advancement-rewards", "Advancement rewards", Material.KNOWLEDGE_BOOK),
            new Switch("features.interest", "Interest", Material.EXPERIENCE_BOTTLE),
            new Switch("features.baltop", "Richest players", Material.GOLDEN_HELMET),
            new Switch("features.sidebar", "Sidebar", Material.OAK_SIGN),
            new Switch("features.leaderboards", "World leaderboards", Material.ITEM_FRAME),
            new Switch("accounts.action-bar", "Changes above hotbar", Material.OAK_SIGN),
            new Switch("features.gambling", "Gambling", Material.GOLD_BLOCK),
            new Switch("features.coinflip", "Coin flips", Material.SUNFLOWER),
            new Switch("features.dice", "Dice", Material.WHITE_WOOL),
            new Switch("features.slots", "Slots", Material.DIAMOND),
            new Switch("features.roulette", "Roulette", Material.ENDER_PEARL),
            new Switch("features.lottery", "Lottery", Material.FILLED_MAP),
            new Switch("features.blackjack", "Blackjack", Material.PAPER),
            new Switch("features.baccarat", "Baccarat", Material.RED_CONCRETE),
            new Switch("features.hilo", "Hi-Lo", Material.LIME_CONCRETE),
            new Switch("features.mines", "Mines", Material.TNT),
            new Switch("features.crash", "Crash", Material.FIREWORK_ROCKET),
            new Switch("features.race", "Horse races", Material.SADDLE),
            new Switch("features.scratch", "Scratch cards", Material.MAP));
    private final EconomyServices services;

    FeatureSwitchMenu(EconomyServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Features");
    }

    @Override
    public String breadcrumb() {
        return "Features";
    }

    @Override
    protected List<Switch> entries() {
        return SWITCHES;
    }

    private boolean on(Switch each) {
        String shown = services.store().display(each.key());
        return "true".equalsIgnoreCase(shown) || "on".equalsIgnoreCase(shown);
    }

    @Override
    protected ItemStack icon(Switch each) {
        boolean on = on(each);
        return Icons.of(on ? each.icon() : Material.GRAY_DYE, (on ? "<green>" : "<red>") + each.label(),
                on ? "<green>On" : "<red>Off", "<dark_gray>" + each.key(), "<yellow>Click<gray> to switch");
    }

    @Override
    protected void onClick(Switch each, InventoryClickEvent event) {
        SettingsStore<EconomySettings> store = services.store();
        store.cycle(each.key());
        store.trySave();
        refresh();
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Every part of the economy has its own switch.", "The rest of the settings: /settings → Economy.");
    }

    @Override
    public String describe() {
        return "the economy's switches";
    }
}
