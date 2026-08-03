package de.raindancer.modules.claims.command;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.model.ClaimBan;
import de.raindancer.core.world.protection.LandAction;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.model.ClaimBan;
import de.raindancer.modules.claims.model.ClaimNames;
import de.raindancer.modules.claims.ClaimServices;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code /claim} — what a player does with their own land.
 *
 * <h2>Deliberately not the same command as before</h2>
 * The old one had twenty-two subcommands, several of which existed only because the menus could not do the thing
 * yet. This one has nine, and the rule for what earns a place is: <b>a subcommand exists when typing it is faster
 * than clicking, or when it takes an argument a menu cannot ask for.</b>
 *
 * <p>So {@code trust <player>} stays — naming somebody is what a command is for — and the eleven flag toggles do
 * not, because a flag is a click. Bare {@code /claim} opens the claim you are standing in, which is what people
 * were typing {@code /claim menu} for.
 *
 * <h2>Built at bootstrap</h2>
 * Paper registers commands before anything is enabled, so this holds a supplier rather than the services. See
 * {@code ModuleCommands.guarded}, which is what answers politely if the module never started.
 */
public final class ClaimCommand implements BasicCommand {

    private static final String USE = "rec.use";

    private final Supplier<ClaimServices> services;

    public ClaimCommand(Supplier<ClaimServices> services) {
        this.services = services;
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return sender.hasPermission(USE);
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage("This is a command for somebody standing in the world.");
            return;
        }
        ClaimServices claims = services.get();
        if (args.length == 0) {
            here(claims, player);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "new", "create" -> begin(claims, player);
            case "list", "mine" -> claims.screens().list(player);
            case "here", "info" -> here(claims, player);
            case "show", "border" -> show(claims, player);
            case "trust" -> trust(claims, player, args, true);
            case "untrust" -> trust(claims, player, args, false);
            case "ban" -> ban(claims, player, args);
            case "unban" -> unban(claims, player, args);
            case "cancel" -> claims.selectionFlow().cancel(player);
            default -> claims.messages().send(player, "claim.unknown-subcommand", "word", args[0]);
        }
    }

    /** Opens the claim under the player's feet, or says there is none. */
    private void here(ClaimServices claims, Player player) {
        Optional<Claim> standing = claims.claimAround(player);
        if (standing.isEmpty()) {
            claims.messages().send(player, "claim.none-here");
            return;
        }
        Claim claim = standing.get();
        // Somebody with no business here gets the facts rather than the controls — otherwise a stranger
        // opening the menu of a claim they wandered into sees a page of greyed buttons and no explanation.
        if (!claim.area().may(player.getUniqueId(), LandAction.ENTER)
                && !claims.rights().isServerAdmin(player)) {
            claims.messages().send(player, "claim.belongs-to",
                    "claim", claims.names().possessive(claim));
            return;
        }
        claims.screens().claim(player, claim);
    }

    private void begin(ClaimServices claims, Player player) {
        if (!claims.config().worldEnabled(player.getWorld().getName())) {
            claims.messages().send(player, "error.world-disabled");
            return;
        }
        claims.selectionFlow().begin(player,
                de.raindancer.modules.claims.selection.Selection.Mode.RECTANGLE,
                de.raindancer.modules.claims.selection.Selection.Purpose.NEW_CLAIM,
                null, null, null);
    }

    private void show(ClaimServices claims, Player player) {
        Optional<Claim> standing = claims.claimAround(player);
        if (standing.isEmpty()) {
            claims.messages().send(player, "claim.none-here");
            return;
        }
        claims.visualizer().showClaim(player, standing.get(), claims.config().visualDurationSeconds());
    }

    /** Trusting somebody, which is the one thing a command genuinely does better than a menu. */
    private void trust(ClaimServices claims, Player player, String[] args, boolean adding) {
        Optional<Claim> maybe = manageable(claims, player, ClaimAdminPermission.MANAGE_MEMBERS);
        if (maybe.isEmpty()) {
            return;
        }
        if (args.length < 2) {
            claims.messages().send(player, "claim.who", "usage", "/claim " + args[0] + " <player>");
            return;
        }
        Claim claim = maybe.get();
        Optional<UUID> subject = resolve(claims, args[1]);
        if (subject.isEmpty()) {
            claims.messages().send(player, "error.no-such-player", "player", args[1]);
            return;
        }
        UUID who = subject.get();
        if (claim.isOwner(who)) {
            claims.messages().send(player, "claim.already-an-owner", "player", args[1]);
            return;
        }
        if (adding) {
            claim.memberOrCreate(who).applyDefaultTrust();
            claims.messages().send(player, "claim.trusted", "player", args[1], "claim", claim.name());
        } else if (claim.removeMember(who)) {
            claims.messages().send(player, "claim.untrusted", "player", args[1], "claim", claim.name());
        } else {
            claims.messages().send(player, "claim.not-trusted", "player", args[1]);
            return;
        }
        claims.claimService().saveAsync(claim);
    }

    private void ban(ClaimServices claims, Player player, String[] args) {
        Optional<Claim> maybe = manageable(claims, player, ClaimAdminPermission.MANAGE_BANS);
        if (maybe.isEmpty()) {
            return;
        }
        if (args.length < 2) {
            claims.messages().send(player, "claim.who", "usage", "/claim ban <player> [reason]");
            return;
        }
        Optional<UUID> subject = resolve(claims, args[1]);
        if (subject.isEmpty()) {
            claims.messages().send(player, "error.no-such-player", "player", args[1]);
            return;
        }
        Claim claim = maybe.get();
        if (claim.isOwner(subject.get())) {
            // An owner cannot be barred from their own claim, so saying so beats writing a ban that does
            // nothing and leaving somebody to wonder why it did not work.
            claims.messages().send(player, "claim.cannot-ban-an-owner", "player", args[1]);
            return;
        }
        String reason = args.length > 2 ? String.join(" ", List.of(args).subList(2, args.length)) : "";
        claim.ban(ClaimBan.permanent(subject.get(), player.getUniqueId(), reason));
        claims.claimService().saveAsync(claim);
        claims.broadcasts().banned(claim, args[1], player.getName(), reason);
        // Somebody standing inside when they are barred is walked out, rather than left standing in a
        // claim they may no longer be in until they happen to move.
        Player inside = claims.server().getPlayer(subject.get());
        if (inside != null && claims.claims().at(inside.getLocation())
                .filter(found -> found.id().equals(claim.id())).isPresent()) {
            claims.eviction().evict(inside, claim, "protection.evicted-banned");
        }
        claims.messages().send(player, "claim.banned", "player", args[1], "claim", claim.name());
    }

    private void unban(ClaimServices claims, Player player, String[] args) {
        Optional<Claim> maybe = manageable(claims, player, ClaimAdminPermission.MANAGE_BANS);
        if (maybe.isEmpty() || args.length < 2) {
            if (maybe.isPresent()) {
                claims.messages().send(player, "claim.who", "usage", "/claim unban <player>");
            }
            return;
        }
        Optional<UUID> subject = resolve(claims, args[1]);
        Claim claim = maybe.get();
        if (subject.isEmpty() || !claim.unban(subject.get())) {
            claims.messages().send(player, "claim.not-banned", "player", args[1]);
            return;
        }
        claims.claimService().saveAsync(claim);
        claims.broadcasts().lifted(claim, args[1], player.getName());
        claims.messages().send(player, "claim.ban-lifted", "player", args[1], "claim", claim.name());
    }

    /**
     * The claim the player is standing in, if they may change it in this way.
     *
     * <p>Standing in it rather than naming it, because every one of these is something you do to the land under
     * your feet — and a command that takes a claim name as well as a player name is a command nobody remembers
     * the argument order of.
     */
    private Optional<Claim> manageable(ClaimServices claims, Player player,
                                       ClaimAdminPermission permission) {
        Optional<Claim> standing = claims.claimAround(player);
        if (standing.isEmpty()) {
            claims.messages().send(player, "claim.none-here");
            return Optional.empty();
        }
        if (!claims.rights().canManage(standing.get(), player, permission)) {
            claims.messages().send(player, "error.no-claim-permission");
            return Optional.empty();
        }
        return standing;
    }

    /** A name to a uuid, online or not. Offline included, or you cannot ban somebody who has left. */
    private Optional<UUID> resolve(ClaimServices claims, String name) {
        Player online = claims.server().getPlayerExact(name);
        if (online != null) {
            return Optional.of(online.getUniqueId());
        }
        OfflinePlayer seen = claims.server().getOfflinePlayer(name);
        return seen.hasPlayedBefore() ? Optional.of(seen.getUniqueId()) : Optional.empty();
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            List<String> words = new ArrayList<>(List.of("new", "list", "here", "show", "trust",
                    "untrust", "ban", "unban", "cancel"));
            if (args.length == 1) {
                words.removeIf(word -> !word.startsWith(args[0].toLowerCase(Locale.ROOT)));
            }
            return words;
        }
        if (args.length == 2 && List.of("trust", "untrust", "ban", "unban")
                .contains(args[0].toLowerCase(Locale.ROOT))) {
            List<String> names = new ArrayList<>();
            services.get().server().getOnlinePlayers().forEach(who -> names.add(who.getName()));
            return names;
        }
        return List.of();
    }

    @Override
    public String permission() {
        return USE;
    }
}
