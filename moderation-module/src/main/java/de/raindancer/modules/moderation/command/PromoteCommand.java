package de.raindancer.modules.moderation.command;

import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.StaffRank;
import de.raindancer.modules.moderation.util.Players;
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
 * {@code /promote <player> [rank]} — makes somebody staff.
 *
 * <h2>Why this is not behind a moderation permission</h2>
 * Because it is the one command that <em>hands out</em> moderation permissions, and anything that can
 * grant a power must not be grantable by that power. A moderator who could promote could promote
 * themselves to admin, which makes every tier below admin decorative. So it asks for op — the level the
 * server owner holds and cannot be given from inside the game.
 *
 * <h2>Why a rank rather than a node</h2>
 * A promotion should be a decision somebody can state: "she's a helper". Handing out individual nodes is
 * possible too, on the person's own page, and it is deliberately the slower path — the preset is the
 * rule and the toggle is the exception.
 */
public final class PromoteCommand implements IModerationCommand {

    /**
     * Ops only.
     *
     * <p>A node rather than a bare {@code isOp()} check so a server running LuckPerms can grant it
     * deliberately — but it is not in any preset, so nothing in this module can hand it out.
     */
    public static final String USE = "rains.moderation.promote";

    private final Supplier<ModerationServices> services;

    /** Whoever typed it, as an id — null for the console, which every rule reads as the owner. */
    private static UUID actorOf(CommandSender sender) {
        return sender instanceof org.bukkit.entity.Player player ? player.getUniqueId() : null;
    }

    public PromoteCommand(Supplier<ModerationServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "makes somebody staff at one of the four ranks";
    }

    @Override
    public String permission() {
        return USE;
    }

    @Override
    public boolean canUse(CommandSender sender) {
        // The owner always, and any staff member who may appoint below themselves. Brigadier calls this
        // while *resolving* the command, so it has to be cheap and must not touch the module's state —
        // hence the permission check here and the full rule at execution.
        return sender.isOp() || sender.hasPermission(USE)
                || sender.hasPermission(ModerationPermission.APPOINT.node());
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        ModerationServices moderation = services.get();

        if (args.length == 0) {
            moderation.messages().send(sender, "moderation.usage",
                    "usage", "/promote <player> [" + String.join("|", StaffRank.names()) + "]");
            return;
        }
        Optional<OfflinePlayer> found = Players.find(moderation.server(), args[0]);
        if (found.isEmpty()) {
            moderation.messages().send(sender, "moderation.no-such-player", "player", args[0]);
            return;
        }
        OfflinePlayer them = found.get();
        String name = Players.nameOf(them);

        // No rank named: the next one up, so `/promote somebody` twice is a ladder rather than an
        // error message. Somebody already at the top is told so rather than silently re-granted.
        Optional<StaffRank> wanted = args.length > 1
                ? StaffRank.byName(args[1])
                : nextUpFrom(moderation, them);
        if (wanted.isEmpty()) {
            if (args.length > 1) {
                moderation.messages().send(sender, "moderation.rank.unknown", "word", args[1],
                        "ranks", String.join(", ", StaffRank.names()));
            } else {
                moderation.messages().send(sender, "moderation.rank.already-at-the-top",
                        "player", name, "rank", StaffRank.ADMIN.title());
            }
            return;
        }
        // The rule, not an op check: a mod may appoint a trial mod, an admin a mod, and nobody their
        // own equal. See PromotionRule — the ladder having no top is what this prevents.
        var allowed = moderation.promotionRule()
                .mayPromote(actorOf(sender), them.getUniqueId(), wanted.get());
        if (allowed.isRefused()) {
            allowed.refusal().ifPresent(key -> moderation.messages().send(sender, key,
                    "rank", allowed.detail() == null ? "" : allowed.detail(),
                    "player", name));
            return;
        }
        moderation.staff().promote(sender, them.getUniqueId(), name, wanted.get());
    }

    /** The rung above whatever they are on, or empty when they are already at the top. */
    private Optional<StaffRank> nextUpFrom(ModerationServices moderation, OfflinePlayer them) {
        Optional<StaffRank> current = moderation.roster().rankOf(them.getUniqueId());
        if (current.isEmpty()) {
            return Optional.of(StaffRank.values()[0]);
        }
        StaffRank[] ladder = StaffRank.values();
        int at = current.get().ordinal();
        return at + 1 < ladder.length ? Optional.of(ladder[at + 1]) : Optional.empty();
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            return Players.suggestions(services.get().server(), args.length == 1 ? args[0] : "");
        }
        if (args.length == 2) {
            List<String> ranks = new ArrayList<>(StaffRank.names());
            ranks.removeIf(rank -> !rank.startsWith(args[1].toLowerCase(Locale.ROOT)));
            return ranks;
        }
        return List.of();
    }
}
