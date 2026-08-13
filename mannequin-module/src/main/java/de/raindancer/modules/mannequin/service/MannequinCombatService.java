package de.raindancer.modules.mannequin.service;

import de.raindancer.core.ui.actionbar.ActionBarPriority;
import de.raindancer.core.ui.actionbar.ActionBars;
import de.raindancer.modules.mannequin.MannequinSettings;
import de.raindancer.modules.mannequin.model.ItemSpec;
import de.raindancer.modules.mannequin.model.Mannequin;
import de.raindancer.modules.mannequin.model.TrainingSession;
import de.raindancer.modules.mannequin.rules.ComboWindowRule;
import de.raindancer.modules.mannequin.rules.LethalHitRule;
import de.raindancer.modules.mannequin.rules.SignalStrengthRule;
import de.raindancer.modules.mannequin.store.MannequinRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;

import java.time.Duration;
import java.util.Map;

/**
 * What happens when a by-player hit lands on a tracked mannequin: everything except the actual
 * cancelling of the damage, which stays in {@link de.raindancer.modules.mannequin.listener.MannequinCombatListener}
 * so this class can be asked "what happened" without a Bukkit event to construct.
 */
public final class MannequinCombatService implements IMannequinService {

    private static final String ACTION_BAR_OWNER = "mannequin";

    private final MannequinRegistry registry;
    private final MannequinEquipService equip;
    private final MannequinRedstoneService redstone;
    private final ActionBars actionBars;
    private final ComboWindowRule comboWindowRule;
    private final LethalHitRule lethalHitRule;
    private final SignalStrengthRule signalStrengthRule;
    private volatile MannequinSettings settings;

    public MannequinCombatService(MannequinRegistry registry, MannequinEquipService equip,
                                  MannequinRedstoneService redstone, ActionBars actionBars,
                                  ComboWindowRule comboWindowRule, LethalHitRule lethalHitRule,
                                  SignalStrengthRule signalStrengthRule, MannequinSettings settings) {
        this.registry = registry;
        this.equip = equip;
        this.redstone = redstone;
        this.actionBars = actionBars;
        this.comboWindowRule = comboWindowRule;
        this.lethalHitRule = lethalHitRule;
        this.signalStrengthRule = signalStrengthRule;
        this.settings = settings;
    }

    @Override
    public void settings(MannequinSettings settings) {
        this.settings = settings;
    }

    private static final java.util.Random RNG = new java.util.Random();

    /**
     * @param mannequin      the stored record — for its loadout specs and its redstone opt-in
     * @param entity         the live entity, so armor durability can be read back and changed
     * @param attacker       who landed the hit
     * @param finalDamage    {@code EntityDamageEvent#getFinalDamage()}, read as the hit lands
     * @param now            when the hit landed
     */
    public void recordHit(Mannequin mannequin, org.bukkit.entity.Mannequin entity, Player attacker,
                          double finalDamage, long now) {
        MannequinSettings current = settings;
        String id = mannequin.id();

        TrainingSession previous = registry.sessionFor(id);
        boolean continues = comboWindowRule.continuesCombo(previous.lastHitAt(), now,
                current.comboWindow());
        TrainingSession updated = previous.hit(finalDamage, now, continues);
        registry.updateSession(id, updated);

        for (Map.Entry<EquipmentSlot, ItemSpec> entry : mannequin.loadout().entrySet()) {
            EquipmentSlot slot = entry.getKey();
            if (slot == EquipmentSlot.HEAD || slot == EquipmentSlot.CHEST
                    || slot == EquipmentSlot.LEGS || slot == EquipmentSlot.FEET) {
                equip.damageEquippedPiece(entity, slot, entry.getValue(), RNG);
            }
        }

        boolean lethal = lethalHitRule.wouldHaveKilledUnarmoredPlayer(finalDamage);
        int signal = signalStrengthRule.signalFor(finalDamage, current.oneShotThresholdDamage());

        if (mannequin.emitsRedstoneSignal()) {
            Block barrel = entity.getWorld().getBlockAt(mannequin.x(), mannequin.barrelY(), mannequin.z());
            if (barrel.getState() instanceof Barrel || barrel.getType() == Material.AIR) {
                redstone.pulse(barrel, signal, current.redstonePulseTicksClamped());
            }
        }

        showFeedback(attacker, updated, finalDamage, lethal);
    }

    private void showFeedback(Player attacker, TrainingSession session, double damage, boolean lethal) {
        if (actionBars == null) {
            return;
        }
        StringBuilder line = new StringBuilder("<yellow>")
                .append(String.format(java.util.Locale.ROOT, "%.1f", damage))
                .append(" damage <gray>· combo <white>")
                .append(session.comboStreak());
        if (lethal) {
            line.append(" <red><bold>· would have killed an unarmored player");
        }
        Component rendered = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                .deserialize(line.toString())
                .colorIfAbsent(NamedTextColor.YELLOW);
        actionBars.show(attacker.getUniqueId(), ACTION_BAR_OWNER, rendered,
                Duration.ofSeconds(3), ActionBarPriority.NORMAL);
    }
}
