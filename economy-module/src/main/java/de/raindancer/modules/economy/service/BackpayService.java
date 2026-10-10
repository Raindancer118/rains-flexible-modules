package de.raindancer.modules.economy.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.EconomyLevers;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.Sources;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.rules.BackpayRule;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Back pay, once per player, for advancements made before they paid — or paid less than they pay now. What
 * was paid already is read from the ledger ("Advancement: <title>"), so nothing is paid twice; whether a
 * player has claimed is kept on the player.
 */
public final class BackpayService implements IEconomyService {

    private static final String PREFIX = "Advancement: ";

    private final Plugin plugin;
    private final RainEconomy economy;
    private final Messages messages;
    private final NamespacedKey claimed;
    private final BackpayRule rule = new BackpayRule();
    private volatile EconomySettings settings;

    public BackpayService(Plugin plugin, RainEconomy economy, Messages messages, EconomySettings settings) {
        this.plugin = plugin;
        this.economy = economy;
        this.messages = messages;
        this.claimed = new NamespacedKey(plugin, "advancement-backpay");
        settings(settings);
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
    }

    private boolean hasClaimed(Player player) {
        return player.getPersistentDataContainer().has(claimed, PersistentDataType.BYTE);
    }

    /** The paying advancements this player has made. On the player's own thread. */
    private List<BackpayRule.Made> made(Player player) {
        List<BackpayRule.Made> made = new ArrayList<>();
        Iterator<Advancement> all = player.getServer().advancementIterator();
        while (all.hasNext()) {
            Advancement advancement = all.next();
            var display = advancement.getDisplay();
            if (display != null && display.doesAnnounceToChat() && player.getAdvancementProgress(advancement).isDone()) {
                made.add(new BackpayRule.Made(PlainTextComponentSerializer.plainText().serialize(display.title()),
                        display.frame()));
            }
        }
        return made;
    }

    /** Works out what is owed — the ledger is read off the server's threads — and hands it back on the player's. */
    private void owed(Player player, Consumer<BackpayRule.Owed> then) {
        Scheduling.entity(plugin, player, () -> {
            List<BackpayRule.Made> made = made(player);
            EconomySettings live = settings;
            Scheduling.async(plugin, () -> {
                BackpayRule.Owed owed = rule.owed(made, economy.book().paidFor(player.getUniqueId(), PREFIX),
                        live::advancementMoney);
                Scheduling.entity(plugin, player, () -> {
                    if (player.isOnline()) {
                        then.accept(owed);
                    }
                });
            });
        });
    }

    /** A line at join for somebody with back pay waiting. */
    public void remind(Player player) {
        if (!settings.advancementBackpay() || !settings.advancementRewardsEnabled() || hasClaimed(player)) {
            return;
        }
        owed(player, owed -> {
            if (owed.count() > 0) {
                messages.send(player, "economy.backpay.waiting", "count", String.valueOf(owed.count()),
                        "amount", settings.currency().render(owed.total()));
            }
        });
    }

    /** {@code /claimadvancements}: pays it all, once. */
    public void claim(Player player) {
        if (!settings.advancementBackpay() || !settings.advancementRewardsEnabled()) {
            messages.send(player, "economy.backpay.off");
            return;
        }
        if (hasClaimed(player)) {
            messages.send(player, "economy.backpay.already");
            return;
        }
        owed(player, owed -> {
            if (hasClaimed(player)) {
                messages.send(player, "economy.backpay.already");
                return;
            }
            if (owed.count() == 0) {
                messages.send(player, "economy.backpay.nothing");
                return;
            }
            // Marked first: a second claim racing this one finds it taken. Unmarked again if the pay is refused.
            player.getPersistentDataContainer().set(claimed, PersistentDataType.BYTE, (byte) 1);
            economy.open(player.getUniqueId(), player.getName());
            Money paying = EconomyLevers.faucet(Sources.REWARD, owed.total());
            EconomyResult result = paying.isPositive()
                    ? economy.move(player.getUniqueId(), paying, TransactionKind.REWARD,
                    "Advancements made before they paid (" + owed.count() + ")")
                    : EconomyResult.failed(EconomyResult.Outcome.TREASURY_EMPTY, owed.total(), Money.ZERO);
            if (!result.succeeded()) {
                player.getPersistentDataContainer().remove(claimed);
                messages.send(player, "economy.backpay.refused");
                return;
            }
            messages.send(player, "economy.backpay.paid", "count", String.valueOf(owed.count()),
                    "amount", settings.currency().render(result.amount()));
        });
    }
}
