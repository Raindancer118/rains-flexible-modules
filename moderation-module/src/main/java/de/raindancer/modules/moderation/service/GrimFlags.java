package de.raindancer.modules.moderation.service;

import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.modules.moderation.model.Flag;
import de.raindancer.modules.moderation.model.GrimFamily;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Grim's flags, taken into this module: each one, on the player's own thread, becomes a flag in the family its check
 * belongs to — and from there a level that follows the player across the network, evidence, alerts to staff on
 * every server, a staff report and, at the family's levels, a kick. Grim keeps its setbacks; this only listens.
 */
public final class GrimFlags implements IModerationService {

    private static final int LONGEST_DETAIL = 500;

    /** Hands a task to the player's own thread. */
    @FunctionalInterface
    public interface OnThread {
        void run(Player player, Runnable task);
    }

    private final Function<UUID, Optional<Player>> online;
    private final OnThread onThread;
    private final BiConsumer<Player, Flag> pipeline;
    private volatile boolean experimentalCount;

    /** @param pipeline weighs one flag for one player (on their thread) */
    public GrimFlags(Function<UUID, Optional<Player>> online, OnThread onThread, BiConsumer<Player, Flag> pipeline) {
        this.online = online;
        this.onThread = onThread;
        this.pipeline = pipeline;
    }

    /** Whether Grim's experimental checks count, as the owner's settings say now. */
    @Override
    public void settings(ModerationSettings fresh) {
        this.experimentalCount = fresh.antiCheat().experimentalChecks();
    }

    @Override
    public String describe() {
        return "taking Grim's flags in";
    }

    /** One flag from Grim, from any thread. */
    public void accept(UUID player, String checkName, String verbose, double grimLevel, boolean experimental) {
        if (player == null || experimental && !experimentalCount) {
            return;
        }
        Optional<Player> here = online.apply(player);
        if (here.isEmpty()) {
            return;
        }
        String check = checkName == null || checkName.isBlank() ? "Grim" : checkName;
        String detail = check + (verbose == null || verbose.isBlank() ? "" : ": " + verbose)
                + (Double.isFinite(grimLevel) ? String.format(Locale.ROOT, " (Grim %.0f)", grimLevel) : "");
        if (detail.length() > LONGEST_DETAIL) {
            detail = detail.substring(0, LONGEST_DETAIL) + "…";
        }
        Flag flag = Flag.of(GrimFamily.of(check), detail);
        onThread.run(here.get(), () -> pipeline.accept(here.get(), flag));
    }
}
