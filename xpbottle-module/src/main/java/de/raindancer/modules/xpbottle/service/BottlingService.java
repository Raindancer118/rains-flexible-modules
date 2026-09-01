package de.raindancer.modules.xpbottle.service;

import de.raindancer.core.platform.util.Cooldowns;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.xpbottle.XpBottleSettings;
import de.raindancer.modules.xpbottle.model.Bottle;
import de.raindancer.modules.xpbottle.model.Bottling;
import de.raindancer.modules.xpbottle.rules.FillAmountRule;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Moving experience between a player and a bottle — the half of this module that touches a live
 * player, and the only place that does.
 *
 * <h2>Why {@code calculateTotalExperiencePoints} and not {@code getTotalExperience}</h2>
 * Bukkit's {@code getTotalExperience} is a running total of everything a player has ever picked up.
 * It is not reduced by enchanting, so on any player who has used an enchanting table it reads higher
 * — sometimes far higher — than what they are actually carrying, and a bottle filled from it hands
 * out experience that does not exist. Paper's {@code calculateTotalExperiencePoints} is derived from
 * the level and the progress bar, which is what the player can actually see and spend, and
 * {@code setExperienceLevelAndProgress} is its exact inverse. Those two, and nothing else, all the
 * way through.
 *
 * <h2>Why nothing is ever taken before the bottle exists</h2>
 * Every path here computes the move first, changes the item second and the player third. A player
 * whose inventory was full at the wrong moment would otherwise be one whose experience went into a
 * bottle that was never made.
 */
public final class BottlingService implements IXpBottleService {

    private final Messages messages;
    private final Effects effects;
    private final FillAmountRule fill;
    private final BottleForge forge;
    private final Cooldowns<UUID> between = new Cooldowns<>();

    private volatile XpBottleSettings settings;

    public BottlingService(Messages messages, Effects effects, FillAmountRule fill,
                           BottleForge forge, XpBottleSettings settings) {
        this.messages = messages;
        this.effects = effects;
        this.fill = fill;
        this.forge = forge;
        settings(settings);
    }

    @Override
    public void settings(XpBottleSettings updated) {
        this.settings = updated == null ? XpBottleSettings.DEFAULTS : updated;
        between.every(Duration.ofSeconds(Math.max(0, this.settings.fillCooldownSeconds())));
    }

    /** What the player is carrying right now, in points they can actually spend. */
    public int experienceOf(Player player) {
        return player == null ? 0 : player.calculateTotalExperiencePoints();
    }

    /**
     * Takes points out of a player. Never more than they have.
     *
     * @return how many actually came out
     */
    public int takeFrom(Player player, int points) {
        if (player == null || points <= 0) {
            return 0;
        }
        int have = experienceOf(player);
        int taking = Math.min(have, points);
        if (taking <= 0) {
            return 0;
        }
        player.setExperienceLevelAndProgress(have - taking);
        return taking;
    }

    /**
     * Fills a plain glass bottle from what the holder is carrying.
     *
     * <p>The whole of the plain path: one click, one bottle out of the stack, the points gone from
     * the bar and into the item. The cooldown is charged only when it worked, so a click that found
     * nothing to take costs nothing.
     *
     * @param held the stack in the player's hand, which loses one bottle when this succeeds
     */
    public Bottling fillPlain(Player player, ItemStack held) {
        XpBottleSettings live = settings;
        Bottle bottle = Bottle.empty(live.capacityFor(0));
        Bottling filling = fill.moved(experienceOf(player), bottle);
        if (!filling.happened()) {
            tell(player, filling);
            return filling;
        }
        if (!between.isReady(player.getUniqueId())) {
            long seconds = between.remaining(player.getUniqueId()).orElse(Duration.ZERO).toSeconds();
            messages.send(player, "xpbottle.wait", "seconds", String.valueOf(Math.max(1, seconds)));
            effects.play(player.getUniqueId(), Cues.NO);
            return Bottling.nothingToTake(bottle);
        }

        // The bottle exists before anything is taken, and what is taken is what the bottle was
        // told it holds — never the amount that was asked for. A player whose experience changed
        // between the two (a mob died, an anvil was used) loses what went in and no more.
        ItemStack filled = forge.stackFor(filling.bottle());
        int taken = takeFrom(player, filling.moved());
        if (taken <= 0) {
            return Bottling.nothingToTake(bottle);
        }
        if (taken < filling.moved()) {
            filled = forge.stackFor(bottle.plus(taken));
        }
        takeOne(held);
        giveOrDrop(player, filled);
        between.start(player.getUniqueId());
        effects.play(player.getUniqueId(), Cues.MAGIC);
        messages.send(player, "xpbottle.filled", "points", String.valueOf(taken));
        return Bottling.of(taken, bottle.plus(taken));
    }

    /**
     * Pours a bottle back into whoever is holding it.
     *
     * <p>A plain bottle is spent doing it and leaves the glass behind; a siphon is emptied in place,
     * because it is an item somebody was given and one that vanished when it emptied would be one
     * they think they have lost.
     *
     * @return how many points went back in
     */
    public int pour(Player player, ItemStack held, Bottle bottle) {
        if (player == null || bottle == null || bottle.isEmpty()) {
            if (player != null) {
                messages.send(player, "xpbottle.already-empty");
                effects.play(player.getUniqueId(), Cues.NO);
            }
            return 0;
        }
        int points = bottle.stored();
        if (bottle.mayVacuum()) {
            forge.dress(held, bottle.poured());
        } else {
            takeOne(held);
            giveOrDrop(player, forge.emptyGlass());
        }
        player.giveExp(points);
        effects.play(player.getUniqueId(), Cues.REWARD);
        messages.send(player, "xpbottle.poured", "points", String.valueOf(points));
        return points;
    }

    /** What a player is told when nothing moved. */
    private void tell(Player player, Bottling filling) {
        Map<Bottling.Reason, String> lines = Map.of(
                Bottling.Reason.ALREADY_FULL, "xpbottle.bottle-full",
                Bottling.Reason.NOTHING_TO_TAKE, "xpbottle.no-experience");
        String key = lines.get(filling.reason());
        if (key != null) {
            messages.send(player, key);
            effects.play(player.getUniqueId(), Cues.NO);
        }
    }

    /** Takes one out of the stack that was clicked — not one found somewhere else in the bag. */
    private static void takeOne(ItemStack held) {
        if (held == null) {
            return;
        }
        held.setAmount(Math.max(0, held.getAmount() - 1));
    }

    /** Into the bag, or at the player's feet when there is no room. Nothing is ever destroyed. */
    private static void giveOrDrop(Player player, ItemStack stack) {
        if (player == null || stack == null) {
            return;
        }
        player.getInventory().addItem(stack).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
    }

    /** A player who has left is not waiting for anything. */
    public void forget(UUID player) {
        between.forget(player);
    }
}
