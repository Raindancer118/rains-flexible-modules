package de.raindancer.modules.economy.store;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.CashPiece;
import de.raindancer.modules.economy.model.Form;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Objects;
import java.util.Optional;

/**
 * What is written on a coin or a note, and how it is read back.
 *
 * <p>In the persistent data container and nowhere else: a name and lore can be forged with an anvil. The
 * namespace is a literal, not the hosting plugin's, so cash stays cash when the module moves to another
 * host plugin.
 */
public final class CashTags {

    public static final NamespacedKey VALUE = key("value");
    public static final NamespacedKey FORM = key("form");
    public static final NamespacedKey SERIAL = key("serial");
    public static final NamespacedKey CHEQUE = key("cheque");
    public static final NamespacedKey ISSUER = key("issuer");

    private CashTags() {
    }

    private static NamespacedKey key(String name) {
        return Objects.requireNonNull(NamespacedKey.fromString("rainseconomy:" + name));
    }

    public static boolean isCash(ItemStack stack) {
        return container(stack).map(data -> data.has(VALUE, PersistentDataType.LONG)).orElse(false);
    }

    public static Optional<CashPiece> read(ItemStack stack) {
        Optional<PersistentDataContainer> data = container(stack);
        if (data.isEmpty() || !data.get().has(VALUE, PersistentDataType.LONG)) {
            return Optional.empty();
        }
        PersistentDataContainer tags = data.get();
        long value = Optional.ofNullable(tags.get(VALUE, PersistentDataType.LONG)).orElse(0L);
        if (value <= 0) {
            return Optional.empty();
        }
        Form form = "NOTE".equals(tags.get(FORM, PersistentDataType.STRING)) ? Form.NOTE : Form.COIN;
        String serial = tags.get(SERIAL, PersistentDataType.STRING);
        boolean cheque = tags.has(CHEQUE, PersistentDataType.BYTE);
        return Optional.of(new CashPiece(Money.of(value), stack.getAmount(), form, serial, cheque));
    }

    public static Optional<String> issuer(ItemStack stack) {
        return container(stack).map(data -> data.get(ISSUER, PersistentDataType.STRING));
    }

    /** Writes the value and form; a serial and an issuer only for numbered notes. Returns the same stack. */
    public static ItemStack stamp(ItemStack stack, Money value, Form form, String serial, boolean cheque,
                                  String issuer) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        PersistentDataContainer tags = meta.getPersistentDataContainer();
        tags.set(VALUE, PersistentDataType.LONG, value.minor());
        tags.set(FORM, PersistentDataType.STRING, form.name());
        if (serial != null) {
            tags.set(SERIAL, PersistentDataType.STRING, serial);
        }
        if (cheque) {
            tags.set(CHEQUE, PersistentDataType.BYTE, (byte) 1);
        }
        if (issuer != null) {
            tags.set(ISSUER, PersistentDataType.STRING, issuer);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private static Optional<PersistentDataContainer> container(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        ItemMeta meta = stack.getItemMeta();
        return meta == null ? Optional.empty() : Optional.of(meta.getPersistentDataContainer());
    }
}
