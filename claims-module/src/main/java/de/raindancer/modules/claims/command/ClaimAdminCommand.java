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
import java.util.UUID;
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
            case "transfer" -> transfer(claims, sender, args);
            case "why", "diagnose" -> why(claims, sender);
            case "here", "info" -> here(claims, sender);
            case "alignvisitors" -> alignVisitors(claims, sender);
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
     * Makes every claim treat visitors exactly as it treats trusted players.
     *
     * <p>The one-off fix for ground that has visitors denied something trusted players are allowed —
     * whether that was set on purpose or inherited from data older than the two tiers being kept separate.
     * An owner may still split the two apart afterwards through their own flag screen; this only removes a
     * difference that already exists, once.
     */
    private void alignVisitors(ClaimServices claims, CommandSender sender) {
        int changed = claims.claimService().alignVisitorsToTrusted();
        claims.messages().send(sender, "admin.visitors-aligned", "count", String.valueOf(changed));
    }

    /**
     * Everything about the claim a server admin is standing in, in one place.
     *
     * <p>The question {@code /claimadmin why} does not answer: why does not say how big the claim is, whether
     * it reaches this deep, who else is trusted here, or whether anybody is banned. A report like "monster
     * spawning is off but they still spawn right here" is usually one of those, not the flag — most often the
     * claim's own vertical range not reaching as far down as whoever is standing here, which is why that is
     * the first thing under the header rather than the last.
     */
    private void here(ClaimServices claims, CommandSender sender) {
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

        claims.messages().send(player, "admin.here-header", "claim", claims.names().qualified(claim));
        claims.messages().sendPlain(player, "admin.here-owners",
                "owners", claims.names().allOwners(claim));
        claims.messages().sendPlain(player, "admin.here-shape",
                "world", claim.worldName(),
                "area", String.valueOf(claim.shape().areaBlocks()),
                "min-y", String.valueOf(claim.shape().minY()),
                "max-y", String.valueOf(claim.shape().maxY()),
                "corners", String.valueOf(claim.shape().vertices().size()));
        boolean coversHere = claim.shape().minY() <= player.getLocation().getBlockY()
                && player.getLocation().getBlockY() <= claim.shape().maxY();
        if (!coversHere) {
            claims.messages().sendPlain(player, "admin.here-not-covered",
                    "y", String.valueOf(player.getLocation().getBlockY()));
        }

        int claimAdmins = 0;
        for (var member : claim.members().values()) {
            if (member.isClaimAdmin()) {
                claimAdmins++;
            }
        }
        claims.messages().sendPlain(player, "admin.here-members",
                "count", String.valueOf(claim.members().size()),
                "admins", String.valueOf(claimAdmins));

        long activeBans = claim.bans().keySet().stream()
                .filter(who -> claim.activeBan(who).isPresent()).count();
        if (activeBans > 0) {
            claims.messages().sendPlain(player, "admin.here-bans", "count", String.valueOf(activeBans));
        }

        if (claim.entryFee().enabled()) {
            claims.messages().sendPlain(player, "admin.here-entry-fee",
                    "amount", String.valueOf(claim.entryFee().amount()));
        }
        if (!claim.bank().isEmpty()) {
            claims.messages().sendPlain(player, "admin.here-bank",
                    "items", String.valueOf(claim.bank().items().size()),
                    "xp", String.valueOf(claim.bank().experiencePoints()));
        }

        de.raindancer.core.world.protection.FlagRules flags = claims.flags();
        claims.messages().sendPlain(player, "admin.here-flags-header");
        for (de.raindancer.core.world.protection.LandFlag flag
                : de.raindancer.core.world.protection.LandFlag.values()) {
            if (!flags.isEnforced(flag)) {
                continue;
            }
            if (!flag.audienceAware()) {
                Optional<Boolean> override = claim.flagOverride(flag,
                        de.raindancer.core.world.protection.LandAudience.OWNER);
                boolean verdict = flags.isAllowed(claim.area(), flag);
                claims.messages().sendPlain(player, "admin.here-flag-line",
                        "flag", claims.messages().raw(flag.nameKey()),
                        "override", override.map(value -> value ? "allowed" : "denied").orElse("—"),
                        "verdict", verdict ? "allowed" : "denied");
                continue;
            }
            claims.messages().sendPlain(player, "admin.here-flag-tiers",
                    "flag", claims.messages().raw(flag.nameKey()),
                    "owner", tierSummary(claim, flags, flag,
                            de.raindancer.core.world.protection.LandAudience.OWNER),
                    "trusted", tierSummary(claim, flags, flag,
                            de.raindancer.core.world.protection.LandAudience.TRUSTED),
                    "visitor", tierSummary(claim, flags, flag,
                            de.raindancer.core.world.protection.LandAudience.VISITOR));
        }
    }

    /** "allowed", "denied" — the claim's own choice if it has made one, otherwise what the server defaults to. */
    private String tierSummary(Claim claim, de.raindancer.core.world.protection.FlagRules flags,
                               de.raindancer.core.world.protection.LandFlag flag,
                               de.raindancer.core.world.protection.LandAudience audience) {
        boolean allowed = flags.isAllowed(claim.area(), flag, audience);
        boolean overridden = claim.flagOverride(flag, audience).isPresent();
        String word = allowed ? "allowed" : "denied";
        return overridden ? word : word + " (default)";
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

    /**
     * Hands somebody else's claim over to a different person entirely, by qualified name.
     *
     * <p>What {@code /claim owner add} cannot do and is not meant to: that adds a co-owner alongside
     * whoever already owns it, deliberately unable to touch the original owner — see {@code
     * Claim#removeOwner}. This is the admin route past that protection, for a claim whose owner has
     * left for good, or one made in the wrong person's name that nobody but an admin can now fix.
     */
    private void transfer(ClaimServices claims, CommandSender sender, String[] args) {
        if (args.length < 3) {
            claims.messages().send(sender, "claim.who",
                    "usage", "/claimadmin transfer <owner/claim> <new owner>");
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
        Optional<UUID> newOwner = resolvePlayer(claims, args[2]);
        if (newOwner.isEmpty()) {
            claims.messages().send(sender, "error.no-such-player", "player", args[2]);
            return;
        }
        Claim theClaim = claim.get();
        String oldName = claims.names().qualified(theClaim);
        theClaim.transferTo(newOwner.get());
        claims.claims().reindex(theClaim);
        claims.claimService().saveAsync(theClaim);
        claims.messages().send(sender, "admin.claim-transferred",
                "claim", oldName, "player", args[2]);

        Player online = claims.server().getPlayer(newOwner.get());
        if (online != null) {
            claims.messages().send(online, "admin.claim-transferred-to-you", "claim", theClaim.name());
        }
    }

    /** By name, online or not — an admin reassigning a claim usually means the new owner is not here either. */
    private Optional<UUID> resolvePlayer(ClaimServices claims, String name) {
        Player online = claims.server().getPlayerExact(name);
        if (online != null) {
            return Optional.of(online.getUniqueId());
        }
        org.bukkit.OfflinePlayer seen = claims.server().getOfflinePlayer(name);
        return seen.hasPlayedBefore() ? Optional.of(seen.getUniqueId()) : Optional.empty();
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            List<String> words = new ArrayList<>(List.of(
                    "bypass", "overview", "flags", "zone", "reload", "delete", "transfer", "why",
                    "here", "alignvisitors", "stick", "save", "manual"));
            if (args.length == 1) {
                String prefix = args[0].toLowerCase(Locale.ROOT);
                words.removeIf(word -> !word.startsWith(prefix));
            }
            return words;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("delete") || args[0].equalsIgnoreCase("transfer"))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> claims = new ArrayList<>(services.get().names().suggestions(null));
            claims.removeIf(claim -> !claim.toLowerCase(Locale.ROOT).startsWith(prefix));
            return claims;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("stick")
                || args.length == 3 && args[0].equalsIgnoreCase("transfer")) {
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            services.get().server().getOnlinePlayers().forEach(who -> {
                if (who.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    names.add(who.getName());
                }
            });
            return names;
        }
        return List.of();
    }
}
