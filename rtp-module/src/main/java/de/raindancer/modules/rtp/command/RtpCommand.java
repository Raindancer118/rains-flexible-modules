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
 * <p>Takes no arguments for a player asking to go somewhere, with one exception: whether this trip's
 * landing is checked for safety, when the owner's settings leave that up to the player — see {@code
 * RtpSettings#safeArrivalPolicy()}. Under any other policy there is nothing to ask, so the trip goes
 * straight ahead. Everything else that varies is an owner's setting, reached through {@code /settings}.
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
        if (live.rtp().playerMayChoose()) {
            live.screens().chooser(player);
            return;
        }
        // Nothing to ask: the owner's policy has already decided one way or the other, so the safe
        // choice passed here is only ever a placeholder the rule ends up ignoring.
        live.rtp().go(player, true);
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
        live.locations().prepare(world, asked).thenAccept(added -> Scheduling.global(live.plugin(), () ->
                live.messages().send(sender, "rtp.pool.prepared", "amount", added, "world",
                        world.getName())));
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
                                               String @NotNull [] args) {
        if (args.length == 1 && source.getSender().hasPermission(PermissionNodes.PREPARE)
                && PREPARE.regionMatches(true, 0, args[0], 0, args[0].length())) {
            return List.of(PREPARE);
        }
        return List.of();
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
