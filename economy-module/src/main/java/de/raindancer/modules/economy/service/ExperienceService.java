package de.raindancer.modules.economy.service;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.rules.ExperienceRule;
import de.raindancer.modules.economy.util.PermissionNodes;
import org.bukkit.entity.Player;

/**
 * Experience levels for money, both ways, priced per point: a level near 30 holds far more points than one
 * near 5, so it costs more, as it does to gather. Selling pays less than buying, so the two can never be
 * turned into money from nothing.
 *
 * <p>Reads and writes a player's experience only through Paper's {@code calculateTotalExperiencePoints} and
 * {@code setExperienceLevelAndProgress} — what the player can see and spend, not Bukkit's lifetime total.
 */
public final class ExperienceService implements IEconomyService {

    /** What a purchase or sale comes to, before it happens. */
    public record Quote(int levels, int points, Money money) {
    }

    private final RainEconomy economy;
    private final Messages messages;
    private final Effects effects;
    private final ExperienceRule rule = new ExperienceRule();
    private volatile EconomySettings settings;

    public ExperienceService(RainEconomy economy, Messages messages, Effects effects, EconomySettings settings) {
        this.economy = economy;
        this.messages = messages;
        this.effects = effects;
        settings(settings);
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
    }

    public ExperienceRule rule() {
        return rule;
    }

    public Quote buying(Player player, int levels) {
        int points = rule.pointsToGain(player.calculateTotalExperiencePoints(), levels);
        return new Quote(levels, points, settings.xpBuyMoney().times(points));
    }

    public Quote selling(Player player, int levels) {
        int points = rule.pointsToLose(player.calculateTotalExperiencePoints(), levels);
        return new Quote(levels, points, settings.xpSellMoney().times(points));
    }

    public boolean buy(Player player, int levels) {
        if (!open(player, PermissionNodes.SHOP)) {
            return false;
        }
        Currency currency = settings.currency();
        int had = player.calculateTotalExperiencePoints();
        int points = rule.pointsToGain(had, levels);
        if (points <= 0) {
            refuse(player, "economy.xp.top");
            return false;
        }
        Money cost = settings.xpBuyMoney().times(points);
        economy.open(player.getUniqueId(), player.getName());
        // Paid first: experience is handed out only once the money has left.
        EconomyResult paid = economy.move(player.getUniqueId(), cost.negate(), TransactionKind.BUY,
                "Experience: " + levels + " level(s)");
        if (!paid.succeeded()) {
            Outcomes.tell(messages, effects, player, paid, currency, "");
            return false;
        }
        player.setExperienceLevelAndProgress(had + points);
        messages.send(player, "economy.xp.bought", "levels", String.valueOf(player.getLevel() - rule.levelOf(had)),
                "amount", currency.render(cost), "level", String.valueOf(player.getLevel()));
        effects.play(player.getUniqueId(), GameSounds.WIN_STEP);
        return true;
    }

    /** {@code levels} of {@link Integer#MAX_VALUE} sells everything. */
    public boolean sell(Player player, int levels) {
        if (!open(player, PermissionNodes.SELL)) {
            return false;
        }
        Currency currency = settings.currency();
        int had = player.calculateTotalExperiencePoints();
        int points = rule.pointsToLose(had, levels);
        if (points <= 0) {
            refuse(player, "economy.xp.nothing");
            return false;
        }
        Money pay = settings.xpSellMoney().times(points);
        if (!pay.isPositive()) {
            refuse(player, "economy.xp.worthless");
            return false;
        }
        economy.open(player.getUniqueId(), player.getName());
        // Taken first, paid second, and given back if the bank would not take the money.
        player.setExperienceLevelAndProgress(had - points);
        EconomyResult paid = economy.move(player.getUniqueId(), pay, TransactionKind.SELL,
                "Experience: " + (rule.levelOf(had) - player.getLevel()) + " level(s)");
        if (!paid.succeeded()) {
            player.setExperienceLevelAndProgress(had);
            Outcomes.tell(messages, effects, player, paid, currency, "");
            return false;
        }
        messages.send(player, "economy.xp.sold", "levels", String.valueOf(rule.levelOf(had) - player.getLevel()),
                "amount", currency.render(pay), "level", String.valueOf(player.getLevel()));
        effects.play(player.getUniqueId(), GameSounds.CASH);
        return true;
    }

    private boolean open(Player player, String node) {
        if (!settings.xpTradeEnabled()) {
            refuse(player, "economy.xp.off");
            return false;
        }
        if (!player.hasPermission(node)) {
            refuse(player, "economy.not-allowed");
            return false;
        }
        return true;
    }

    private void refuse(Player player, String key) {
        messages.send(player, key);
        effects.play(player.getUniqueId(), Cues.NO);
    }
}
