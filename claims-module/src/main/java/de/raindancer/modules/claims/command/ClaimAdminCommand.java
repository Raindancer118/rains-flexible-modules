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
