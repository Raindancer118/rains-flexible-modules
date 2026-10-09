package de.raindancer.modules.economy.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.social.presence.Away;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.rules.ActivityRule;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Passive income: money for being online, every so many minutes — one amount while playing and another
 * while away. Who is away is Core's answer ({@link Away}, fed by the essentials module's {@code /afk});
 * only when nothing on the server tells Core does this look for itself, sampling once a minute where
 * somebody stands and looks.
 */
public final class IncomeService implements IEconomyService {

    private record Seen(String where, long movedAt, int minutes) {
    }

    private final Plugin plugin;
    private final RewardService rewards;
    private final Messages messages;
    private final LongSupplier clock;
    private final ActivityRule activity = new ActivityRule();
    private final Map<UUID, Seen> seen = new ConcurrentHashMap<>();
    private volatile EconomySettings settings;

    public IncomeService(Plugin plugin, RewardService rewards, Messages messages, LongSupplier clock,
                         EconomySettings settings) {
        this.plugin = plugin;
        this.rewards = rewards;
        this.messages = messages;
        this.clock = clock;
        settings(settings);
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
    }

    /** Once a minute. Each player is looked at on their own thread. */
    public void minute(Collection<? extends Player> online) {
        if (!settings.incomeEnabled()) {
            return;
        }
        for (Player player : online) {
            Scheduling.entity(plugin, player, () -> sample(player));
        }
    }

    private void sample(Player player) {
        EconomySettings live = settings;
        long now = clock.getAsLong();
        Location at = player.getLocation();
        String where = at.getWorld().getName() + ":" + at.getBlockX() + ":" + at.getBlockY() + ":" + at.getBlockZ()
                + ":" + Math.round(at.getYaw()) + ":" + Math.round(at.getPitch());
        Seen before = seen.get(player.getUniqueId());
        long movedAt = before == null || !before.where().equals(where) ? now : before.movedAt();
        int minutes = (before == null ? 0 : before.minutes()) + 1;
        if (minutes >= live.incomeMinutes()) {
            minutes = 0;
            boolean away = isAway(player.getUniqueId(), movedAt, now, live);
            Money amount = away ? live.incomeAwayMoney() : live.incomeMoney();
            if (amount.isPositive()) {
                EconomyResult paid = rewards.pay(player, amount, away ? "Income (away)" : "Income",
                        TransactionKind.INCOME);
                if (paid.succeeded()) {
                    messages.send(player, away ? "economy.earn.income-away" : "economy.earn.income",
                            "amount", live.currency().render(paid.amount()), "minutes", String.valueOf(live.incomeMinutes()));
                }
            }
        }
        seen.put(player.getUniqueId(), new Seen(where, movedAt, minutes));
    }

    /** Core's answer when something on the server gives one; this module's own sampling otherwise. */
    boolean isAway(UUID player, long movedAt, long now, EconomySettings live) {
        if (Away.isKnown()) {
            return Away.isAway(player);
        }
        return !activity.active(movedAt, now, live.afkMinutes());
    }

    public void forget(UUID player) {
        seen.remove(player);
    }
}
