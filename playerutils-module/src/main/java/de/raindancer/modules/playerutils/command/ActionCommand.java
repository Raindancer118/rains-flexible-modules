package de.raindancer.modules.playerutils.command;

import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.modules.playerutils.PlayerUtilsServices;
import de.raindancer.modules.playerutils.model.Action;
import de.raindancer.modules.playerutils.model.Parameter;
import de.raindancer.modules.playerutils.model.Reading;
import de.raindancer.modules.playerutils.screen.ConfirmScreen;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One command per {@link Action}, all the same shape: who (yourself if nobody, a name, a nickname, a
 * selector) and the action's own parameters, in any order. Destructive ones ask first.
 */
public final class ActionCommand implements IPlayerUtilsCommand {

    private final Supplier<PlayerUtilsServices> services;
    private final Action action;

    public ActionCommand(Supplier<PlayerUtilsServices> services, Action action) {
        this.services = services;
        this.action = action;
    }

    public Action action() {
        return action;
    }

    @Override
    public String describe() {
        return action.describe();
    }

    @Override
    public String permission() {
        return action.node();
    }

    /** {@code /damage [player] [hearts] [lethal]} */
    public static String usage(Action action) {
        StringBuilder usage = new StringBuilder("/").append(action.word());
        if (action != Action.NEAR) {
            usage.append(action.self() == Action.Self.NEVER ? " <player>" : " [player]");
        }
        for (Parameter parameter : action.parameters()) {
            usage.append(' ').append(parameter.usage());
        }
        return usage.toString();
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        run(source.getSender(), args);
    }

    public void run(CommandSender sender, String[] args) {
        PlayerUtilsServices live = services.get();
        Reading reading = live.arguments().read(action, args);
        if (!reading.isUnderstood()) {
            live.messages().send(sender, "playerutils.did-not-understand",
                    "problem", reading.problems().getFirst(), "usage", usage(action));
            return;
        }
        if (action == Action.NEAR) {
            if (!(sender instanceof Player viewer)) {
                live.messages().send(sender, "playerutils.only-a-player");
                return;
            }
            if (reading.target().isPresent()) {
                live.messages().send(sender, "playerutils.did-not-understand",
                        "problem", "/near is always about you", "usage", usage(action));
                return;
            }
            if (live.targeting().mayAct(sender, action, viewer)) {
                live.info().show(sender, action, viewer, (int) Math.round(reading.number("radius")));
            }
            return;
        }
        List<Player> targets = live.targeting().resolve(sender, reading.target());
        if (targets.isEmpty()) {
            return;
        }
        if (action.isInfo()) {
            for (Player target : targets) {
                if (live.targeting().mayAct(sender, action, target)) {
                    live.info().show(sender, action, target, 0);
                }
            }
            return;
        }
        if (live.actions().needsConfirmation(action, reading, targets.size())) {
            confirm(live, sender, args, targets, reading);
            return;
        }
        perform(live, sender, targets, reading);
    }

    private void perform(PlayerUtilsServices live, CommandSender sender, List<Player> targets, Reading reading) {
        int done = 0;
        for (Player target : targets) {
            if (live.actions().attempt(sender, action, target, reading)) {
                done++;
            }
        }
        if (targets.size() > 1) {
            live.messages().send(sender, "playerutils.summary", "action", action.word(),
                    "done", done, "count", targets.size());
        }
    }

    private void confirm(PlayerUtilsServices live, CommandSender sender, String[] args, List<Player> targets,
                         Reading reading) {
        String who = targets.size() == 1 ? PlayerTargets.shownName(targets.getFirst()) : targets.size() + " players";
        if (!(sender instanceof Player viewer)) {
            live.messages().send(sender, "playerutils.confirm-needed", "action", action.word(), "who", who,
                    "command", "/" + action.word() + " " + String.join(" ", args) + " confirm");
            return;
        }
        String what = action == Action.WIPE
                ? live.wipeRule().describe(live.wipeRule().parts(reading.switches()))
                : action.describe().toLowerCase(Locale.ROOT);
        List<String> consequences = new ArrayList<>();
        consequences.add("<gray>" + action.word() + " → <white>" + de.raindancer.core.ui.text.Text.literal(who));
        consequences.add("<gray>Takes: <white>" + what);
        String[] confirmed = Arrays.copyOf(args, args.length + 1);
        confirmed[args.length] = "confirm";
        Reading sure = live.arguments().read(action, confirmed);
        new ConfirmScreen(live, viewer, null, "<red>" + capitalised(action.word()) + " " + who + "?",
                consequences, () -> {
                    viewer.closeInventory();
                    perform(live, sender, targets, sure);
                }).open();
    }

    private static String capitalised(String word) {
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        PlayerUtilsServices live = services.get();
        CommandSender sender = source.getSender();
        String typed = args.length == 0 ? "" : args[args.length - 1];
        String lowered = typed.toLowerCase(Locale.ROOT);
        Set<String> options = new LinkedHashSet<>();
        String[] before = args.length == 0 ? args : Arrays.copyOf(args, args.length - 1);
        Reading sofar = live.arguments().read(action, before);
        boolean takesRest = action.parameters().stream().anyMatch(p -> p.kind() == Parameter.Kind.REST);
        if (takesRest) {
            if (args.length <= 1) {
                options.addAll(players(live, sender, typed));
            } else if (args.length == 2) {
                options.add("c:");
            }
            return filter(options, lowered);
        }
        if (sofar.target().isEmpty() && action != Action.NEAR
                && (sender.hasPermission(action.othersNode()) || !(sender instanceof Player))) {
            options.addAll(players(live, sender, typed));
        }
        for (Parameter parameter : action.parameters()) {
            switch (parameter.kind()) {
                case WORD -> {
                    boolean given = Arrays.stream(before).anyMatch(word -> parameter.wordFor(word).isPresent());
                    if (!given) {
                        options.addAll(parameter.words());
                    }
                }
                case SWITCH -> {
                    if (!sofar.isOn(parameter.name())) {
                        options.add(parameter.words().getFirst());
                    }
                }
                case NUMBER -> {
                    if (Arrays.stream(before).noneMatch(word -> word.matches("[\\d.,]+[hxs%]?"))) {
                        options.addAll(numberHints(parameter));
                    }
                }
                case REST -> {
                }
            }
        }
        return filter(options, lowered);
    }

    private static List<String> players(PlayerUtilsServices live, CommandSender sender, String typed) {
        if (sender instanceof Player viewer) {
            List<String> names = new ArrayList<>(PlayerTargets.suggest(live.server(), typed,
                    other -> live.core().vanish().canSee(viewer.getUniqueId(), other.getUniqueId())));
            if (!viewer.hasPermission(de.raindancer.modules.playerutils.util.PermissionNodes.SELECTORS)) {
                names.removeIf(PlayerTargets::isSelector);
            }
            return names;
        }
        return PlayerTargets.suggest(live.server(), typed);
    }

    private static List<String> numberHints(Parameter parameter) {
        List<String> hints = new ArrayList<>();
        double fallback = Double.isNaN(parameter.fallback()) ? parameter.min() : parameter.fallback();
        for (double value : new double[]{parameter.min(), fallback, parameter.max()}) {
            String written = value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
            if (!hints.contains(written)) {
                hints.add(written);
            }
        }
        return hints;
    }

    private static List<String> filter(Collection<String> options, String lowered) {
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lowered)).toList();
    }
}
