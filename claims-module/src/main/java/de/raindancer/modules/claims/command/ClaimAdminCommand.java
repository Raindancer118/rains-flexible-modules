package de.raindancer.modules.claims.command;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimNames;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimNames;
import de.raindancer.modules.claims.ClaimServices;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /claimadmin} — the server owner's side.
 *
 * <p>Separate from {@code /claim} on purpose. The old plugin mixed them, so an owner tab-completing their own
 * command was offered {@code purge} and {@code reload}, and every one of those had to check a permission it would
 * usually refuse. Two commands means the admin ones are not in anybody else's way and the tab completion of each
 * is short enough to read.
 */
public final class ClaimAdminCommand implements IClaimCommand {

    @Override
    public String describe() {
        return "land administration for the server owner";
    }


    private static final String ADMIN = "rec.admin";

    private final Supplier<ClaimServices> services;

    public ClaimAdminCommand(Supplier<ClaimServices> services) {
        this.services = services;
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return sender.hasPermission(ADMIN);
    }

    @Override
    public String permission() {
        return ADMIN;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        ClaimServices claims = services.get();
        if (args.length == 0) {
            if (sender instanceof Player player) {
                claims.screens().admin(player);
            } else {
                overview(claims, sender);
            }
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "bypass" -> bypass(claims, sender);
            case "overview", "status" -> overview(claims, sender);
            case "flags", "flag" -> flags(claims, sender);
            case "zone" -> zone(claims, sender);
            case "reload" -> reload(claims, sender);
            case "delete" -> delete(claims, sender, args);
            case "why", "diagnose" -> why(claims, sender);
            case "stick" -> giveStick(claims, sender, args);
            case "save" -> save(claims, sender);
            case "manual", "book", "guide" -> manual(claims, sender);
            default -> claims.messages().send(sender, "claim.unknown-subcommand", "word", args[0]);
        }
    }

    /**
     * The admin bypass, which is Core's rather than this module's.
     *
     * <p>Deliberately: one bypass covers every kind of protected ground on the server, so an admin fixing a claim
     * and an admin fixing an arena do not need two different toggles to remember.
     */
    private void bypass(ClaimServices claims, CommandSender sender) {
        if (!(sender instanceof Player player)) {
            claims.messages().send(sender, "error.players-only");
            return;
        }
        boolean on = claims.land().toggleBypass(player);
        claims.messages().send(player, on ? "admin.bypass-on" : "admin.bypass-off");
    }

    /** What this module is doing right now — the first question when something looks wrong. */
    private void overview(ClaimServices claims, CommandSender sender) {
        claims.messages().send(sender, "admin.overview",
                "claims", String.valueOf(claims.claims().size()),
                "zones", String.valueOf(claims.zones().all().size()),
                "tracked", String.valueOf(claims.provider().tracked()),
                "answering", claims.land().provider().map(who -> who.name()).orElse("nobody"));
    }

    /** The flag settings: which flags owners may change, and what a new claim starts with. */
    private void flags(ClaimServices claims, CommandSender sender) {
        if (!(sender instanceof Player player)) {
            claims.messages().send(sender, "error.players-only");
            return;
        }
        claims.messages().send(player, "admin.open-flags");
        new de.raindancer.modules.claims.screen.FlagPolicyMenu(claims, player, null).open();
    }

    private void zone(ClaimServices claims, CommandSender sender) {
        if (!(sender instanceof Player player)) {
            claims.messages().send(sender, "error.players-only");
            return;
        }
        claims.selectionFlow().begin(player,
                de.raindancer.modules.claims.selection.Selection.Mode.RECTANGLE,
                de.raindancer.modules.claims.selection.Selection.Purpose.NO_CLAIM_ZONE,
                null, null, null);
    }

    private void reload(ClaimServices claims, CommandSender sender) {
        claims.claimService().refreshWorldNames();
        claims.messages().send(sender, "admin.reloaded");
    }

    /** The staff manual — the same book as the player one, written for whoever runs the server. */
    private void manual(ClaimServices claims, CommandSender sender) {
        if (!(sender instanceof Player player)) {
            claims.messages().send(sender, "error.players-only");
            return;
        }
        de.raindancer.modules.claims.util.ManualBook manual =
                new de.raindancer.modules.claims.util.ManualBook(claims,
                        de.raindancer.modules.claims.util.ManualBook.Edition.ADMIN);
        player.openBook(manual.asBook());
    }

    /**
     * Why every flag is or is not applying, for the player standing here.
     *
     * <p>The best support tool the old plugin had, and the rewrite lost it. Everything else on this command
     * changes something; this is the only one that explains, and an admin without it is guessing. Flags moved
     * into RainsCore, which makes the question harder to answer by reading a config file rather than easier.
     *
     * <p>Four parts per flag, because the verdict alone is what the reporter already told you: the server's
     * policy, the claim's own override, the audience this player falls in, and only then the answer. Those are
     * the four places a surprise can come from, and naming which one decided it is the whole job.
     */
    private void why(ClaimServices claims, CommandSender sender) {
        if (!(sender instanceof Player player)) {
            claims.messages().send(sender, "error.players-only");
            return;
        }
        if (!claims.rights().isServerAdmin(player)) {
            claims.messages().send(player, "error.not-allowed");
            return;
        }
        Optional<Claim> standing = claims.claimAround(player);
        if (standing.isEmpty()) {
            claims.messages().send(player, "claim.none-here");
            return;
        }

        Claim claim = standing.get();
        de.raindancer.core.world.protection.FlagRules flags = claims.flags();
        de.raindancer.core.world.protection.LandAudience audience =
                flags.audienceOf(claim.area(), player);

        claims.messages().send(player, "admin.why-header",
                "claim", claim.name(),
                "audience", claims.messages().raw(audience.nameKey()));

        for (de.raindancer.core.world.protection.LandFlag flag
                : de.raindancer.core.world.protection.LandFlag.values()) {
            if (!flags.isEnforced(flag)) {
                continue;   // switched off server-wide: it decides nothing, so it explains nothing
            }
            de.raindancer.core.world.protection.FlagPolicy policy = flags.policy(flag);
            Optional<Boolean> override = claim.flagOverride(flag, audience);

            claims.messages().sendPlain(player, "admin.why-line",
                    "flag", claims.messages().raw(flag.nameKey()),
                    "policy", policy.displayName(),
                    "override", override.map(value -> value ? "allowed" : "denied").orElse("—"),
                    "verdict", flags.isAllowedFor(claim.area(), flag, player) ? "allowed" : "denied");
        }
    }

    /**
     * Hands the marking-out tool to somebody else.
     *
     * <p>The one way to help a player who cannot work the selection tool out is to give them one and watch
     * what they do with it. Told to them explicitly: an item appearing in your inventory with no explanation
     * is indistinguishable from a bug.
     */
    private void giveStick(ClaimServices claims, CommandSender sender, String[] args) {
        if (!(sender instanceof Player admin)) {
            claims.messages().send(sender, "error.players-only");
            return;
        }
        if (!claims.rights().isServerAdmin(admin)) {
            claims.messages().send(admin, "error.not-allowed");
            return;
        }
        if (args.length < 2) {
            claims.messages().send(admin, "claim.who", "usage", "/claimadmin stick <player>");
            return;
        }
        Player target = admin.getServer().getPlayerExact(args[1]);
        if (target == null) {
            claims.messages().send(admin, "error.player-not-found", "player", args[1]);
            return;
        }
        claims.stick().give(target,
                de.raindancer.modules.claims.selection.Selection.Purpose.NEW_CLAIM,
                de.raindancer.modules.claims.selection.Selection.Mode.RECTANGLE);
        claims.messages().send(target, "admin.stick-received", "admin", admin.getName());
        claims.messages().send(admin, "admin.stick-given", "player", target.getName());
    }

    /**
     * Writes every claim out now, rather than at the next autosave.
     *
     * <p>Small, and the reason it existed is not: an admin about to try something they might have to undo
     * wants a known-good file on disk first, and the honest answer without this was "wait two minutes".
     */
    private void save(ClaimServices claims, CommandSender sender) {
        if (sender instanceof Player player && !claims.rights().isServerAdmin(player)) {
            claims.messages().send(player, "error.not-allowed");
            return;
        }
        int written = claims.claimService().saveAllBlocking();
        claims.messages().send(sender, "admin.saved", "count", String.valueOf(written));
    }

    /**
     * Deletes somebody else's claim, by qualified name.
     *
     * <p>By name rather than by standing in it, because the claims an admin needs to remove are usually the ones
     * they cannot reach — in a world they are not in, or belonging to somebody who has left. The name is the
     * {@code owner/claim} form, so two people with a claim called "home" is not ambiguous.
     */
    private void delete(ClaimServices claims, CommandSender sender, String[] args) {
        if (args.length < 2) {
            claims.messages().send(sender, "claim.who", "usage", "/claimadmin delete <owner/claim>");
            return;
        }
        ClaimNames.Resolution found = claims.names().resolve(args[1], null);
        if (found.isAmbiguous()) {
            claims.messages().send(sender, "error.ambiguous-claim",
                    "candidates", claims.names().describeCandidates(found.candidates()));
            return;
        }
        Optional<Claim> claim = found.claim();
        if (claim.isEmpty()) {
            claims.messages().send(sender, "error.no-such-claim", "claim", args[1]);
            return;
        }
        String name = claims.names().qualified(claim.get());
        claims.claimService().delete(claim.get(), sender instanceof Player player ? player : null);
        claims.messages().send(sender, "admin.claim-deleted", "claim", name);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            List<String> words = new ArrayList<>(
                    List.of("bypass", "overview", "zone", "reload", "delete"));
            if (args.length == 1) {
                words.removeIf(word -> !word.startsWith(args[0].toLowerCase(Locale.ROOT)));
            }
            return words;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("delete")) {
            return services.get().names().suggestions(null);
        }
        return List.of();
    }
}
