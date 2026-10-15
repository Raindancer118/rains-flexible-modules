package de.raindancer.modules.oneblock.command;

import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.platform.network.Presence;
import de.raindancer.core.platform.proxy.ProxyTransfer;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.oneblock.OneBlockServices;
import de.raindancer.modules.oneblock.model.InviteKind;
import de.raindancer.modules.oneblock.model.Island;
import de.raindancer.modules.oneblock.model.IslandDimension;
import de.raindancer.modules.oneblock.model.Reply;
import de.raindancer.modules.oneblock.model.Requester;
import de.raindancer.modules.oneblock.model.Spawn;
import de.raindancer.modules.oneblock.screen.ChooseModeMenu;
import de.raindancer.modules.oneblock.screen.ConfirmScreen;
import de.raindancer.modules.oneblock.screen.IslandMenu;
import de.raindancer.modules.oneblock.screen.TeamMenu;
import de.raindancer.modules.oneblock.screen.UpgradesMenu;
import de.raindancer.modules.oneblock.service.IslandServer;
import de.raindancer.modules.oneblock.service.Replies;
import de.raindancer.modules.oneblock.service.SidebarService;
import de.raindancer.modules.oneblock.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * {@code /island} from any server of the network. Invites and their answers work from anywhere; everything else is
 * the island server's — typed elsewhere, it is kept for the player's arrival and they are sent over.
 */
public final class IslandCommand implements IOneBlockCommand {

    /** What answers on every server, without going anywhere. */
    private static final List<String> ANYWHERE = List.of("invite", "guest", "accept", "deny", "sidebar");
    /** What only the island server does. */
    private static final List<String> ON_ISLANDS = List.of("menu", "create", "home", "nether", "end", "unlock",
            "upgrades", "team", "kick", "leave", "setspawn", "info", "economy");
    private static final List<String> SUBCOMMANDS;

    static {
        List<String> all = new ArrayList<>(ON_ISLANDS);
        all.addAll(ANYWHERE);
        SUBCOMMANDS = List.copyOf(all);
    }

    private final Supplier<OneBlockServices> services;

    public IslandCommand(Supplier<OneBlockServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        OneBlockServices live = services.get();
        if (!(source.getSender() instanceof Player player)) {
            live.messages().send(source.getSender(), "oneblock.only-a-player");
            return;
        }
        if (!player.hasPermission(PermissionNodes.USE)) {
            live.messages().send(player, "oneblock.not-allowed");
            return;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("sidebar")) {
            toggleSidebar(live, player);
            return;
        }
        Requester me = new Requester(player.getUniqueId(), player.getName());
        String other = args.length > 1 ? args[1] : null;
        if (ANYWHERE.contains(sub)) {
            CompletableFuture<Reply> asked = switch (sub) {
                case "invite" -> live.gateway().invite(me, other, InviteKind.TEAM);
                case "guest" -> live.gateway().invite(me, other, InviteKind.GUEST);
                case "accept" -> live.gateway().answer(me, other, true);
                default -> live.gateway().answer(me, other, false);
            };
            asked.thenAccept(reply -> Scheduling.entity(live.plugin(), player, () -> said(live, player, reply)));
            return;
        }
        if (!sub.isEmpty() && !ON_ISLANDS.contains(sub)) {
            live.messages().send(player, "oneblock.usage");
            return;
        }
        Optional<IslandServer> islands = live.islandServer();
        if (islands.isEmpty()) {
            if (sub.isEmpty()) {
                live.gateway().home(me).thenAccept(reply ->
                        Scheduling.entity(live.plugin(), player, () -> said(live, player, reply)));
                return;
            }
            live.gateway().then(me, String.join(" ", args)).thenAccept(reply -> reply.ifPresent(going ->
                    Scheduling.entity(live.plugin(), player, () -> said(live, player, going))));
            return;
        }
        onTheIslandServer(live, islands.get(), player, sub, args);
    }

    private static void onTheIslandServer(OneBlockServices live, IslandServer islands, Player player, String sub,
                                          String[] args) {
        UUID id = player.getUniqueId();
        Optional<Island> own = islands.book().ofPlayer(id);
        switch (sub) {
            case "" -> islands.host().home(id);
            case "menu" -> new IslandMenu(live, islands, player, null).open();
            case "create" -> {
                if (own.isPresent()) {
                    live.messages().send(player, "oneblock.create.already");
                } else {
                    new ChooseModeMenu(live, islands, player, null, true).open();
                }
            }
            case "economy" -> {
                if (own.isEmpty()) {
                    live.messages().send(player, "oneblock.refuse.no-island");
                } else if (own.get().mode().decided()) {
                    live.messages().send(player, "oneblock.mode.already");
                } else if (!own.get().owner().equals(id)) {
                    live.messages().send(player, "oneblock.mode.owner-only");
                } else {
                    new ChooseModeMenu(live, islands, player, null, false).open();
                }
            }
            case "home" -> go(live, islands, player, own, IslandDimension.OVERWORLD);
            case "nether" -> go(live, islands, player, own, IslandDimension.NETHER);
            case "end" -> go(live, islands, player, own, IslandDimension.END);
            case "unlock" -> {
                Optional<IslandDimension> dimension = args.length > 1 ? IslandDimension.byId(args[1]) : Optional.empty();
                if (dimension.isEmpty() || dimension.get() == IslandDimension.OVERWORLD) {
                    live.messages().send(player, "oneblock.unlock.usage");
                } else {
                    Replies.say(live.messages(), player, islands.islands().unlock(id, dimension.get()));
                }
            }
            case "upgrades" -> {
                IslandDimension dimension = args.length > 1 ? IslandDimension.byId(args[1]).orElse(IslandDimension.OVERWORLD)
                        : Island.dimensionOf(player.getWorld().getName()).orElse(IslandDimension.OVERWORLD);
                if (own.isEmpty()) {
                    live.messages().send(player, "oneblock.refuse.no-island");
                } else {
                    new UpgradesMenu(live, islands, player, null,
                            own.get().unlocked(dimension) ? dimension : IslandDimension.OVERWORLD).open();
                }
            }
            case "team" -> new TeamMenu(live, islands, player, null).open();
            case "kick" -> kick(live, islands, player, args.length > 1 ? args[1] : null);
            case "leave" -> leave(live, islands, player, own);
            case "setspawn" -> {
                Location at = player.getLocation();
                Replies.say(live.messages(), player, islands.islands().setSpawn(id, at.getWorld().getName(),
                        new Spawn(at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch())));
            }
            case "info" -> info(live, islands, player, args.length > 1 ? args[1] : null);
            default -> live.messages().send(player, "oneblock.usage");
        }
    }

    private static void go(OneBlockServices live, IslandServer islands, Player player, Optional<Island> own,
                           IslandDimension dimension) {
        if (own.isEmpty()) {
            islands.host().home(player.getUniqueId());
            return;
        }
        if (!own.get().unlocked(dimension)) {
            live.messages().send(player, "oneblock.go.locked-buy", "dimension", live.messages().get(dimension.nameKey()),
                    "price", String.format("%,d", islands.currentContent().unlockPrice(dimension).orElse(0L)),
                    "id", dimension.id());
            return;
        }
        islands.travel().go(player, own.get(), dimension);
    }

    private static void kick(OneBlockServices live, IslandServer islands, Player player, String name) {
        if (name == null || name.isBlank()) {
            live.messages().send(player, "oneblock.kick.usage");
            return;
        }
        OfflinePlayer target = live.server().getOfflinePlayerIfCached(name);
        if (target == null) {
            live.messages().send(player, "oneblock.kick.not-member", "player", name);
            return;
        }
        Reply reply = islands.islands().kick(player.getUniqueId(), target.getUniqueId(),
                target.getName() == null ? name : target.getName());
        Replies.say(live.messages(), player, reply);
        Player online = target.getPlayer();
        if (reply.ok() && online != null && Island.isIslandWorld(online.getWorld().getName())
                && islands.book().ofWorld(online.getWorld().getName())
                .filter(island -> !island.isMember(online.getUniqueId())).isPresent()) {
            islands.travel().toWaiting(online);
            live.messages().send(online, "oneblock.kick.you", "player", player.getName());
        }
    }

    private static void leave(OneBlockServices live, IslandServer islands, Player player, Optional<Island> own) {
        if (own.isEmpty()) {
            live.messages().send(player, "oneblock.refuse.no-island");
            return;
        }
        boolean owner = own.get().owner().equals(player.getUniqueId());
        if (owner && !own.get().members().isEmpty()) {
            live.messages().send(player, "oneblock.leave.has-team");
            return;
        }
        new ConfirmScreen(islands.brand(), player, null, owner ? "Give up your island?" : "Leave the island?",
                List.of(owner ? "<red>Nobody plays it any more. It is kept, not deleted."
                        : "<gray>You leave " + own.get().ownerName() + "'s team."),
                () -> {
                    Replies.say(live.messages(), player, islands.islands().leave(player.getUniqueId()));
                    player.closeInventory();
                    if (islands.book().ofPlayer(player.getUniqueId()).isEmpty()
                            && Island.isIslandWorld(player.getWorld().getName())) {
                        islands.travel().toWaiting(player);
                    }
                }).open();
    }

    private static void info(OneBlockServices live, IslandServer islands, Player player, String name) {
        Optional<Island> island;
        if (name == null) {
            island = islands.book().ofWorld(player.getWorld().getName()).or(() -> islands.book().ofPlayer(player.getUniqueId()));
        } else {
            OfflinePlayer target = live.server().getOfflinePlayerIfCached(name);
            island = target == null ? Optional.empty() : islands.book().ofPlayer(target.getUniqueId());
        }
        if (island.isEmpty()) {
            live.messages().send(player, "oneblock.info.none", "player", name == null ? player.getName() : name);
            return;
        }
        Island shown = island.get();
        List<String> team = new ArrayList<>();
        for (UUID member : shown.everyone()) {
            String memberName = live.server().getOfflinePlayer(member).getName();
            team.add(memberName == null ? member.toString().substring(0, 8) : memberName);
        }
        List<String> dimensions = shown.unlockedDimensions().stream().map(dimension -> dimension.id()).toList();
        live.messages().send(player, "oneblock.info.island", "player", shown.ownerName(),
                "mode", live.messages().get(shown.mode().badgeKey()), "team", String.join(", ", team),
                "shards", String.format("%,d", shown.shards()), "blocks", String.format("%,d", shown.totalBroken()),
                "dimensions", String.join(", ", dimensions));
    }

    private static void toggleSidebar(OneBlockServices live, Player player) {
        if (live.sidebar().isEmpty()) {
            live.messages().send(player, "oneblock.sidebar.elsewhere");
            return;
        }
        boolean on = SidebarService.SHOWN.toggle(player);
        if (!on) {
            live.sidebar().get().clear(player.getUniqueId());
        }
        live.messages().send(player, on ? "oneblock.sidebar.shown" : "oneblock.sidebar.hidden");
    }

    private static void said(OneBlockServices live, Player player, Reply reply) {
        Replies.say(live.messages(), player, reply);
        if (reply.transfer()) {
            String island = live.config().proxyServer();
            live.core().networkTravel().ifPresentOrElse(
                    travel -> travel.send(player, island, Optional.empty()),
                    () -> ProxyTransfer.send(live.plugin(), player, island));
        }
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        if (!(source.getSender() instanceof Player player) || !player.hasPermission(PermissionNodes.USE)) {
            return List.of();
        }
        if (args.length <= 1) {
            String started = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(name -> name.startsWith(started)).toList();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String typed = args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("unlock")) {
            return Arrays.asList("nether", "end").stream().filter(name -> name.startsWith(typed)).toList();
        }
        if (sub.equals("upgrades")) {
            return Arrays.stream(IslandDimension.values()).map(IslandDimension::id)
                    .filter(name -> name.startsWith(typed)).toList();
        }
        OneBlockServices live = services.get();
        List<String> names = new ArrayList<>(PlayerTargets.suggest(live.server(), source.getSender(), args[1],
                online -> !online.equals(player)));
        elsewhere(live).stream().filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed))
                .filter(name -> !names.contains(name)).sorted().forEach(names::add);
        return names;
    }

    /** Everybody online on another server — the network's presence, or the link's roster. */
    private static Collection<String> elsewhere(OneBlockServices live) {
        return live.core().networkPlayers().<Collection<String>>map(players -> players.everyone(false).stream()
                        .filter(there -> !there.server().equals(players.self())).map(Presence.Here::name).toList())
                .orElseGet(() -> live.core().playersElsewhere());
    }

    @Override
    public String describe() {
        return "your island, its dimensions, upgrades and team, and invites from anywhere on the network";
    }
}
