package de.raindancer.modules.hungergames.command;

import de.raindancer.core.content.items.CustomItem;
import de.raindancer.modules.hungergames.HungerGamesServices;
import de.raindancer.modules.hungergames.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /hg} — the door to everything.
 *
 * <h2>Why this has so few subcommands</h2>
 * Because almost everything a tournament needs is a page, not a verb. {@code /hg admin} opens the suite;
 * {@code /hg teams} and {@code /hg shop} open the two pages players use; {@code /hg spectate} is for people
 * who are out. Four of the six subcommands here are "open a screen", and that is the design rather than an
 * omission — a gamemaster with forty people waiting should be clicking.
 *
 * <p>The two that are not: {@code /hg status}, because somebody wants to know where the round is without
 * opening an inventory (and because the console can ask it), and {@code /hg end}, because the console has no
 * inventory to hold a confirmation dialog in and a server being shut down needs a way to finish the round
 * cleanly.
 *
 * <h2>Built at bootstrap, run much later</h2>
 * Paper's {@code COMMANDS} lifecycle event fires during the bootstrap phase — before the plugin object
 * exists, let alone this module's services. A handler registered in {@code onEnable} never runs at all: no
 * warning, no exception, the command simply is not there. So this holds a {@link Supplier} and asks at the
 * moment it runs, never at construction.
 */
public final class HungerGamesCommand implements IHungerGamesCommand {

    /** The most of one item {@code /hg give} will hand over at once — a full stack, and a typo guard. */
    private static final int MOST_AT_ONCE = 64;

    private final Supplier<HungerGamesServices> services;

    public HungerGamesCommand(Supplier<HungerGamesServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "the tournament: its screens, its state and its ending";
    }

    @Override
    public String permission() {
        // Deliberately none. /hg teams and /hg shop are a player's, and a permission on the root would put
        // the whole command behind a node that only staff hold — so tributes could not pick a team.
        return "";
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return true;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        HungerGamesServices hg = services.get();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "", "help" -> help(hg, sender);
            case "status" -> status(hg, sender);
            case "admin" -> openFor(hg, sender, PermissionNodes.mayOpenTheAdminSuite(sender),
                    hg.screens()::admin);
            case "teams" -> openFor(hg, sender, true, hg.screens()::teams);
            // No "shop" branch. The sponsor shop opens at a beacon and nowhere else — that is what makes a
            // beacon worth crossing the arena for. A command that opened the same page from anywhere turned
            // the beacon into decoration, which is not a thing a port gets to decide.
            case "spectate" -> openFor(hg, sender, true, hg.screens()::spectate);
            case "give" -> give(hg, sender, args);
            case "end" -> end(hg, sender);
            default -> hg.messages().send(sender, "hungergames.unknown-subcommand", "what", sub);
        }
    }

    /**
     * Opens a page, when the sender is a player and may.
     *
     * <p>The console check first, because "you are not allowed" is a wrong and confusing answer to give a
     * console that simply has no inventory.
     */
    private void openFor(HungerGamesServices hg, CommandSender sender, boolean allowed,
                         java.util.function.Consumer<Player> page) {
        if (!(sender instanceof Player player)) {
            hg.messages().send(sender, "hungergames.only-a-player");
            return;
        }
        if (!allowed) {
            hg.messages().send(sender, "hungergames.not-allowed");
            return;
        }
        page.accept(player);
    }

    /** Where the round is, in one line. Answerable from the console, and by anybody. */
    private void status(HungerGamesServices hg, CommandSender sender) {
        hg.messages().send(sender, "hungergames.status",
                "phase", hg.session().phase().name(),
                "alive", String.valueOf(hg.session().participants().aliveCount()),
                "registered", String.valueOf(hg.session().participants().all().size()),
                "teams", String.valueOf(hg.session().teams().count()));
    }

    /**
     * Ends the round now, scoring it as a time-out would.
     *
     * <p>Guarded by a permission and by the phase, and <em>not</em> by a confirmation — typing it is the
     * confirmation, the same reasoning as {@code /start}. The button on {@code /hg admin} asks first because
     * a button sits next to other buttons; this was typed on purpose.
     */
    private void end(HungerGamesServices hg, CommandSender sender) {
        if (!PermissionNodes.mayOpenTheAdminSuite(sender)) {
            hg.messages().send(sender, "hungergames.not-allowed");
            return;
        }
        if (!hg.control().endRound()) {
            hg.messages().send(sender, "hungergames.step-refused", "step", "end",
                    "why", "no round is running (currently " + hg.session().phase() + ")");
            return;
        }
        hg.log().info("The round was ended by {} through /hg end.", sender.getName());
    }

    /**
     * {@code /hg give <item> [amount] [player]} — a custom item, into somebody's hands.
     *
     * <h2>Why this had to come back</h2>
     * It is how an item is tested. There are fourteen of them, most reachable in a round only through a
     * sponsor purchase or a lucky chest, and an admin checking whether the grappling hook still pulls
     * cannot play until one drops. The old plugin had it ({@code HgCommand:105}) with completion from the
     * item registry; the port dropped it, and the fourteen items became untestable by anybody who was not
     * willing to run a whole round.
     *
     * <h2>The argument order, kept exactly</h2>
     * {@code <item> [amount] [player]}, as the source had it — not the more obvious {@code [player] [item]}.
     * A gamemaster who has typed it three hundred times types it the same way under pressure, and an order
     * that reads better to a fresh pair of eyes is worth nothing against that.
     *
     * <p>From the console the player is required rather than defaulted, because a console has no hands.
     */
    private void give(HungerGamesServices hg, CommandSender sender, String[] args) {
        if (!PermissionNodes.mayOpenTheAdminSuite(sender)) {
            hg.messages().send(sender, "hungergames.not-allowed");
            return;
        }
        if (args.length < 2) {
            hg.messages().send(sender, "hungergames.give-usage");
            return;
        }

        String wanted = args[1].strip();
        Optional<CustomItem> found = hg.items().all().stream()
                .filter(item -> "hungergames".equals(item.plugin()))
                .filter(item -> item.id().equalsIgnoreCase(wanted)
                        // The old file's spelling too: somebody reading a loot.yml types SMOKE_BOMB.
                        || item.id().equalsIgnoreCase(wanted.toLowerCase(Locale.ROOT).replace('_', '-')))
                .findFirst();
        if (found.isEmpty()) {
            hg.messages().send(sender, "hungergames.give-unknown-item", "item", wanted);
            return;
        }

        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Integer.parseInt(args[2].strip());
            } catch (NumberFormatException notANumber) {
                hg.messages().send(sender, "hungergames.give-bad-amount", "amount", args[2]);
                return;
            }
            // Refused rather than clamped, the same rule /init follows: a clamp hands over a number nobody
            // chose, and the person who typed 0 finds out by looking at an empty hand.
            if (amount < 1 || amount > MOST_AT_ONCE) {
                hg.messages().send(sender, "hungergames.give-bad-amount", "amount", args[2]);
                return;
            }
        }

        Player recipient;
        if (args.length >= 4) {
            recipient = hg.server().getPlayerExact(args[3].strip());
            if (recipient == null) {
                hg.messages().send(sender, "hungergames.give-nobody", "who", args[3]);
                return;
            }
        } else if (sender instanceof Player self) {
            recipient = self;
        } else {
            hg.messages().send(sender, "hungergames.give-console-needs-a-player");
            return;
        }

        Optional<ItemStack> made = hg.itemFactory().create(found.get(), amount);
        if (made.isEmpty()) {
            hg.messages().send(sender, "hungergames.give-unknown-item", "item", wanted);
            return;
        }
        ItemStack stack = made.get();
        // Whatever will not fit is dropped at their feet rather than silently lost, which is what
        // addItem's leftovers otherwise are.
        recipient.getInventory().addItem(stack).values()
                .forEach(leftOver -> recipient.getWorld().dropItemNaturally(recipient.getLocation(), leftOver));

        hg.messages().send(sender, "hungergames.give-done",
                "amount", String.valueOf(amount),
                "item", found.get().id(),
                "who", recipient.getName());
        hg.log().info("{} gave {} x{} to {}.", sender.getName(), found.get().id(), amount,
                recipient.getName());
    }

    private void help(HungerGamesServices hg, CommandSender sender) {
        // No help-shop line: the shop has no command any more, and a help page naming one is worse than a
        // help page that is short.
        for (String line : List.of("hungergames.help-header", "hungergames.help-teams",
                "hungergames.help-spectate", "hungergames.help-status")) {
            hg.messages().send(sender, line);
        }
        if (PermissionNodes.mayOpenTheAdminSuite(sender)) {
            hg.messages().send(sender, "hungergames.help-admin");
            hg.messages().send(sender, "hungergames.help-give");
        }
    }

    @Override
    public List<String> suggest(CommandSourceStack source, String[] args) {
        // The item names, for "/hg give <tab>" — the source completed these too (HgCommand:866), and
        // fourteen ids nobody can remember are exactly what completion is for.
        if (args.length == 2 && args[0].equalsIgnoreCase("give")
                && PermissionNodes.mayOpenTheAdminSuite(source.getSender())) {
            String typed = args[1].toLowerCase(Locale.ROOT);
            HungerGamesServices hg = services.get();
            return hg.items().all().stream()
                    .filter(item -> "hungergames".equals(item.plugin()))
                    .map(CustomItem::id)
                    .filter(id -> id.startsWith(typed))
                    .sorted()
                    .toList();
        }
        if (args.length > 1) {
            return List.of();
        }
        String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

        List<String> offered = new ArrayList<>(List.of("teams", "spectate", "status", "help"));
        // Suggested only to somebody who may use them. Offering "admin" to every player is how a page they
        // cannot open becomes the thing they type first.
        if (PermissionNodes.mayOpenTheAdminSuite(source.getSender())) {
            offered.add("admin");
            offered.add("end");
            offered.add("give");
        }
        return offered.stream().filter(one -> one.startsWith(typed)).sorted().toList();
    }
}
