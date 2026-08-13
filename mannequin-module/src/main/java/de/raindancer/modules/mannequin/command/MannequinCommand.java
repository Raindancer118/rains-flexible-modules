package de.raindancer.modules.mannequin.command;

import de.raindancer.modules.mannequin.MannequinServices;
import de.raindancer.modules.mannequin.MannequinSettings;
import de.raindancer.modules.mannequin.model.Mannequin;
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
            live.messages().send(sender, "mannequin.usage");
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> create(live, sender);
            case "remove" -> withMannequin(live, sender, args, this::remove);
            case "loadout" -> withMannequin(live, sender, args, (l, p, m) -> l.screens().loadout(p, m));
            case "skin" -> withMannequin(live, sender, args, (l, p, m) -> l.screens().skin(p, m));
            case "stats" -> withMannequin(live, sender, args, (l, p, m) -> l.screens().stats(p, m));
            case "list" -> list(live, sender);
            default -> live.messages().send(sender, "mannequin.usage");
        }
    }

    private void create(MannequinServices live, CommandSender sender) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "mannequin.only-a-player");
            return;
        }
        MannequinSettings settings = live.config();
        if (!createRule.mayCreate(settings.openCreation(), player)) {
            live.messages().send(sender, "mannequin.create.no-permission");
            return;
        }
        Mannequin created = live.mannequins().create(player.getUniqueId(),
                player.getLocation().getBlock().getLocation());
        live.messages().send(sender, "mannequin.create.done", "id", created.id());
    }

    private void remove(MannequinServices live, Player player, Mannequin mannequin) {
        boolean owns = mannequin.owner().equals(player.getUniqueId());
        if (!owns && !player.hasPermission(PermissionNodes.REMOVE_ANY)) {
            live.messages().send(player, "mannequin.remove.no-permission");
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
