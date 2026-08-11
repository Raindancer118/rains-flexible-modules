package de.raindancer.modules.rtp.command;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.rtp.RtpServices;
import de.raindancer.modules.rtp.RtpSettings;
import de.raindancer.modules.rtp.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /rtp} — send the player somewhere random in their own world.
 *
 * <p>A bare {@code /rtp} takes the owner's own minimum distance from {@code RtpSettings#minRadius()}.
 * {@code /rtp <distance>} asks for at least that many blocks from the middle instead, for this trip
 * only — somebody who has already explored the nearest ring wants further out without an owner having
 * to widen it for everybody. Whether this trip's landing is checked for safety is the other thing that
 * varies per trip, when the owner's settings leave that up to the player — see {@code
 * RtpSettings#safeArrivalPolicy()}. Everything else is an owner's setting, reached through
 * {@code /settings}.
 *
 * <h2>The one exception: {@code prepare}</h2>
 * An owner filling the pool by hand — see {@code RtpLocationPoolService} — asks for it here rather than
 * through a second command, the same way {@code ModerationCommand} answers to {@code /mod config} and
 * {@code /mod reports} beside a bare {@code /mod}. Gated on its own permission rather than the plain
 * {@link PermissionNodes#USE}: everybody who can go somewhere random should not also be able to spend
 * the server's time searching thousands of chunks ahead of time.
 */
public final class RtpCommand implements IRtpCommand {

    private static final String PREPARE = "prepare";

    private final Supplier<RtpServices> services;

    /**
     * @param services asked for when the command runs, never captured — see {@link IRtpCommand} on
     *                 why a command built at bootstrap cannot hold anything the module built
     */
    public RtpCommand(Supplier<RtpServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        RtpServices live = services.get();
        CommandSender sender = source.getSender();

        if (args.length > 0 && PREPARE.equalsIgnoreCase(args[0])) {
            prepare(live, sender, args);
            return;
        }

        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "rtp.only-a-player");
            return;
        }

        Integer minDistance = null;
        if (args.length > 0) {
            try {
                minDistance = Integer.parseInt(args[0]);
            } catch (NumberFormatException notANumber) {
                live.messages().send(sender, "rtp.invalid-distance", "value", args[0]);
                return;
            }
        }

        if (live.rtp().playerMayChoose()) {
            live.screens().chooser(player, minDistance);
            return;
        }
        // Nothing to ask: the owner's policy has already decided one way or the other, so the safe
        // choice passed here is only ever a placeholder the rule ends up ignoring.
        live.rtp().go(player, true, minDistance);
    }

    /**
     * Searches for more locations right now, rather than waiting for the daily top-up.
     *
     * <p>Answers at once with how many were actually asked for — the pool might already be near its
     * ceiling — and again, later, with how many were actually found: not every point picked turns out
     * to be somewhere safe, and an owner watching the console wants to know both numbers.
     */
    private void prepare(RtpServices live, CommandSender sender, String[] args) {
        if (!sender.hasPermission(PermissionNodes.PREPARE)) {
            live.messages().send(sender, "rtp.pool.no-permission");
            return;
        }
        RtpSettings settings = live.config();
        if (!settings.poolEnabled()) {
            live.messages().send(sender, "rtp.pool.disabled");
            return;
        }
        World world = sender instanceof Player player ? player.getWorld()
                : live.server().getWorlds().stream().findFirst().orElse(null);
        if (world == null) {
            live.messages().send(sender, "rtp.pool.no-world");
            return;
        }

        int amount = settings.dailyMinimum();
        if (args.length > 1) {
            try {
                amount = Integer.parseInt(args[1]);
            } catch (NumberFormatException notANumber) {
                live.messages().send(sender, "rtp.pool.invalid-amount", "value", args[1]);
                return;
            }
        }
        if (amount <= 0) {
            live.messages().send(sender, "rtp.pool.invalid-amount", "value", String.valueOf(amount));
            return;
        }
        int room = Math.max(0, settings.maxPoolSize() - live.locations().size());
        int asked = Math.min(amount, room);
        if (asked <= 0) {
            live.messages().send(sender, "rtp.pool.full");
            return;
        }

        live.messages().send(sender, "rtp.pool.preparing", "amount", asked, "world", world.getName());
        if (live.log() != null) {
            live.log().info("{} asked for {} random-teleport location(s) in {} to be prepared.",
                    sender.getName(), asked, world.getName());
        }
        live.locations().prepare(world, asked).thenAccept(added -> Scheduling.global(live.plugin(), () -> {
            live.messages().send(sender, "rtp.pool.prepared", "amount", added, "world", world.getName());
            if (live.log() != null) {
                live.log().info("Prepared {} random-teleport location(s) in {}, asked for by {}.",
                        added, world.getName(), sender.getName());
            }
        }));
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
                                               String @NotNull [] args) {
        // args is empty rather than one blank string the instant after "/rtp ", before anything has
        // been typed for the first argument — the length check alone would leave that moment with no
        // suggestions at all, which is exactly when they are most wanted.
        if (args.length > 1 || !source.getSender().hasPermission(PermissionNodes.PREPARE)) {
            return List.of();
        }
        String typed = args.length == 1 ? args[0] : "";
        return PREPARE.regionMatches(true, 0, typed, 0, typed.length()) ? List.of(PREPARE) : List.of();
    }

    @Override
    public @NotNull String permission() {
        return PermissionNodes.USE;
    }

    @Override
    public String describe() {
        return "go somewhere random in your own world";
    }
}
