package de.raindancer.modules.economy.service;

import de.raindancer.modules.economy.model.Game;
import de.raindancer.core.content.items.NonIngredients;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.Form;
import de.raindancer.modules.economy.rules.ScratchRule;
import de.raindancer.modules.economy.store.AccountBook;
import de.raindancer.modules.economy.store.CashSeal;
import de.raindancer.modules.economy.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.security.SecureRandom;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Scratch cards: tickets bought, carried, given away, and scratched. A ticket is numbered and sealed like a
 * cheque and can be scratched once — a copy is caught at scratching. What it wins is drawn when it is
 * scratched and paid at once; the fields only reveal it.
 */
public final class ScratchService implements IEconomyService {

    public static final NamespacedKey SERIAL = Objects.requireNonNull(NamespacedKey.fromString("rainseconomy:ticket"));
    public static final NamespacedKey SEAL = Objects.requireNonNull(NamespacedKey.fromString("rainseconomy:ticket-seal"));
    public static final NamespacedKey PRICE = Objects.requireNonNull(NamespacedKey.fromString("rainseconomy:ticket-price"));

    /** A scratched ticket: its fields, and what it paid. */
    public record Scratched(List<ScratchRule.Prize> fields, ScratchRule.Prize won, Money price, Money payout) {
    }

    private final RainEconomy economy;
    private final AccountBook book;
    private final GamblingService gambling;
    private final CashSeal seal;
    private final Messages messages;
    private final ScratchRule rule = new ScratchRule();
    private final SecureRandom random = new SecureRandom();
    private volatile EconomySettings settings;

    public ScratchService(RainEconomy economy, GamblingService gambling, CashSeal seal, Messages messages,
                          EconomySettings settings) {
        this.economy = economy;
        this.book = economy.book();
        this.gambling = gambling;
        this.seal = seal;
        this.messages = messages;
        settings(settings);
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
    }

    public ScratchRule rule() {
        return rule;
    }

    public void buy(Player player, int count) {
        EconomySettings live = settings;
        if (!live.gameOn(Game.SCRATCH) || !player.hasPermission(PermissionNodes.GAMBLE)) {
            gambling.tell(player, "economy.gamble.off");
            return;
        }
        if (gambling.loanBlocks(player)) {
            return;
        }
        Money price = live.scratchPriceMoney();
        int bought = 0;
        for (int i = 0; i < Math.max(1, Math.min(64, count)); i++) {
            String serial = Long.toString(random.nextLong() & Long.MAX_VALUE, 36).toUpperCase();
            economy.open(player.getUniqueId(), player.getName());
            EconomyResult paid = book.buyTicket(player.getUniqueId(), serial, price, economy.most());
            if (!paid.succeeded()) {
                gambling.tell(player, paid.outcome() == EconomyResult.Outcome.NOT_ENOUGH ? "economy.not-enough"
                        : "economy.refused", "amount", live.currency().render(price),
                        "balance", live.currency().render(paid.balance()), "player", "");
                break;
            }
            player.getInventory().addItem(ticket(serial, price)).values()
                    .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            bought++;
        }
        if (bought > 0) {
            economy.tell(player.getUniqueId(), price.times(bought).negate(), economy.balance(player.getUniqueId()),
                    de.raindancer.modules.economy.model.TransactionKind.GAMBLE);
            gambling.sounds().play(player.getUniqueId(), GameSounds.CASH);
            messages.send(player, "economy.gamble.scratch-bought", "count", String.valueOf(bought),
                    "amount", live.currency().render(price.times(bought)));
        }
    }

    private ItemStack ticket(String serial, Money price) {
        ItemStack stack = new ItemStack(Material.MAP);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text("Scratch card", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Worth up to a thousand times its price.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("No. " + serial, NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("Right click to scratch it.", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(SERIAL, PersistentDataType.STRING, serial);
        meta.getPersistentDataContainer().set(PRICE, PersistentDataType.LONG, price.minor());
        meta.getPersistentDataContainer().set(SEAL, PersistentDataType.STRING, seal.seal(price, Form.NOTE, "ticket:" + serial));
        stack.setItemMeta(meta);
        return NonIngredients.mark(stack);
    }

    public static boolean isTicket(ItemStack stack) {
        return stack != null && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(SERIAL, PersistentDataType.STRING);
    }

    /**
     * Scratches the ticket in this hand slot: uses it up, draws its prize and pays it. Empty when it is no
     * ticket — or a copy, which is taken away.
     */
    public Optional<Scratched> scratch(Player player, int slot) {
        ItemStack stack = player.getInventory().getItem(slot);
        if (!isTicket(stack)) {
            return Optional.empty();
        }
        var data = stack.getItemMeta().getPersistentDataContainer();
        String serial = data.get(SERIAL, PersistentDataType.STRING);
        Money price = Money.of(Optional.ofNullable(data.get(PRICE, PersistentDataType.LONG)).orElse(0L));
        String sealed = data.get(SEAL, PersistentDataType.STRING);
        boolean genuine = sealed != null && seal.verify(new de.raindancer.modules.economy.model.CashPiece(price, 1,
                Form.NOTE, "ticket:" + serial, true, sealed));
        if (stack.getAmount() > 1) {
            stack.setAmount(1);
            player.getInventory().setItem(slot, stack);
        }
        if (!genuine || !book.useTicket(player.getUniqueId(), serial)) {
            player.getInventory().setItem(slot, null);
            gambling.tell(player, "economy.gamble.scratch-copy");
            return Optional.empty();
        }
        player.getInventory().setItem(slot, null);
        ScratchRule.Prize won = rule.draw(random.nextDouble());
        Money payout = price.share(rule.multiplier(won, settings.edge(Game.SCRATCH)));
        gambling.payOut(player.getUniqueId(), price, payout, "Scratch card");
        return Optional.of(new Scratched(rule.fields(won, random), won, price, payout));
    }

    public void reveal(Player player, Scratched card) {
        gambling.finish(player, card.payout().isPositive(), card.price(), card.payout(), "economy.gamble.scratch",
                "prize", card.won() == null ? "nothing" : card.won().name().toLowerCase());
    }
}
