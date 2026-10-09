package de.raindancer.modules.economy.service;

import de.raindancer.core.content.items.NonIngredients;
import de.raindancer.core.moderation.audit.Audit;
import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.CashPiece;
import de.raindancer.modules.economy.model.Form;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.rules.CashCheckRule;
import de.raindancer.modules.economy.model.CashCheck;
import de.raindancer.modules.economy.store.AccountBook;
import de.raindancer.modules.economy.store.CashSeal;
import de.raindancer.modules.economy.store.CashTags;
import de.raindancer.modules.economy.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Money as things you can hold: withdrawing coins and cheques, and paying them back in.
 *
 * <h2>The two promises</h2>
 * <ul>
 *   <li>Nothing leaves the account without the items existing, and nothing is credited without the items
 *       going. Withdrawing checks the room first; paying in removes the pieces only once the ledger has
 *       accepted them.</li>
 *   <li>A cheque is paid in once. A second copy — a duplication glitch, a hacked client — is
 *       confiscated, recorded, and reported to staff, and nothing is credited for it.</li>
 * </ul>
 */
public final class CashService implements IEconomyService {

    /** What one coin is worth: one unit of the currency, which has no smaller one. */
    public static final Money COIN = Money.of(1);

    private static final char[] SERIAL_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final Server server;
    private final RainEconomy economy;
    private final AccountBook book;
    private final Messages messages;
    private final Effects effects;
    private final Audit audit;
    private final CashSeal seal;
    private final CashCheckRule check = new CashCheckRule();
    private final SecureRandom random = new SecureRandom();
    private volatile EconomySettings settings;

    private volatile SupplyService supply;

    /** The money supply's settings; the shipped ones, which change nothing, until wired. */
    public void supply(SupplyService service) {
        this.supply = service;
    }

    private de.raindancer.modules.economy.SupplySettings supplied() {
        return SupplyService.settingsOf(supply);
    }

    public CashService(Server server, RainEconomy economy, Messages messages, Effects effects, Audit audit,
                       CashSeal seal, EconomySettings settings) {
        this.server = server;
        this.economy = economy;
        this.book = economy.book();
        this.messages = messages;
        this.effects = effects;
        this.audit = audit;
        this.seal = seal;
        settings(settings);
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
    }

    public CashSeal seal() {
        return seal;
    }

    /** What a coin is made of right now. */
    public Material coinMaterial() {
        Material material = settings.coinItem();
        return material == null || material.isAir() || !material.isItem() ? Material.GOLD_NUGGET : material;
    }

    /** Whether this piece carries this server's seal over what it claims to be. */
    public boolean sealed(CashPiece piece) {
        return seal.verify(piece);
    }

    // ---------------------------------------------------------------------------- making cash

    /** One coin, in whatever the coin is made of today. */
    public ItemStack coin() {
        return forge(coinMaterial(), COIN, Form.COIN, null, null);
    }

    /** A cheque for an amount, numbered and signed by whoever wrote it. */
    public ItemStack cheque(Money value, String serial, String issuer) {
        return forge(Material.PAPER, value, Form.NOTE, serial, issuer);
    }

    private ItemStack forge(Material material, Money value, Form form, String serial, String issuer) {
        Currency currency = settings.currency();
        boolean cheque = form == Form.NOTE;
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text().append(currency.render(value))
                .append(Component.text(cheque ? " Cheque" : " " + currency.singular(), NamedTextColor.GRAY)).build()
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        if (issuer != null) {
            lore.add(line("Written by " + issuer, NamedTextColor.GRAY));
        }
        if (serial != null) {
            lore.add(line("No. " + serial, NamedTextColor.DARK_GRAY));
        }
        lore.add(Component.empty());
        lore.add(line("Right click to pay it into your account.", NamedTextColor.YELLOW));
        lore.add(line("Sneak and right click to pay in all your cash.", NamedTextColor.YELLOW));
        lore.add(line("Money, not " + readable(material) + ": it cannot be crafted with.", NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        if (cheque) {
            meta.setEnchantmentGlintOverride(true);
        }
        stack.setItemMeta(meta);
        if (!cheque && stack.getMaxStackSize() != 64) {
            // Coins always stack to 64, whatever they are made of — ender pearls, saddles, anything.
            stack.setData(io.papermc.paper.datacomponent.DataComponentTypes.MAX_STACK_SIZE, 64);
        }
        String model = settings.coinModel();
        if (!cheque && model != null && !model.isBlank()) {
            try {
                stack.setData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL,
                        net.kyori.adventure.key.Key.key(model.strip()));
            } catch (IllegalArgumentException notAKey) {
                // A typo in the setting keeps the item's own look rather than losing the coin.
            }
        }
        CashTags.stamp(stack, value, form, serial, cheque, issuer, seal.seal(value, form, serial));
        return NonIngredients.mark(stack);
    }

    private static Component line(String text, NamedTextColor colour) {
        return Component.text(text, colour).decoration(TextDecoration.ITALIC, false);
    }

    private static String readable(Material material) {
        return material.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    String serial() {
        StringBuilder built = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (i > 0 && i % 4 == 0) {
                built.append('-');
            }
            built.append(SERIAL_ALPHABET[random.nextInt(SERIAL_ALPHABET.length)]);
        }
        return built.toString();
    }

    // ---------------------------------------------------------------------------- withdrawing

    /** Takes an amount out as coins, or as one cheque. */
    public void withdraw(Player player, Money amount, boolean asCheque) {
        EconomySettings live = settings;
        Currency currency = live.currency();
        if (!live.cashEnabled()) {
            refuse(player, "economy.cash.off");
            return;
        }
        if (!amount.isPositive()) {
            refuse(player, "economy.not-an-amount");
            return;
        }
        long wait = new de.raindancer.modules.economy.rules.VestingRule().hoursLeft(
                book.find(player.getUniqueId()).map(de.raindancer.modules.economy.model.Account::created).orElse(0L),
                System.currentTimeMillis(), supplied().newAccountLock() ? supplied().newAccountHours() : 0);
        if (wait > 0) {
            refuse(player, "economy.pay.too-new", "hours", String.valueOf(wait));
            return;
        }
        List<ItemStack> items = new ArrayList<>();
        Map<String, Money> numbered = new LinkedHashMap<>();
        Map<Money, Integer> coins = new LinkedHashMap<>();
        if (asCheque) {
            if (!live.chequesEnabled()) {
                refuse(player, "economy.cash.cheques-off");
                return;
            }
            String serial = serial();
            numbered.put(serial, amount);
            items.add(cheque(amount, serial, player.getName()));
        } else {
            long count = amount.minor() / COIN.minor();
            if (count > live.mostPieces()) {
                refuse(player, "economy.cash.too-many", "pieces", String.valueOf(count),
                        "most", String.valueOf(live.mostPieces()));
                return;
            }
            coins.put(COIN, (int) count);
            ItemStack coin = coin();
            int stack = Math.max(1, coin.getMaxStackSize());
            long left = count;
            while (left > 0) {
                ItemStack part = coin.clone();
                part.setAmount((int) Math.min(stack, left));
                items.add(part);
                left -= part.getAmount();
            }
        }
        if (!fits(player.getInventory(), items)) {
            refuse(player, "economy.cash.no-room");
            return;
        }
        Money fee = amount.share(live.withdrawFee());
        EconomyResult result = book.issueCash(player.getUniqueId(), numbered, coins, amount, fee, economy.most());
        if (!result.succeeded()) {
            Outcomes.tell(messages, effects, player, EconomyResult.failed(result.outcome(), amount.plus(fee),
                    result.balance()), currency, "");
            return;
        }
        player.getInventory().addItem(items.toArray(ItemStack[]::new)).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        economy.tell(player.getUniqueId(), amount.plus(fee).negate(), result.balance(), TransactionKind.WITHDRAW);
        effects.play(player.getUniqueId(), Cues.REWARD);
        messages.send(player, asCheque ? "economy.cash.cheque-written" : "economy.cash.withdrawn",
                "amount", currency.render(amount), "balance", currency.render(result.balance()));
        if (fee.isPositive()) {
            messages.send(player, "economy.cash.fee", "fee", currency.render(fee));
        }
    }

    /** Whether all of these would go into the inventory, merging into partial stacks as the game does. */
    static boolean fits(PlayerInventory inventory, List<ItemStack> items) {
        ItemStack[] slots = inventory.getStorageContents();
        ItemStack[] simulated = new ItemStack[slots.length];
        for (int i = 0; i < slots.length; i++) {
            simulated[i] = slots[i] == null ? null : slots[i].clone();
        }
        for (ItemStack item : items) {
            int left = item.getAmount();
            for (int i = 0; i < simulated.length && left > 0; i++) {
                ItemStack there = simulated[i];
                if (there != null && !there.getType().isAir() && there.isSimilar(item)) {
                    int room = Math.max(0, there.getMaxStackSize() - there.getAmount());
                    int moved = Math.min(room, left);
                    there.setAmount(there.getAmount() + moved);
                    left -= moved;
                }
            }
            for (int i = 0; i < simulated.length && left > 0; i++) {
                if (simulated[i] == null || simulated[i].getType().isAir()) {
                    ItemStack placed = item.clone();
                    placed.setAmount(Math.min(left, Math.max(1, item.getMaxStackSize())));
                    simulated[i] = placed;
                    left -= placed.getAmount();
                }
            }
            if (left > 0) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------- paying in

    /** Pays in the stack in the main hand. */
    public void depositHand(Player player) {
        int slot = player.getInventory().getHeldItemSlot();
        deposit(player, List.of(slot));
    }

    /** Pays in every piece of cash the player carries. */
    public void depositAll(Player player) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < contents.length; i++) {
            if (CashTags.isCash(contents[i])) {
                slots.add(i);
            }
        }
        if (slots.isEmpty()) {
            refuse(player, "economy.cash.none");
            return;
        }
        deposit(player, slots);
    }

    /** Pays in the cash in these slots — what is genuine of it; forgeries are confiscated. */
    private void deposit(Player player, List<Integer> slots) {
        EconomySettings live = settings;
        Currency currency = live.currency();
        if (!live.cashEnabled()) {
            refuse(player, "economy.cash.off");
            return;
        }
        PlayerInventory inventory = player.getInventory();
        Map<Integer, CashPiece> pieces = new LinkedHashMap<>();
        for (int slot : slots) {
            CashTags.read(inventory.getItem(slot)).ifPresent(piece -> pieces.put(slot, piece));
        }
        if (pieces.isEmpty()) {
            refuse(player, "economy.cash.none");
            return;
        }
        CashCheck check;
        try {
            check = this.check.check(pieces, COIN, seal::verify, book::noteValue, book::coinsOut);
        } catch (ArithmeticException absurd) {
            refuse(player, "economy.not-an-amount");
            return;
        }
        if (!check.confiscated().isEmpty()) {
            check.confiscated().forEach((slot, count) -> take(inventory, slot, count));
            confiscate(player, pieces, check);
        }
        if (check.overTheFloat() > 0) {
            refuse(player, "economy.cash.over-circulation", "count", String.valueOf(check.overTheFloat()));
            alertStaff("economy.cash.over-circulation-alert", "player", player.getName(),
                    "count", String.valueOf(check.overTheFloat()));
        }
        if (!check.total().isPositive()) {
            return;
        }
        de.raindancer.modules.economy.SupplySettings aging = supplied();
        de.raindancer.modules.economy.rules.NoteExpiryRule expiry = new de.raindancer.modules.economy.rules.NoteExpiryRule();
        long now = System.currentTimeMillis();
        EconomyResult result = book.redeemCash(player.getUniqueId(), check.serials(), check.coins(), check.total(),
                economy.most(), (face, issuedAt) -> expiry.worth(face, issuedAt, now,
                        aging.noteExpiry() ? aging.noteExpiryDays() : 0, aging.expiredNotePercent()));
        if (!result.succeeded()) {
            Outcomes.tell(messages, effects, player, result, currency, "");
            return;
        }
        check.taken().forEach((slot, count) -> take(inventory, slot, count - check.confiscated().getOrDefault(slot, 0)));
        economy.tell(player.getUniqueId(), result.amount(), result.balance(), TransactionKind.DEPOSIT);
        effects.play(player.getUniqueId(), Cues.EARNED);
        messages.send(player, "economy.cash.deposited", "amount", currency.render(result.amount()),
                "balance", currency.render(result.balance()));
        if (check.total().isMoreThan(result.amount())) {
            messages.send(player, "economy.cash.expired", "lost", currency.render(check.total().minus(result.amount())),
                    "days", String.valueOf(aging.noteExpiryDays()));
        }
    }

    /** Takes this many items out of one slot. */
    private static void take(PlayerInventory inventory, int slot, int count) {
        ItemStack stack = inventory.getItem(slot);
        if (stack == null || count <= 0) {
            return;
        }
        if (stack.getAmount() <= count) {
            inventory.setItem(slot, null);
        } else {
            stack.setAmount(stack.getAmount() - count);
            inventory.setItem(slot, stack);
        }
    }

    private void confiscate(Player player, Map<Integer, CashPiece> pieces, CashCheck check) {
        Currency currency = settings.currency();
        int count = check.confiscatedCount();
        refuse(player, "economy.cash.counterfeit", "count", String.valueOf(count));
        check.confiscated().forEach((slot, taken) -> {
            CashPiece piece = pieces.get(slot);
            audit.record(AuditEntry.of("economy", "counterfeit-cash")
                    .by(player.getUniqueId(), player.getName())
                    .saying("Cash that was already paid in, never issued, or not this server's was confiscated")
                    .with("serial", String.valueOf(piece.serial()))
                    .with("value", currency.format(piece.each()))
                    .with("count", taken)
                    .in(player.getWorld().getName()));
            alertStaff("economy.cash.counterfeit-alert", "player", player.getName(),
                    "serial", piece.numbered() ? piece.serial() : "(coin)", "amount", currency.render(piece.each()),
                    "count", String.valueOf(taken));
        });
    }

    private void alertStaff(String key, Object... values) {
        for (Player staff : server.getOnlinePlayers()) {
            if (staff.hasPermission(PermissionNodes.ALERTS)) {
                messages.send(staff, key, values);
            }
        }
    }

    private void refuse(Player player, String key, Object... values) {
        messages.send(player, key, values);
        effects.play(player.getUniqueId(), Cues.NO);
    }
}
