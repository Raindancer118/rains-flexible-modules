package de.raindancer.modules.names.command;

import de.raindancer.modules.names.NamesServices;
import de.raindancer.modules.names.model.NameStyle;
import de.raindancer.modules.names.model.Reagent;
import de.raindancer.modules.names.store.Palette;
import de.raindancer.modules.names.util.Naming;
import de.raindancer.modules.names.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@code /namestyle} — the manual, and the one thing an operator needs to type.
 *
 * <h2>Why it exists at all</h2>
 * Nothing in this module is a Bukkit recipe, so none of it appears in the recipe book, and a feature
 * nobody can discover is a feature nobody has.
 *
 * <p>A player gets {@code screen.PaletteMenu}, because the answer to "which blue is that?" is the
 * colour and not its name. The console gets the same thing as lines of chat, since it has no inventory
 * to open — and so does a player whose menu could not be opened, rather than nothing at all.
 *
 * <p>Both are generated from the palette that is actually loaded, so a server that has changed a dye,
 * added an item or deleted the obfuscated line gets a manual describing its own rules.
 */
public final class NameStyleCommand implements INamesCommand {

    private final Supplier<NamesServices> services;

    /**
     * @param services asked for when the command runs, never captured — see {@link INamesCommand} on why
     *                 a command built at bootstrap cannot hold anything the module built
     */
    public NameStyleCommand(Supplier<NamesServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        NamesServices live = services.get();
        CommandSender sender = source.getSender();

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission(PermissionNodes.RELOAD)) {
                live.messages().send(sender, "names.no-permission");
                return;
            }
            Palette reloaded = live.reloading().reload();
            live.messages().send(sender, "names.reloaded", "count", reloaded.reagents().size());
            return;
        }

        // An unknown word prints the manual rather than one line into silence: somebody typing
        // /namestyle colours wants the manual, and telling them their word was wrong helps nobody.
        if (sender instanceof Player player) {
            live.screens().manual(player);
            return;
        }
        manualInChat(live, sender);
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
                                               String @NotNull [] args) {
        return args.length <= 1 && source.getSender().hasPermission(PermissionNodes.RELOAD)
                ? List.of("reload")
                : List.of();
    }

    @Override
    public String permission() {
        return PermissionNodes.USE;
    }

    @Override
    public String describe() {
        return "how to colour an item's name, and what each dye does on this server";
    }

    /**
     * The manual as lines of chat.
     *
     * <p>The prose comes from {@code messages.yml} so a server can reword it; the listing is generated,
     * because it is the palette and there is nothing to word.
     */
    private static void manualInChat(NamesServices live, CommandSender sender) {
        live.messages().lines("names.manual.intro", "stops", live.config().stops())
                .forEach(sender::sendMessage);
        if (live.config().washInCauldron()) {
            live.messages().lines("names.manual.wash").forEach(sender::sendMessage);
        }
        if (live.config().colourMobNames()) {
            live.messages().lines("names.manual.mobs").forEach(sender::sendMessage);
        }
        sender.sendMessage(Component.empty());

        // An empty palette is a real state — an owner who deleted every line meant it — and saying so
        // beats a heading with nothing under it, which reads as a broken plugin.
        if (live.colours().isEmpty()) {
            live.messages().lines("names.manual.empty").forEach(sender::sendMessage);
            return;
        }

        String heading = null;
        for (Map.Entry<Material, Reagent> entry : live.colours().ordered()) {
            String group = groupOf(entry.getValue());
            if (!group.equals(heading)) {
                heading = group;
                sender.sendMessage(plain(group, NamedTextColor.YELLOW));
            }
            sender.sendMessage(plain("  " + Palette.pretty(entry.getKey()), NamedTextColor.WHITE)
                    .append(plain(" → ", NamedTextColor.DARK_GRAY))
                    .append(sample(entry.getValue())));
        }
    }

    private static String groupOf(Reagent reagent) {
        return switch (reagent) {
            case Reagent.Colour ignored -> "Colours";
            case Reagent.Decoration ignored -> "Decorations";
            case Reagent.Shade ignored -> "Shades";
        };
    }

    /**
     * The reagent, shown in itself — the only description of a colour anybody actually reads.
     *
     * <p>A shade has nothing of its own to show, since it only changes a colour that is already there,
     * so it is demonstrated on a mid grey: enough to see which way it moves.
     */
    private static Component sample(Reagent reagent) {
        NameStyle base = reagent instanceof Reagent.Shade
                ? NameStyle.NONE.withColour(NamedTextColor.GRAY)
                : NameStyle.NONE;
        return Naming.styled(reagent.describe(), reagent.appliedTo(base));
    }

    /** Chat inherits nothing, but a line built here may end up in an item's lore in a later build. */
    private static Component plain(String text, NamedTextColor colour) {
        return Component.text(text, colour).decoration(TextDecoration.ITALIC, false);
    }
}
