package de.raindancer.modules.xaeromap.command;

import de.raindancer.modules.xaeromap.XaeroMapServices;
import de.raindancer.modules.xaeromap.model.ClaimMapSnapshot;
import de.raindancer.modules.xaeromap.model.Waypoint;
import de.raindancer.modules.xaeromap.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /xaeromap} — what the server is telling map mods, and how to make it say it again.
 *
 * <h2>Why this command exists at all</h2>
 * Everything this module does is invisible from the server side: the packets either arrive and draw
 * something on a client nobody running the server can see, or they are dropped silently by a client
 * with no mod installed. There is no in-game symptom to tell those two apart, which makes "is it
 * working" unanswerable without this — {@code status} says how many connected players are actually
 * running a map mod that reads this protocol, which is the one fact that separates "broken" from
 * "nobody has the mod".
 *
 * <p>No menu, deliberately. Nothing here is a setting — the settings are Core's own {@code /settings}
 * pages, off the {@code @Settings} record — and a menu of buttons that each do one thing immediately is
 * a slower way to type one word.
 *
 * <p>{@code homes} and {@code warps} are the exception to "a subcommand has to earn its place": they
 * take no argument and a menu could show them, but what they produce is chat — a row of buttons in the
 * client's own chat window — so a menu would be a window you open in order to close it.
 */
public final class XaeroMapCommand implements IXaeroMapCommand {

    private final Supplier<XaeroMapServices> services;

    public XaeroMapCommand(Supplier<XaeroMapServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        XaeroMapServices live = services.get();
        CommandSender sender = source.getSender();
        String what = args.length == 0 ? "refresh" : args[0].toLowerCase(java.util.Locale.ROOT);

        switch (what) {
            case "refresh" -> refresh(live, sender);
            case "status" -> status(live, sender);
            case "resync" -> resyncEverybody(live, sender);
            case "homes" -> offer(live, sender, "homes");
            case "warps" -> offer(live, sender, "warps");
            default -> live.messages().send(sender, "xaeromap.usage");
        }
    }

    /** The player's own map, sent again from nothing. */
    private void refresh(XaeroMapServices live, CommandSender sender) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "xaeromap.only-a-player");
            return;
        }
        if (!player.hasPermission(PermissionNodes.REFRESH)) {
            live.messages().send(sender, "xaeromap.no-permission");
            return;
        }
        live.worldIds().send(player);
        if (!live.sync().isReady(player.getUniqueId())) {
            // Their client never answered the probe, so either they have no map mod or it does not
            // read this protocol. Offering again is the right move — a mod installed mid-session
            // registers its channel late — but they are told, because a silent no-op reads as a bug.
            live.sync().offer(player);
            live.messages().send(sender, "xaeromap.refresh.no-mod");
            return;
        }
        live.sync().begin(player);
        live.messages().send(sender, "xaeromap.refresh.done");
    }

    /**
     * Hands the player their places as waypoints to add.
     *
     * <p>Refused rather than half-done in three cases, each with its own wording: the feature is off,
     * the player has no map mod at all (the offer would arrive as raw text they cannot read), or they
     * have no places of that kind. A silent nothing would read as the command being broken.
     */
    private void offer(XaeroMapServices live, CommandSender sender, String which) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "xaeromap.only-a-player");
            return;
        }
        if (!live.waypoints().enabled()) {
            live.messages().send(sender, "xaeromap.waypoints.off");
            return;
        }
        if (!live.waypoints().canReceive(player)) {
            live.messages().send(sender, "xaeromap.waypoints.no-mod");
            return;
        }
        List<Waypoint> places = "homes".equals(which)
                ? live.waypoints().homesOf(player)
                : live.waypoints().warpsFor(player);
        if (places.isEmpty()) {
            live.messages().send(sender, "xaeromap.waypoints.none", "kind", which);
            return;
        }
        // The heading first, then the offers: each of those is a bare share line the client turns into
        // a button, so anything said after them would look like part of the last one.
        live.messages().send(sender, "xaeromap.waypoints.offering",
                "count", String.valueOf(places.size()), "kind", which);
        int sent = live.waypoints().offer(player, places);
        if (sent < places.size()) {
            live.messages().send(sender, "xaeromap.waypoints.partial",
                    "sent", String.valueOf(sent), "count", String.valueOf(places.size()));
        }
    }

    private void status(XaeroMapServices live, CommandSender sender) {
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "xaeromap.no-permission");
            return;
        }
        ClaimMapSnapshot picture = live.sync().current();
        live.messages().send(sender, "xaeromap.status.line",
                "worlds", live.config().worldIds() ? "on" : "off",
                "claims", live.config().claims() ? "on" : "off",
                "source", live.claims().get().name(),
                "shown", live.config().audience().name().toLowerCase(java.util.Locale.ROOT)
                        .replace('_', ' '),
                "mapped", String.valueOf(picture.claims().size()),
                "chunks", String.valueOf(picture.chunkCount()),
                "listening", String.valueOf(live.sync().readyCount()),
                "mapmods", String.valueOf(live.clients().count()),
                "online", String.valueOf(live.server().getOnlinePlayers().size()));
    }

    private void resyncEverybody(XaeroMapServices live, CommandSender sender) {
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "xaeromap.no-permission");
            return;
        }
        int sent = 0;
        for (Player player : live.server().getOnlinePlayers()) {
            live.worldIds().send(player);
            if (live.sync().isReady(player.getUniqueId())) {
                live.sync().begin(player);
                sent++;
            }
        }
        live.messages().send(sender, "xaeromap.resync.done", "players", String.valueOf(sent));
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
                                               String @NotNull [] args) {
        if (args.length != 1) {
            return List.of();
        }
        List<String> offered = new ArrayList<>(List.of("refresh", "homes", "warps"));
        if (source.getSender().hasPermission(PermissionNodes.ADMIN)) {
            offered.add("status");
            offered.add("resync");
        }
        String typed = args[0].toLowerCase(java.util.Locale.ROOT);
        offered.removeIf(option -> !option.startsWith(typed));
        return offered;
    }

    @Override
    public String describe() {
        return "what this server is telling Xaero's map mods, sending it again, and handing a player "
                + "their places as waypoints";
    }
}
