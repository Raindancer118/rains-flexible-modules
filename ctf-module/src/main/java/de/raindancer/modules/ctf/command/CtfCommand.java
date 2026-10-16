package de.raindancer.modules.ctf.command;

import de.raindancer.modules.ctf.CtfServices;
import de.raindancer.modules.ctf.model.Base;
import de.raindancer.modules.ctf.model.Role;
import de.raindancer.modules.ctf.service.PartyService;
import de.raindancer.modules.ctf.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /ctf}: the menu, and everything in it as words — join, leave, vote, class, watch, stats, top, party — and
 * staff's {@code admin} branch for maps, the lobby and rounds.
 */
public final class CtfCommand implements ICtfCommand {

    private static final List<String> PLAYER = List.of("join", "leave", "vote", "class", "side", "watch", "follow",
            "stats", "top", "looks", "challenges", "party");
    private static final List<String> ADMIN = List.of("start", "stop", "info", "map", "lobby", "board");

    private final Supplier<CtfServices> services;

    public CtfCommand(Supplier<CtfServices> services) {
        this.services = services;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CtfServices live = services.get();
        CommandSender sender = source.getSender();
        String first = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (first.equals("admin")) {
            admin(live, sender, Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "ctf.only-a-player");
            return;
        }
        if (!player.hasPermission(PermissionNodes.PLAY)) {
            live.messages().send(player, "ctf.no-permission");
            return;
        }
        switch (first) {
            case "" -> live.screens().main(player);
            case "join" -> live.rounds().join(player);
            case "leave" -> {
                if (live.lobby().isInside(player.getUniqueId())) {
                    live.rounds().leave(player);
                } else {
                    live.messages().send(player, "ctf.join.not-in");
                }
            }
            case "vote" -> live.screens().vote(player);
            case "class" -> {
                if (args.length > 1) {
                    Optional<Role> role = Role.parse(args[1]);
                    if (role.isEmpty()) {
                        live.messages().send(player, "ctf.class.unknown", "class", args[1]);
                        return;
                    }
                    live.rounds().pick(player, role.get());
                    live.messages().send(player, "ctf.class.picked", "class", role.get().title());
                } else {
                    live.screens().classes(player);
                }
            }
            case "side" -> live.screens().sides(player);
            case "watch" -> live.rounds().watch(player);
            case "follow" -> live.messages().send(player, live.spectators().toggleFollow(player.getUniqueId())
                    ? "ctf.watch.following" : "ctf.watch.not-following");
            case "stats" -> {
                if (args.length > 1) {
                    var other = Bukkit.getOfflinePlayerIfCached(args[1]);
                    if (other == null) {
                        live.messages().send(player, "ctf.no-such-player", "player", args[1]);
                        return;
                    }
                    live.screens().stats(player, other.getUniqueId());
                } else {
                    live.screens().stats(player, player.getUniqueId());
                }
            }
            case "top" -> live.screens().top(player);
            case "looks" -> live.screens().looks(player);
            case "challenges" -> live.screens().challenges(player);
            case "party" -> party(live, player, Arrays.copyOfRange(args, 1, args.length));
            default -> live.messages().send(player, "ctf.usage");
        }
    }

    private void party(CtfServices live, Player player, String[] args) {
        String what = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (what) {
            case "invite" -> {
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
                if (target == null) {
                    live.messages().send(player, "ctf.no-such-player", "player", args.length > 1 ? args[1] : "");
                    return;
                }
                PartyService.Outcome outcome = live.parties().invite(player.getUniqueId(), target.getUniqueId());
                live.messages().send(player, outcome.key(), "player", target.getName());
                if (outcome == PartyService.Outcome.INVITED) {
                    live.messages().send(target, "ctf.party.invite-received", "player", player.getName());
                }
            }
            case "accept" -> {
                Optional<java.util.UUID> by = live.parties().invitedBy(player.getUniqueId());
                PartyService.Outcome outcome = live.parties().accept(player.getUniqueId());
                live.messages().send(player, outcome.key(), "player", by.map(Bukkit::getOfflinePlayer)
                        .map(org.bukkit.OfflinePlayer::getName).orElse("?"));
                if (outcome == PartyService.Outcome.JOINED) {
                    by.map(Bukkit::getPlayer).ifPresent(leader -> live.messages().send(leader,
                            "ctf.party.someone-joined", "player", player.getName()));
                }
            }
            case "leave" -> live.messages().send(player, live.parties().leave(player.getUniqueId()).isPresent()
                    ? "ctf.party.left" : "ctf.party.not-in-one");
            case "kick" -> {
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
                boolean kicked = target != null && live.parties().kick(player.getUniqueId(), target.getUniqueId());
                live.messages().send(player, kicked ? "ctf.party.kicked" : "ctf.party.cannot-kick", "player",
                        args.length > 1 ? args[1] : "");
            }
            case "list", "" -> live.parties().of(player.getUniqueId()).ifPresentOrElse(party -> {
                List<String> names = new ArrayList<>();
                for (java.util.UUID member : party.members()) {
                    String name = Bukkit.getOfflinePlayer(member).getName();
                    names.add((member.equals(party.leader()) ? "★" : "") + (name == null ? "?" : name));
                }
                live.messages().send(player, "ctf.party.members", "members", String.join(", ", names));
            }, () -> live.messages().send(player, "ctf.party.how"));
            default -> live.messages().send(player, "ctf.party.how");
        }
    }

    private void admin(CtfServices live, CommandSender sender, String[] args) {
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "ctf.no-permission");
            return;
        }
        String what = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (what) {
            case "" -> {
                if (sender instanceof Player player) {
                    live.screens().admin(player);
                } else {
                    live.messages().send(sender, "ctf.admin.usage");
                }
            }
            case "start" -> live.messages().send(sender, live.rounds().startNow() ? "ctf.admin.starting"
                    : "ctf.admin.already-running");
            case "stop" -> live.messages().send(sender, live.rounds().stopNow() ? "ctf.admin.stopped"
                    : "ctf.admin.nothing-running");
            case "map" -> map(live, sender, Arrays.copyOfRange(args, 1, args.length));
            case "info" -> info(live, sender);
            case "lobby" -> {
                if (sender instanceof Player player) {
                    live.screens().admin(player);
                }
            }
            case "board" -> {
                if (sender instanceof Player player) {
                    boolean remove = args.length > 1 && args[1].equalsIgnoreCase("remove");
                    if (remove) {
                        live.messages().send(player, live.boards().removeNearest(player) ? "ctf.admin.board-removed"
                                : "ctf.admin.no-board-near");
                    } else {
                        live.boards().place(player.getLocation());
                        live.messages().send(player, "ctf.admin.board-placed");
                    }
                }
            }
            default -> live.messages().send(sender, "ctf.admin.usage");
        }
    }

    /** Where the round stands, for staff — and for a test reading the console. */
    private void info(CtfServices live, CommandSender sender) {
        live.rounds().round().ifPresentOrElse(round -> {
            synchronized (round) {
                live.messages().send(sender, "ctf.admin.info-round", "map", round.map().id(), "phase",
                        round.phase().name(), "type", round.type().name(), "twist", round.mutator().name(),
                        "score", java.util.Arrays.toString(round.scores()));
                for (de.raindancer.modules.ctf.model.Flag flag : round.flags()) {
                    de.raindancer.modules.ctf.model.Point at = flag.where();
                    live.messages().send(sender, "ctf.admin.info-flag", "side", flag.side(), "state",
                            live.rounds().isFlying(flag.side()) ? "FLYING" : flag.state().name(), "where", at.blockX() + " " + at.blockY() + " " + at.blockZ(),
                            "carrier", flag.carrier().map(id -> java.util.Optional.ofNullable(Bukkit.getPlayer(id))
                                    .map(Player::getName).orElse(id.toString())).orElse("-"));
                }
                for (de.raindancer.modules.ctf.model.Fighter fighter : round.fighters()) {
                    Player player = Bukkit.getPlayer(fighter.id());
                    String where = player == null ? "-" : player.getLocation().getBlockX() + " "
                            + player.getLocation().getBlockY() + " " + player.getLocation().getBlockZ();
                    live.messages().send(sender, "ctf.admin.info-fighter", "player", fighter.name(), "side",
                            fighter.side(), "alive", fighter.isAlive() ? "alive" : "down", "class",
                            fighter.role().name(), "where", where);
                }
            }
        }, () -> live.messages().send(sender, "ctf.admin.info-none", "phase", live.rounds().phase().name(),
                "maps", live.maps().all().size()));
    }

    private void map(CtfServices live, CommandSender sender, String[] args) {
        String what = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (what) {
            case "list" -> {
                live.maps().all().forEach(map -> live.messages().send(sender,
                        map.isPlayable() ? "ctf.admin.map-line" : "ctf.admin.map-line-missing", "map", map.id(),
                        "title", map.title(), "bases", map.completeBases().size(), "missing",
                        String.join(", ", map.missingFor(de.raindancer.modules.ctf.model.GameType.CLASSIC))));
                live.maps().schematics().stream().filter(file -> live.maps().all().stream()
                        .noneMatch(map -> map.file().equals(file))).forEach(file ->
                        live.messages().send(sender, "ctf.admin.schematic-line", "file", file));
            }
            case "setup" -> {
                if (!(sender instanceof Player player) || args.length < 2) {
                    live.messages().send(sender, "ctf.admin.usage");
                    return;
                }
                live.setup().begin(player, args[1], said -> live.messages().send(player, (String) said[0],
                        Arrays.copyOfRange(said, 1, said.length)));
            }
            case "title" -> {
                if (!(sender instanceof Player player) || args.length < 2) {
                    live.messages().send(sender, "ctf.admin.usage");
                    return;
                }
                String rest = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                String[] parts = rest.split("\\s+by\\s+", 2);
                boolean done = live.setup().change(player, map -> map.withTitle(parts[0].trim(),
                        parts.length > 1 ? parts[1].trim() : map.author()));
                live.messages().send(player, done ? "ctf.admin.marked" : "ctf.admin.not-in-map");
            }
            case "menu" -> {
                if (sender instanceof Player player) {
                    live.screens().setup(player);
                }
            }
            case "rebuild" -> {
                if (args.length < 2 || live.maps().load(args[1]).isEmpty()) {
                    live.messages().send(sender, "ctf.admin.no-such-map", "map", args.length > 1 ? args[1] : "");
                    return;
                }
                live.arena().forget(args[1]);
                live.messages().send(sender, "ctf.admin.will-rebuild", "map", args[1]);
            }
            case "bases" -> {
                if (args.length < 2) {
                    live.messages().send(sender, "ctf.admin.usage");
                    return;
                }
                live.maps().load(args[1]).ifPresentOrElse(map -> {
                    for (Base base : map.bases()) {
                        live.messages().send(sender, "ctf.admin.base-line", "colour", base.colour().name()
                                .toLowerCase(Locale.ROOT), "flag", base.flag() == null ? "-" : base.flag().line(),
                                "spawns", base.spawns().size());
                    }
                }, () -> live.messages().send(sender, "ctf.admin.no-such-map", "map", args[1]));
            }
            default -> live.messages().send(sender, "ctf.admin.usage");
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        boolean admin = sender.hasPermission(PermissionNodes.ADMIN);
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length <= 1) {
            List<String> all = new ArrayList<>(PLAYER);
            if (admin) {
                all.add("admin");
            }
            return all.stream().filter(option -> option.startsWith(typed)).toList();
        }
        String first = args[0].toLowerCase(Locale.ROOT);
        if (first.equals("class") && args.length == 2) {
            return Arrays.stream(Role.values()).map(role -> role.name().toLowerCase(Locale.ROOT))
                    .filter(option -> option.startsWith(typed)).toList();
        }
        if (first.equals("party") && args.length == 2) {
            return List.of("invite", "accept", "leave", "kick", "list").stream().filter(option -> option.startsWith(typed))
                    .toList();
        }
        if (first.equals("party") && args.length == 3 || first.equals("stats") && args.length == 2) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
        }
        if (first.equals("admin") && admin) {
            if (args.length == 2) {
                return ADMIN.stream().filter(option -> option.startsWith(typed)).toList();
            }
            if (args[1].equalsIgnoreCase("map") && args.length == 3) {
                return List.of("list", "setup", "menu", "title", "rebuild", "bases").stream()
                        .filter(option -> option.startsWith(typed)).toList();
            }
            if (args[1].equalsIgnoreCase("map") && args.length == 4) {
                CtfServices live = services.get();
                List<String> names = new ArrayList<>(live.maps().schematics());
                live.maps().all().forEach(map -> names.add(map.id()));
                return names.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
            }
            if (args[1].equalsIgnoreCase("board") && args.length == 3) {
                return List.of("place", "remove").stream().filter(option -> option.startsWith(typed)).toList();
            }
        }
        return List.of();
    }
}
