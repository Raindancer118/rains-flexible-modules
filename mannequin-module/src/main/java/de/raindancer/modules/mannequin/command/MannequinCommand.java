package de.raindancer.modules.mannequin.command;

import de.raindancer.modules.mannequin.MannequinServices;
import de.raindancer.modules.mannequin.MannequinSettings;
import de.raindancer.modules.mannequin.model.Mannequin;
import de.raindancer.modules.mannequin.model.MannequinKind;
import de.raindancer.modules.mannequin.rules.CreateMannequinRule;
import de.raindancer.modules.mannequin.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /mannequin create|remove|loadout|skin|stats|list} — everything typing is faster for than
 * clicking through a menu, or that takes an argument a menu cannot ask for (an id).
 *
 * <h2>{@code remove} confirms the same way {@code farmworld-module}'s {@code delete} does</h2>
 * {@code /mannequin remove <id> confirm} — a bare {@code /mannequin remove <id>} says what would
 * happen and stops there. Tab completion deliberately never offers the word {@code confirm}
 * itself, so it has to be typed on purpose rather than tabbed past. The GUI's own remove button
 * already confirms through a page; a command with the same effect and no confirmation at all would
 * be a second, weaker entrance to the same irreversible action.
 */
public final class MannequinCommand implements IMannequinCommand {

    private static final List<String> SUBCOMMANDS =
            List.of("create", "remove", "loadout", "skin", "stats", "list");

    private final Supplier<MannequinServices> services;
    private final CreateMannequinRule createRule = new CreateMannequinRule();

    public MannequinCommand(Supplier<MannequinServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        MannequinServices live = services.get();
        CommandSender sender = source.getSender();

        if (args.length == 0) {
            // The same door /claim and /home open bare: whichever answer is right for everybody,
            // rather than a guess about which mannequin somebody meant.
            if (sender instanceof Player player) {
                live.screens().list(player);
                return;
            }
            live.messages().send(sender, "mannequin.usage");
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> create(live, sender, args);
            case "remove" -> withMannequin(live, sender, args, (l, p, m) -> remove(l, p, m, args));
            case "loadout" -> withMannequin(live, sender, args, (l, p, m) -> l.screens().loadout(p, m));
            case "skin" -> withMannequin(live, sender, args, (l, p, m) -> l.screens().skin(p, m));
            case "stats" -> withMannequin(live, sender, args, (l, p, m) -> l.screens().stats(p, m));
            case "list" -> list(live, sender);
            default -> live.messages().send(sender, "mannequin.usage");
        }
    }

    /**
     * {@code /mannequin create [kind]} — {@code kind} is optional and case-insensitive, defaulting
     * to {@code player} when omitted. An unrecognised kind is reported rather than silently
     * defaulted, the same "a refusal says something" rule every screen in this module already
     * follows for a button — a typo here should not quietly hand somebody a different mob than the
     * one they typed.
     */
    private void create(MannequinServices live, CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "mannequin.only-a-player");
            return;
        }
        MannequinSettings settings = live.config();
        if (!createRule.mayCreate(settings.openCreation(), player)) {
            live.messages().send(sender, "mannequin.create.no-permission");
            return;
        }
        MannequinKind kind = MannequinKind.PLAYER;
        if (args.length >= 2) {
            Optional<MannequinKind> requested = MannequinKind.byName(args[1]);
            if (requested.isEmpty()) {
                live.messages().send(sender, "mannequin.create.unknown-kind", "kind", args[1]);
                return;
            }
            kind = requested.get();
        }
        // The full location, not block-snapped: MannequinService#create reads its yaw before
        // deriving the block coordinates, so the dummy faces the way the player was looking.
        Mannequin created = live.mannequins().create(player.getUniqueId(), kind, player.getLocation());
        live.messages().send(sender, "mannequin.create.done", "id", created.id());
    }

    private void remove(MannequinServices live, Player player, Mannequin mannequin, String[] args) {
        boolean owns = mannequin.owner().equals(player.getUniqueId());
        if (!owns && !player.hasPermission(PermissionNodes.REMOVE_ANY)) {
            live.messages().send(player, "mannequin.remove.no-permission");
            return;
        }
        if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
            live.messages().send(player, "mannequin.remove.are-you-sure", "id", mannequin.id());
            return;
        }
        live.mannequins().remove(mannequin.id());
        live.messages().send(player, "mannequin.remove.done", "id", mannequin.id());
    }

    private void list(MannequinServices live, CommandSender sender) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "mannequin.only-a-player");
            return;
        }
        List<Mannequin> owned = live.registry().ownedBy(player.getUniqueId());
        if (owned.isEmpty()) {
            live.messages().send(sender, "mannequin.list.empty");
            return;
        }
        for (Mannequin mannequin : owned) {
            live.messages().send(sender, "mannequin.list.entry", "id", mannequin.id(),
                    "name", mannequin.displayName(), "world", mannequin.world());
        }
    }

    private interface WithMannequin {
        void run(MannequinServices services, Player player, Mannequin mannequin);
    }

    private void withMannequin(MannequinServices live, CommandSender sender, String[] args,
                               WithMannequin action) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "mannequin.only-a-player");
            return;
        }
        if (args.length < 2) {
            live.messages().send(sender, "mannequin.missing-id");
            return;
        }
        Optional<Mannequin> found = live.registry().get(args[1]);
        if (found.isEmpty()) {
            live.messages().send(sender, "mannequin.unknown-id", "id", args[1]);
            return;
        }
        action.run(live, player, found.get());
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
                                               String @NotNull [] args) {
        if (args.length <= 1) {
            String typed = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
            return SUBCOMMANDS.stream().filter(name -> name.startsWith(typed)).toList();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && sub.equals("create")) {
            String typed = args[1].toLowerCase(Locale.ROOT);
            return java.util.Arrays.stream(de.raindancer.modules.mannequin.model.MannequinKind.values())
                    .map(kind -> kind.name().toLowerCase(Locale.ROOT))
                    .filter(name -> name.startsWith(typed))
                    .toList();
        }
        if (args.length == 2 && List.of("remove", "loadout", "skin", "stats").contains(sub)
                && source.getSender() instanceof Player player) {
            MannequinServices live = services.get();
            return live.registry().ownedBy(player.getUniqueId()).stream()
                    .map(Mannequin::id)
                    .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }

    @Override
    public @NotNull String permission() {
        return PermissionNodes.USE;
    }

    @Override
    public String describe() {
        return "create, remove, dress, skin and inspect training dummies";
    }
}
