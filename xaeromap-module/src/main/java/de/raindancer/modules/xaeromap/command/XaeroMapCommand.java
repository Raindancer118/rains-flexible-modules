package de.raindancer.modules.xaeromap.command;

import de.raindancer.modules.xaeromap.XaeroMapServices;
import de.raindancer.modules.xaeromap.model.ClaimMapSnapshot;
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
 * <p>Three subcommands and no menu, deliberately. Nothing here is a setting — the settings are Core's
 * own {@code /settings} pages, off the {@code @Settings} record — and a menu of three buttons that each
 * do one thing immediately is a slower way to type three words.
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
        List<String> offered = new ArrayList<>(List.of("refresh"));
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
        return "what this server is telling Xaero's map mods, and sending it again";
    }
}
