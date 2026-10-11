package de.raindancer.modules.sends.rules;

import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * What may go in a send: something, and nothing the server keeps with its holder.
 *
 * <p>The predicate is a seam: Core's bound and insured items in the live module, a lambda in a test that has no
 * server to read item data from.
 */
public final class SendableItemsRule implements ISendsRule {

    private final Predicate<ItemStack> keptWithHolder;

    public SendableItemsRule(Predicate<ItemStack> keptWithHolder) {
        this.keptWithHolder = keptWithHolder;
    }

    public Optional<String> refusal(List<ItemStack> items) {
        if (items.isEmpty()) {
            return Optional.of("sends.refuse.empty");
        }
        for (ItemStack item : items) {
            if (keptWithHolder.test(item)) {
                return Optional.of("sends.refuse.unsendable");
            }
        }
        return Optional.empty();
    }

    @Override
    public String describe() {
        return "a send holds something, and nothing bound to its holder";
    }
}
