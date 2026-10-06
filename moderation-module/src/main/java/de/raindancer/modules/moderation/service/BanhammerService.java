package de.raindancer.modules.moderation.service;

import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.Sentence;
import de.raindancer.modules.moderation.rules.BanhammerRule;
import de.raindancer.modules.moderation.rules.StaffRule;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Swinging the Banhammer. The ban goes through {@link PunishmentService} — the same path as a typed
 * {@code /ban}, so it mirrors to the vanilla list, kicks, is audited and announced like any other.
 */
public final class BanhammerService implements IModerationService {

    private final PunishmentService punishments;
    private final StaffRule staffRule;
    private final Messages messages;
    private final BanhammerRule rule = new BanhammerRule();

    private volatile ModerationSettings settings;

    public BanhammerService(PunishmentService punishments, StaffRule staffRule, Messages messages,
                            ModerationSettings settings) {
        this.punishments = punishments;
        this.staffRule = staffRule;
        this.messages = messages;
        settings(settings);
    }

    @Override
    public void settings(ModerationSettings fresh) {
        this.settings = fresh;
    }

    /**
     * One player hit another; bans the victim if it was the Banhammer.
     *
     * @param swungByAttacker the hit was the attacker's own melee swing
     * @return whether somebody was banned
     */
    public boolean struck(Player attacker, Player victim, ItemStack weapon, boolean swungByAttacker) {
        Verdict verdict = rule.judge(new BanhammerRule.Strike(settings.banhammer(),
                attacker.hasPermission(ModerationPermission.BANHAMMER.node()),
                weapon == null ? null : weapon.getType(), plainName(weapon), swungByAttacker,
                attacker.getUniqueId().equals(victim.getUniqueId()),
                staffRule.isImmune(victim.getUniqueId()),
                punishments.isActive(victim.getUniqueId(), PunishmentKind.BAN)));
        if (verdict.isRefused()) {
            if (rule.tellsTheSwinger(verdict)) {
                messages.send(attacker, verdict.reason(), "player", victim.getName());
            }
            return false;
        }
        punishments.punish(attacker.getUniqueId(), attacker.getName(), victim.getUniqueId(), victim.getName(),
                PunishmentKind.BAN, Sentence.forEver(), BanhammerRule.reason(attacker.getName()));
        messages.send(attacker, "moderation.banhammer.struck", "player", victim.getName());
        return true;
    }

    /** The name as plain text — colours, gradients and decorations dropped — or null for none. */
    private static String plainName(ItemStack weapon) {
        if (weapon == null || weapon.getType() != Material.MACE || !weapon.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = weapon.getItemMeta();
        return meta.hasCustomName()
                ? PlainTextComponentSerializer.plainText().serialize(meta.customName())
                : null;
    }

    @Override
    public String describe() {
        return "the Banhammer: one hit with it is a permanent ban";
    }
}
