package de.raindancer.modules.economy.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.EconomyResult;
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
 * Paid for playing: one salary per so many minutes of <em>active</em> play. Activity is sampled once a
 * minute — where somebody stands and where they look — rather than from every move event, which fire
 * thousands of times a second on a busy server.
 */
public final class SalaryService implements IEconomyService {

    private record Seen(String where, long movedAt, int activeMinutes) {
    }

    private final Plugin plugin;
    private final RewardService rewards;
    private final Messages messages;
    private final LongSupplier clock;
    private final ActivityRule activity = new ActivityRule();
    private final Map<UUID, Seen> seen = new ConcurrentHashMap<>();
    private volatile EconomySettings settings;

    public SalaryService(Plugin plugin, RewardService rewards, Messages messages, LongSupplier clock,
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
        if (!settings.salaryEnabled()) {
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
        int minutes = before == null ? 0 : before.activeMinutes();
        if (activity.active(movedAt, now, live.afkMinutes())) {
            minutes++;
        }
        if (minutes >= live.salaryMinutes()) {
            minutes = 0;
            EconomyResult paid = rewards.pay(player, live.salaryMoney(), "Salary", TransactionKind.SALARY);
            if (paid.succeeded()) {
                messages.send(player, "economy.earn.salary", "amount", live.currency().render(live.salaryMoney()),
                        "minutes", String.valueOf(live.salaryMinutes()));
            }
        }
        seen.put(player.getUniqueId(), new Seen(where, movedAt, minutes));
    }

    public void forget(UUID player) {
        seen.remove(player);
    }
}
