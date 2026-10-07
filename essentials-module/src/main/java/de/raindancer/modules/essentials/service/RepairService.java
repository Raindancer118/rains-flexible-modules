package de.raindancer.modules.essentials.service;

import de.raindancer.core.moderation.audit.Audit;
import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.essentials.EssentialsSettings;
import de.raindancer.modules.essentials.model.Wear;
import de.raindancer.modules.essentials.rules.RepairRule;
import de.raindancer.modules.essentials.util.Enchantments;
import de.raindancer.modules.essentials.util.PermissionNodes;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.function.BiConsumer;

/**
 * Mending what is worn: the item in the hand, or everything in the inventory.
 *
 * <p>Runs on the thread that owns the player being repaired — that is who is edited, not who typed the
 * command — and the scheduling is injected so a test needs no server.
 */
public final class RepairService implements IEssentialsService {

    private final Messages messages;
    private final Audit audit;
    private final BiConsumer<Player, Runnable> onOwnThread;
    private final RepairRule rule = new RepairRule();

    public RepairService(Messages messages, Audit audit, BiConsumer<Player, Runnable> onOwnThread,
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

    public void repairHand(CommandSender actor, Player target) {
        onOwnThread.accept(target, () -> {
            ItemStack held = target.getInventory().getItemInMainHand();
            Verdict verdict = rule.judge(new RepairRule.Request(RepairRule.Scope.HAND, isSelf(actor, target),
                    actor.hasPermission(PermissionNodes.REPAIR_OTHERS),
                    actor.hasPermission(PermissionNodes.REPAIR_ALL), wearOf(held)));
            if (verdict.isRefused()) {
                messages.send(actor, verdict.reason(), "player", PlayerTargets.shownName(target));
                return;
            }
            ItemStack mended = held.clone();
            mend(mended);
            target.getInventory().setItemInMainHand(mended);
            String item = Enchantments.readable(mended.getType().name());
            audit(actor, target, "repaired a held item", item);
            if (isSelf(actor, target)) {
                messages.send(actor, "essentials.repair.hand", "item", item);
                return;
            }
            messages.send(actor, "essentials.repair.hand-other", "player", PlayerTargets.shownName(target),
                    "item", item);
            messages.send(target, "essentials.repair.by-staff", "what", item);
        });
    }

    public void repairAll(CommandSender actor, Player target) {
        onOwnThread.accept(target, () -> {
            Verdict verdict = rule.judge(new RepairRule.Request(RepairRule.Scope.ALL, isSelf(actor, target),
                    actor.hasPermission(PermissionNodes.REPAIR_OTHERS),
                    actor.hasPermission(PermissionNodes.REPAIR_ALL), Wear.EMPTY));
            if (verdict.isRefused()) {
                messages.send(actor, verdict.reason(), "player", PlayerTargets.shownName(target));
                return;
            }
            PlayerInventory inventory = target.getInventory();
            ItemStack[] storage = inventory.getStorageContents();
            ItemStack[] armour = inventory.getArmorContents();
            ItemStack[] extra = inventory.getExtraContents();
            int mended = mendAll(storage) + mendAll(armour) + mendAll(extra);
            if (mended == 0) {
                messages.send(actor, isSelf(actor, target) ? "essentials.repair.all-none"
                        : "essentials.repair.all-none-other", "player", PlayerTargets.shownName(target));
                return;
            }
            inventory.setStorageContents(storage);
            inventory.setArmorContents(armour);
            inventory.setExtraContents(extra);
            String things = mended + (mended == 1 ? " thing" : " things");
            audit(actor, target, "repaired a whole inventory", things);
            if (isSelf(actor, target)) {
                messages.send(actor, "essentials.repair.all", "things", things);
                return;
            }
            messages.send(actor, "essentials.repair.all-other", "player", PlayerTargets.shownName(target),
                    "things", things);
            messages.send(target, "essentials.repair.by-staff", "what", things);
        });
    }

    private int mendAll(ItemStack[] stacks) {
        int mended = 0;
        for (int slot = 0; slot < stacks.length; slot++) {
            ItemStack item = stacks[slot];
            if (item == null || !RepairRule.needsRepair(wearOf(item))) {
                continue;
            }
            ItemStack copy = item.clone();
            if (mend(copy)) {
                stacks[slot] = copy;
                mended++;
            }
        }
        return mended;
    }

    private static Wear wearOf(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return Wear.EMPTY;
        }
        return item.getItemMeta() instanceof Damageable meta
                ? new Wear(true, true, meta.getDamage())
                : new Wear(true, false, 0);
    }

    private static boolean mend(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable worn) || worn.getDamage() <= 0) {
            return false;
        }
        worn.setDamage(0);
        item.setItemMeta(meta);
        return true;
    }

    private static boolean isSelf(CommandSender actor, Player target) {
        return actor instanceof Player who && who.getUniqueId().equals(target.getUniqueId());
    }

    private void audit(CommandSender actor, Player target, String action, String detail) {
        AuditEntry.Builder entry = AuditEntry.of("essentials", action)
                .to(target.getUniqueId(), target.getName())
                .saying(detail);
        if (actor instanceof Player who) {
            entry.by(who.getUniqueId(), who.getName());
        }
        audit.record(entry);
    }

    @Override
    public String describe() {
        return "repairing the item in hand, or everything somebody carries";
    }
}
