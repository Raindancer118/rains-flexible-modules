package de.raindancer.modules.essentials.service;

import de.raindancer.core.moderation.audit.Audit;
import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.essentials.EssentialsSettings;
import de.raindancer.modules.essentials.rules.EnchantRule;
import de.raindancer.modules.essentials.util.Enchantments;
import de.raindancer.modules.essentials.util.PermissionNodes;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Putting enchantments on the held item at levels vanilla would never give, and taking them off.
 *
 * <p>Edits a copy and puts it back, because what the hand returns is not guaranteed to write through.
 * Runs on the thread owning the one holding the item; the scheduling is injected so a test needs no
 * server. A book keeps its enchantments as <em>stored</em> ones — a plain enchantment on a book does
 * nothing at an anvil.
 */
public final class EnchantService implements IEssentialsService {

    private final Messages messages;
    private final Audit audit;
    private final BiConsumer<Player, Runnable> onOwnThread;
    private final EnchantRule rule = new EnchantRule();

    public EnchantService(Messages messages, Audit audit, BiConsumer<Player, Runnable> onOwnThread,
                          EssentialsSettings settings) {
        this.messages = messages;
        this.audit = audit;
        this.onOwnThread = onOwnThread;
        settings(settings);
    }

    @Override
    public void settings(EssentialsSettings fresh) {
        // Nothing here is configurable; the rule is the whole policy.
    }

    /** Whether this would be allowed — nothing is sent or changed, so a menu can grey a button with it. */
    public Verdict check(CommandSender actor, Player target, Enchantment enchantment, int level) {
        ItemStack held = target.getInventory().getItemInMainHand();
        boolean holding = held != null && !held.isEmpty();
        return rule.judge(EnchantRule.Request.builder()
                .holding(holding)
                .self(isSelf(actor, target))
                .mayOthers(actor.hasPermission(PermissionNodes.ENCHANT_OTHERS))
                .level(level)
                .vanillaMax(enchantment.getMaxLevel())
                .fitsItem(holding && enchantment.canEnchantItem(held))
                .present(holding && levelOn(held, enchantment) > 0)
                .mayBeyondMax(actor.hasPermission(PermissionNodes.ENCHANT_BEYOND_MAX))
                .mayAnyItem(actor.hasPermission(PermissionNodes.ENCHANT_ANY_ITEM))
                .build());
    }

    /** The highest level this actor may offer for it, for a chooser. */
    public int ceiling(CommandSender actor, Enchantment enchantment) {
        return EnchantRule.ceiling(enchantment.getMaxLevel(),
                actor.hasPermission(PermissionNodes.ENCHANT_BEYOND_MAX));
    }

    /**
     * @param level 0 takes it off
     * @return whether it was done — only meaningful where the target is owned by this thread, which the
     *         menu and a command on one's own item are
     */
    public boolean apply(CommandSender actor, Player target, Enchantment enchantment, int level) {
        boolean[] done = {false};
        onOwnThread.accept(target, () -> done[0] = applyHere(actor, target, enchantment, level));
        return done[0];
    }

    private boolean applyHere(CommandSender actor, Player target, Enchantment enchantment, int level) {
        Verdict verdict = check(actor, target, enchantment, level);
        if (verdict.isRefused()) {
            messages.send(actor, verdict.reason(), "detail", verdict.detail(),
                    "player", PlayerTargets.shownName(target), "enchantment", Enchantments.readable(enchantment));
            return false;
        }
        ItemStack item = target.getInventory().getItemInMainHand().clone();
        if (level == 0) {
            take(item, enchantment);
        } else {
            put(item, enchantment, level);
        }
        target.getInventory().setItemInMainHand(item);

        String line = Enchantments.line(enchantment, level);
        String itemName = Enchantments.readable(item.getType().name());
        String key = level == 0 ? "removed" : "applied";
        if (isSelf(actor, target)) {
            messages.send(actor, "essentials.enchant." + key, "enchantment", level == 0
                    ? Enchantments.readable(enchantment) : line, "item", itemName);
        } else {
            messages.send(actor, "essentials.enchant." + key + "-other", "player", PlayerTargets.shownName(target),
                    "enchantment", level == 0 ? Enchantments.readable(enchantment) : line, "item", itemName);
            messages.send(target, "essentials.enchant.by-staff", "enchantment",
                    level == 0 ? Enchantments.readable(enchantment) + " removed" : line, "item", itemName);
        }
        audit(actor, target, level == 0 ? "removed an enchantment" : "enchanted an item",
                level == 0 ? Enchantments.keyOf(enchantment) + " from " + item.getType().name()
                        : Enchantments.keyOf(enchantment) + " " + level + " on " + item.getType().name(),
                Enchantments.keyOf(enchantment), level, item.getType().name());
        return true;
    }

    /** Takes every enchantment off the held item. */
    public void clear(CommandSender actor, Player target) {
        onOwnThread.accept(target, () -> {
            ItemStack held = target.getInventory().getItemInMainHand();
            boolean holding = held != null && !held.isEmpty();
            boolean self = isSelf(actor, target);
            if (!self && !actor.hasPermission(PermissionNodes.ENCHANT_OTHERS)) {
                messages.send(actor, "essentials.enchant.not-others", "player", PlayerTargets.shownName(target));
                return;
            }
            if (!holding) {
                messages.send(actor, "essentials.enchant.nothing-held", "player", PlayerTargets.shownName(target));
                return;
            }
            ItemStack item = held.clone();
            List<Enchantment> present = new ArrayList<>(item.getEnchantments().keySet());
            if (item.getItemMeta() instanceof EnchantmentStorageMeta storage) {
                present.addAll(storage.getStoredEnchants().keySet());
            }
            if (present.isEmpty()) {
                messages.send(actor, "essentials.enchant.nothing-to-clear", "player", PlayerTargets.shownName(target));
                return;
            }
            present.forEach(enchantment -> take(item, enchantment));
            target.getInventory().setItemInMainHand(item);
            if (self) {
                messages.send(actor, "essentials.enchant.cleared", "count", present.size());
            } else {
                messages.send(actor, "essentials.enchant.cleared-other", "player", PlayerTargets.shownName(target),
                        "count", present.size());
                messages.send(target, "essentials.enchant.by-staff", "enchantment", "everything removed",
                        "item", Enchantments.readable(item.getType().name()));
            }
            audit(actor, target, "stripped an item of enchantments", present.size() + " from "
                    + item.getType().name(), "all", 0, item.getType().name());
        });
    }

    private static int levelOn(ItemStack item, Enchantment enchantment) {
        if (item.getItemMeta() instanceof EnchantmentStorageMeta storage) {
            return storage.getStoredEnchantLevel(enchantment);
        }
        return item.getEnchantmentLevel(enchantment);
    }

    private static void put(ItemStack item, Enchantment enchantment, int level) {
        if (item.getItemMeta() instanceof EnchantmentStorageMeta storage) {
            storage.addStoredEnchant(enchantment, level, true);
            item.setItemMeta(storage);
            return;
        }
        item.addUnsafeEnchantment(enchantment, level);
    }

    private static void take(ItemStack item, Enchantment enchantment) {
        if (item.getItemMeta() instanceof EnchantmentStorageMeta storage) {
            storage.removeStoredEnchant(enchantment);
            item.setItemMeta(storage);
        }
        item.removeEnchantment(enchantment);
    }

    private static boolean isSelf(CommandSender actor, Player target) {
        return actor instanceof Player who && who.getUniqueId().equals(target.getUniqueId());
    }

    private void audit(CommandSender actor, Player target, String action, String detail, String key, int level,
                       String material) {
        AuditEntry.Builder entry = AuditEntry.of("essentials", action)
                .to(target.getUniqueId(), target.getName())
                .saying(detail)
                .with("enchantment", key)
                .with("level", level)
                .with("item", material);
        if (actor instanceof Player who) {
            entry.by(who.getUniqueId(), who.getName());
        }
        audit.record(entry);
    }

    @Override
    public String describe() {
        return "enchanting the held item beyond what vanilla allows, and stripping it again";
    }
}
