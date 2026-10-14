package de.raindancer.modules.sends.command;

import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.modules.sends.SendsServices;
import de.raindancer.modules.sends.model.Send;
import de.raindancer.modules.sends.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /send create [player]}, {@code accept}, {@code decline} and {@code cancel}.
 *
 * <p>These exist as commands because a chat line can carry them as a click, and because typing one is faster
 * than finding the window; {@code /sends} is the same thing as a list. Without an id each one acts on the only
 * send it could mean, and says so when there are several.
 */
public final class SendCommand implements ISendsCommand {

    private static final List<String> SUBCOMMANDS = List.of("create", "accept", "decline", "cancel");

    private final Supplier<SendsServices> services;

    public SendCommand(Supplier<SendsServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        SendsServices live = services.get();
        if (!(source.getSender() instanceof Player player)) {
            live.messages().send(source.getSender(), "sends.only-a-player");
            return;
        }
        if (!player.hasPermission(PermissionNodes.USE)) {
            live.messages().send(player, "sends.not-allowed");
            return;
        }
        if (args.length == 0) {
            live.messages().send(player, "sends.usage");
            return;
        }
        String id = args.length > 1 ? args[1] : null;
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(live, player, id);
            case "accept" -> incoming(live, player, id).ifPresent(send -> live.sends().accept(player, send.id()));
            case "decline" -> incoming(live, player, id).ifPresent(send -> live.sends().release(player, send.id()));
            case "cancel" -> outgoing(live, player, id).ifPresent(send -> live.sends().release(player, send.id()));
            default -> live.messages().send(player, "sends.usage");
        }
    }

    private void create(SendsServices live, Player player, String name) {
        if (name == null) {
            live.screens().choose(player);
            return;
        }
        Optional<Player> found = PlayerTargets.online(live.server(), player, name);
        if (found.isEmpty()) {
            // Not here: maybe on another server of the network.
            var elsewhere = live.core().networkPlayers().flatMap(players -> players.named(name, false));
            if (elsewhere.isPresent()) {
                if (elsewhere.get().player().equals(player.getUniqueId())) {
                    live.messages().send(player, "sends.refuse.self");
                    return;
                }
                live.screens().compose(player, elsewhere.get().player(), elsewhere.get().name());
                return;
            }
            live.messages().send(player, "sends.refuse.unknown", "player", name);
            return;
        }
        if (found.get().equals(player)) {
            live.messages().send(player, "sends.refuse.self");
            return;
        }
        live.screens().compose(player, found.get().getUniqueId(), found.get().getName());
    }

    /** The send this player means by "accept" or "decline": the one they named, or the only one there is. */
    private Optional<Send> incoming(SendsServices live, Player player, String typed) {
        List<Send> waiting = live.book().incoming(player.getUniqueId());
        return pick(live, player, typed, waiting, "sends.none-waiting");
    }

    private Optional<Send> outgoing(SendsServices live, Player player, String typed) {
        List<Send> waiting = live.book().outgoing(player.getUniqueId());
        return pick(live, player, typed, waiting, "sends.none-out");
    }

    private Optional<Send> pick(SendsServices live, Player player, String typed, List<Send> waiting,
                                String noneKey) {
        if (typed != null) {
            Optional<Send> named = live.book().find(typed, player.getUniqueId())
                    .filter(waiting::contains);
            if (named.isEmpty()) {
                live.messages().send(player, "sends.gone");
            }
            return named;
        }
        if (waiting.isEmpty()) {
            live.messages().send(player, noneKey);
            return Optional.empty();
        }
        if (waiting.size() > 1) {
            live.messages().send(player, "sends.several", "count", String.valueOf(waiting.size()));
            live.screens().sends(player);
            return Optional.empty();
        }
        return Optional.of(waiting.getFirst());
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        if (!(source.getSender() instanceof Player player) || !player.hasPermission(PermissionNodes.USE)) {
            return List.of();
        }
        SendsServices live = services.get();
        if (args.length <= 1) {
            String started = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(name -> name.startsWith(started)).toList();
        }
        String started = args[1].toLowerCase(Locale.ROOT);
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                List<String> names = new ArrayList<>(PlayerTargets.suggest(live.server(), source.getSender(),
                        args[1], other -> !other.equals(player)));
                live.core().networkPlayers().ifPresent(players -> players.everyone(false).stream()
                        .filter(there -> !there.server().equals(players.self()))
                        .map(there -> there.name())
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(started))
                        .filter(name -> !names.contains(name)).forEach(names::add));
                return names;
            }
            case "accept", "decline" -> {
                return shortIds(live.book().incoming(player.getUniqueId()), started);
            }
            case "cancel" -> {
                return shortIds(live.book().outgoing(player.getUniqueId()), started);
            }
            default -> {
                return List.of();
            }
        }
    }

    private static List<String> shortIds(List<Send> sends, String started) {
        List<String> ids = new ArrayList<>();
        for (Send send : sends) {
            if (send.shortId().startsWith(started)) {
                ids.add(send.shortId());
            }
        }
        return ids;
    }

    @Override
    public String describe() {
        return "making, accepting, declining and cancelling sends";
    }
}
