package de.raindancer.modules.economy.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.ItemValues;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.SupplySettings;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.rules.RepairRule;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Optional;

/** {@code /repair}: the item in hand made whole for money, which leaves the economy. */
public final class RepairService implements IEconomyService {

    public static final String SOURCE = de.raindancer.modules.economy.model.Sources.REPAIR;

    private final Plugin plugin;
    private final RainEconomy economy;
    private final Messages messages;
    private final Effects effects;
    private final ChatButtons buttons;
    private final SupplyService supply;
    private final RepairRule rule = new RepairRule();
    private volatile EconomySettings settings;

    public RepairService(Plugin plugin, RainEconomy economy, Messages messages, Effects effects, ChatButtons buttons,
                         SupplyService supply, EconomySettings settings) {
        this.plugin = plugin;
        this.economy = economy;
        this.messages = messages;
        this.effects = effects;
        this.buttons = buttons;
        this.supply = supply;
        settings(settings);
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
    }

    /** What repairing this stack costs now; empty when there is nothing to repair. */
    public Optional<Money> price(ItemStack held) {
        if (held == null || held.getType().isAir() || !(held.getItemMeta() instanceof Damageable damaged)
                || !damaged.hasDamage()) {
            return Optional.empty();
        }
        SupplySettings live = SupplyService.settingsOf(supply);
        ItemStack whole = held.clone();
        whole.setAmount(1);
        whole.editMeta(Damageable.class, meta -> meta.setDamage(0));
        int most = damaged.hasMaxDamage() ? damaged.getMaxDamage() : held.getType().getMaxDurability();
        return rule.price(ItemValues.valueOf(whole), damaged.getDamage(), most, live.repairPercent(),
                SupplySettings.money(live.repairLeast(), settings.currency()))
                .map(price -> de.raindancer.core.social.economy.EconomyLevers.sink(SOURCE, price));
    }

    /** Shows the price with a button; the click repairs, if it is still the same item and the same price. */
    public void offer(Player player) {
        if (!SupplyService.settingsOf(supply).repair()) {
            refuse(player, "economy.repair.off");
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        Optional<Money> price = price(held);
        if (price.isEmpty()) {
            refuse(player, "economy.repair.nothing");
            return;
        }
        Currency currency = settings.currency();
        int slot = player.getInventory().getHeldItemSlot();
        ItemStack seen = held.clone();
        Money quoted = price.get();
        messages.send(player, "economy.repair.offer", "amount", currency.render(quoted),
                "button", buttons.label("<green>[Repair]</green>").tooltip("<gray>Pay and repair it")
                        .forOnly(player.getUniqueId()).expiringIn(Duration.ofSeconds(30))
                        .does(clicker -> Scheduling.entity(plugin, player, () -> repair(player, slot, seen, quoted)))
                        .render());
    }

    void repair(Player player, int slot, ItemStack seen, Money quoted) {
        ItemStack now = player.getInventory().getItem(slot);
        if (now == null || !now.isSimilar(seen) || now.getAmount() != seen.getAmount()
                || !price(now).map(quoted::equals).orElse(false)) {
            refuse(player, "economy.repair.changed");
            return;
        }
        Currency currency = settings.currency();
        EconomyResult paid = economy.move(player.getUniqueId(), quoted.negate(), TransactionKind.REPAIR,
                now.getType().name().toLowerCase(java.util.Locale.ROOT), SOURCE);
        if (!paid.succeeded()) {
            Outcomes.tell(messages, effects, player, paid, currency, "");
            return;
        }
        boolean resetAnvil = SupplyService.settingsOf(supply).repairResetsAnvil();
        now.editMeta(meta -> {
            if (meta instanceof Damageable damageable) {
                damageable.setDamage(0);
            }
            if (resetAnvil && meta instanceof Repairable repairable) {
                repairable.setRepairCost(0);
            }
        });
        player.getInventory().setItem(slot, now);
        effects.play(player.getUniqueId(), Cues.OK);
        messages.send(player, "economy.repair.done", "amount", currency.render(quoted),
                "balance", currency.render(paid.balance()));
    }

    private void refuse(Player player, String key) {
        messages.send(player, key);
        effects.play(player.getUniqueId(), Cues.NO);
    }
}
