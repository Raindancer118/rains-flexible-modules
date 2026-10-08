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
import de.raindancer.modules.economy.model.Denomination;
import de.raindancer.modules.economy.model.Form;
import de.raindancer.modules.economy.model.Split;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.rules.ChangeRule;
import de.raindancer.modules.economy.store.AccountBook;
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
 * Money as things you can hold: withdrawing coins, notes and cheques, and paying them back in.
 *
 * <h2>The two promises</h2>
 * <ul>
 *   <li>Nothing leaves the account without the items existing, and nothing is credited without the items
 *       going. Withdrawing checks the room first; paying in removes the pieces only once the ledger has
 *       accepted them.</li>
 *   <li>A numbered note is paid in once. A second copy — a duplication glitch, a hacked client — is
 *       confiscated, recorded, and reported to staff, and nothing is credited for it.</li>
 * </ul>
 */
public final class CashService implements IEconomyService {

    private static final char[] SERIAL_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final Server server;
    private final RainEconomy economy;
    private final AccountBook book;
    private final Messages messages;
    private final Effects effects;
    private final Audit audit;
    private final ChangeRule change = new ChangeRule();
    private final SecureRandom random = new SecureRandom();
    private volatile EconomySettings settings;
    private volatile List<Denomination> denominations = List.of();

    public CashService(Server server, RainEconomy economy, Messages messages, Effects effects, Audit audit,
                       EconomySettings settings) {
        this.server = server;
        this.economy = economy;
        this.book = economy.book();
        this.messages = messages;
        this.effects = effects;
        this.audit = audit;
        settings(settings);
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
        this.denominations = Denomination.parseAll(this.settings.denominations(), this.settings.currency()).stream()
                .filter(each -> each.material().isItem())
                .toList();
    }

    public List<Denomination> denominations() {
        return denominations;
    }

    // ---------------------------------------------------------------------------- making cash

    /** One piece of a denomination, numbered when it is a note and numbering is on. */
    public ItemStack piece(Denomination denomination, String serial) {
        return forge(denomination.material(), denomination.value(), denomination.form(), serial, false, null);
    }

    public ItemStack forge(Material material, Money value, Form form, String serial, boolean cheque, String issuer) {
        Currency currency = settings.currency();
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        String kind = cheque ? "Cheque" : form == Form.NOTE ? "Banknote" : currency.nameFor(value);
        meta.displayName(Component.text().append(currency.render(value))
                .append(Component.text(" " + kind, NamedTextColor.GRAY)).build()
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
        if (form == Form.NOTE) {
            meta.setEnchantmentGlintOverride(true);
        }
        stack.setItemMeta(meta);
        CashTags.stamp(stack, value, form, serial, cheque, issuer);
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

    /** Takes an amount out as the fewest pieces, or as one cheque. */
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
        List<ItemStack> items = new ArrayList<>();
        Map<String, Money> numbered = new LinkedHashMap<>();
        Money total;
        if (asCheque) {
            if (!live.chequesEnabled()) {
                refuse(player, "economy.cash.cheques-off");
                return;
            }
            String serial = serial();
            numbered.put(serial, amount);
            items.add(forge(Material.PAPER, amount, Form.NOTE, serial, true, player.getName()));
            total = amount;
        } else {
            Split split = change.split(amount, denominations);
            if (split.count() == 0) {
                refuse(player, "economy.cash.too-small", "amount", currency.render(amount));
                return;
            }
            if (split.count() > live.mostPieces()) {
                refuse(player, "economy.cash.too-many", "pieces", String.valueOf(split.count()),
                        "most", String.valueOf(live.mostPieces()));
                return;
            }
            for (Map.Entry<Denomination, Integer> each : split.pieces().entrySet()) {
                Denomination denomination = each.getKey();
                if (denomination.form() == Form.NOTE) {
                    for (int i = 0; i < each.getValue(); i++) {
                        String serial = live.serialNotes() ? serial() : null;
                        if (serial != null) {
                            numbered.put(serial, denomination.value());
                        }
                        items.add(piece(denomination, serial));
                    }
                } else {
                    ItemStack coin = piece(denomination, null);
                    int left = each.getValue();
                    int stack = Math.max(1, coin.getMaxStackSize());
                    while (left > 0) {
                        ItemStack part = coin.clone();
                        part.setAmount(Math.min(stack, left));
                        items.add(part);
                        left -= part.getAmount();
                    }
                }
            }
            total = split.paidOut();
            if (split.leftover().isPositive()) {
                messages.send(player, "economy.cash.leftover", "amount", currency.render(split.leftover()));
            }
        }
        if (!fits(player.getInventory(), items)) {
            refuse(player, "economy.cash.no-room");
            return;
        }
        Money fee = total.share(live.withdrawFee());
        EconomyResult result = book.issueCash(player.getUniqueId(), numbered, total, fee, economy.most());
        if (!result.succeeded()) {
            Outcomes.tell(messages, effects, player, EconomyResult.failed(result.outcome(), total.plus(fee),
                    result.balance()), currency, "");
            return;
        }
        player.getInventory().addItem(items.toArray(ItemStack[]::new)).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        economy.tell(player.getUniqueId(), total.plus(fee).negate(), result.balance(), TransactionKind.WITHDRAW);
        effects.play(player.getUniqueId(), Cues.REWARD);
        messages.send(player, asCheque ? "economy.cash.cheque-written" : "economy.cash.withdrawn",
                "amount", currency.render(total), "balance", currency.render(result.balance()));
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

    /** What the cash in these slots would pay in, and which of it is counterfeit. */
    private void deposit(Player player, List<Integer> slots) {
        EconomySettings live = settings;
        Currency currency = live.currency();
        if (!live.cashEnabled()) {
            refuse(player, "economy.cash.off");
            return;
        }
        PlayerInventory inventory = player.getInventory();
        List<Integer> good = new ArrayList<>();
        List<String> serials = new ArrayList<>();
        Money total = Money.ZERO;
        int counterfeit = 0;
        for (int slot : slots) {
            ItemStack stack = inventory.getItem(slot);
            CashPiece piece = CashTags.read(stack).orElse(null);
            if (piece == null) {
                continue;
            }
            if (piece.numbered()) {
                java.util.Optional<Money> registered = book.noteValue(piece.serial());
                if (registered.isEmpty() || !registered.get().equals(piece.each()) || serials.contains(piece.serial())) {
                    counterfeit++;
                    confiscate(player, slot, piece);
                    continue;
                }
                serials.add(piece.serial());
            }
            try {
                total = total.plus(piece.total());
            } catch (ArithmeticException overflow) {
                break;
            }
            good.add(slot);
        }
        if (counterfeit > 0) {
            refuse(player, "economy.cash.counterfeit", "count", String.valueOf(counterfeit));
        }
        if (good.isEmpty()) {
            if (counterfeit == 0) {
                refuse(player, "economy.cash.none");
            }
            return;
        }
        EconomyResult result = book.redeemCash(player.getUniqueId(), serials, total, economy.most());
        if (!result.succeeded()) {
            Outcomes.tell(messages, effects, player, result, currency, "");
            return;
        }
        for (int slot : good) {
            inventory.setItem(slot, null);
        }
        economy.tell(player.getUniqueId(), total, result.balance(), TransactionKind.DEPOSIT);
        effects.play(player.getUniqueId(), Cues.EARNED);
        messages.send(player, "economy.cash.deposited", "amount", currency.render(total),
                "balance", currency.render(result.balance()));
    }

    private void confiscate(Player player, int slot, CashPiece piece) {
        player.getInventory().setItem(slot, null);
        Currency currency = settings.currency();
        audit.record(AuditEntry.of("economy", "counterfeit-note")
                .by(player.getUniqueId(), player.getName())
                .saying("A note that was already paid in, or never issued, was confiscated")
                .with("serial", String.valueOf(piece.serial()))
                .with("value", currency.format(piece.each()))
                .with("count", piece.count())
                .in(player.getWorld().getName()));
        for (Player staff : server.getOnlinePlayers()) {
            if (staff.hasPermission(PermissionNodes.ALERTS)) {
                messages.send(staff, "economy.cash.counterfeit-alert", "player", player.getName(),
                        "serial", String.valueOf(piece.serial()), "amount", currency.render(piece.each()));
            }
        }
    }

    private void refuse(Player player, String key, Object... values) {
        messages.send(player, key, values);
        effects.play(player.getUniqueId(), Cues.NO);
    }
}
