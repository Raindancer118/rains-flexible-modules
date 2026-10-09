package de.raindancer.modules.essentials.command;

import de.raindancer.core.platform.command.MistypedCommand;
import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.model.HouseRule;
import de.raindancer.modules.essentials.model.RulePreset;
import de.raindancer.modules.essentials.screen.RulesMenu;
import de.raindancer.modules.essentials.service.RulesService;
import de.raindancer.modules.essentials.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /rules} — reads the rules to anybody; to whoever may manage them, also edits them and their
 * presets, by command or in the editor.
 */
public final class RulesCommand implements IEssentialsCommand {

    static final List<String> MANAGING = List.of("edit", "add", "remove", "punishment", "preset");
    static final List<String> PRESET_WORDS = List.of("list", "show", "take", "apply", "save", "delete");

    private static final String ADD_USAGE = "/rules add <title> | <text>";
    private static final String CONFIRM = "confirm";

    private final Supplier<EssentialsServices> services;

    public RulesCommand(Supplier<EssentialsServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "shows the server's rules, and edits them";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        EssentialsServices live = services.get();
        CommandSender sender = source.getSender();
        RulesService rules = live.rules();
        if (args.length == 0) {
            rules.show(sender);
            return;
        }
        String word = args[0].toLowerCase(Locale.ROOT);
        Optional<Integer> number = number(word);
        if (number.isPresent()) {
            rules.showOne(sender, number.get());
            return;
        }
        if (!MANAGING.contains(word) || !sender.hasPermission(PermissionNodes.RULES_MANAGE)) {
            if (!sender.hasPermission(PermissionNodes.RULES_MANAGE)
                    || !MistypedCommand.subcommand(sender, "rules", args, 0, MANAGING)) {
                live.messages().send(sender, "essentials.usage", "usage", "/rules [number]");
            }
            return;
        }
        switch (word) {
            case "edit" -> {
                if (sender instanceof Player player) {
                    new RulesMenu(live, player, null).open();
                } else {
                    live.messages().send(sender, "essentials.only-a-player");
                }
            }
            case "add" -> add(live, sender, args);
            case "remove" -> remove(live, sender, args);
            case "punishment" -> punishment(live, sender, args);
            default -> preset(live, sender, args);
        }
    }

    private static void add(EssentialsServices live, CommandSender sender, String[] args) {
        String rest = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        int bar = rest.indexOf('|');
        if (bar < 0) {
            live.messages().send(sender, "essentials.usage", "usage", ADD_USAGE);
            return;
        }
        live.rules().add(sender, rest.substring(0, bar), rest.substring(bar + 1));
    }

    private static void remove(EssentialsServices live, CommandSender sender, String[] args) {
        Optional<HouseRule> rule = args.length < 2 ? Optional.empty()
                : number(args[1]).flatMap(live.rules().book()::byNumber);
        if (args.length < 2 || number(args[1]).isEmpty()) {
            live.messages().send(sender, "essentials.usage", "usage", "/rules remove <number>");
            return;
        }
        rule.ifPresentOrElse(found -> live.rules().remove(sender, found.id()),
                () -> live.messages().send(sender, "essentials.rules.no-such", "number", args[1]));
    }

    /** {@code /rules punishment <number> <warn, mute 1h, ban 3d, ban>} — nothing after the number clears it. */
    private static void punishment(EssentialsServices live, CommandSender sender, String[] args) {
        Optional<HouseRule> rule = args.length < 2 ? Optional.empty()
                : number(args[1]).flatMap(live.rules().book()::byNumber);
        if (rule.isEmpty()) {
            live.messages().send(sender, "essentials.usage", "usage", "/rules punishment <number> <warn, mute 1h, ban>");
            return;
        }
        live.rules().setPenalties(sender, rule.get().id(), String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
    }

    private static void preset(EssentialsServices live, CommandSender sender, String[] args) {
        String what = args.length < 2 ? "list" : args[1].toLowerCase(Locale.ROOT);
        if (what.equals("list")) {
            live.rules().listPresets(sender);
            return;
        }
        if (!PRESET_WORDS.contains(what)) {
            if (!MistypedCommand.subcommand(sender, "rules", args, 1, PRESET_WORDS)) {
                live.messages().send(sender, "essentials.usage", "usage", "/rules preset <list|apply|save|delete>");
            }
            return;
        }
        if (args.length < 3) {
            live.messages().send(sender, "essentials.usage", "usage", "/rules preset " + what + " <name>");
            return;
        }
        String name = args[2];
        boolean confirmed = args.length > 3 && args[3].equalsIgnoreCase(CONFIRM);
        switch (what) {
            case "show" -> live.rules().showPreset(sender, name);
            case "take" -> {
                Optional<Integer> which = args.length > 3 ? number(args[3]) : Optional.empty();
                if (which.isEmpty()) {
                    live.messages().send(sender, "essentials.usage", "usage", "/rules preset take <name> <number>");
                } else {
                    live.rules().takeFromPreset(sender, name, which.get());
                }
            }
            case "save" -> live.rules().savePreset(sender, name,
                    String.join(" ", Arrays.copyOfRange(args, 3, args.length)));
            case "apply" -> {
                if (confirmed) {
                    live.rules().applyPreset(sender, name);
                } else {
                    askFirst(live, sender, "essentials.rules.preset.confirm-apply", name,
                            "/rules preset apply " + name + " " + CONFIRM);
                }
            }
            default -> {
                if (confirmed) {
                    live.rules().deletePreset(sender, name);
                } else {
                    askFirst(live, sender, "essentials.rules.preset.confirm-delete", name,
                            "/rules preset delete " + name + " " + CONFIRM);
                }
            }
        }
    }

    /** The one irreversible step said out loud, with the button that takes it — typed, it needs "confirm". */
    private static void askFirst(EssentialsServices live, CommandSender sender, String key, String name,
                                 String command) {
        if (live.rules().book().preset(de.raindancer.modules.essentials.rules.HouseRuleTextRule.presetName(name))
                .isEmpty()) {
            live.messages().send(sender, "essentials.rules.preset.no-such", "name", name);
            return;
        }
        live.messages().send(sender, key, "name", name, "command", command);
        if (sender instanceof Player) {
            sender.sendMessage(live.core().buttons().row(
                    live.core().buttons().label(live.messages().raw("essentials.rules.preset.confirm-button"))
                            .runs(command)));
        }
    }

    private static Optional<Integer> number(String text) {
        try {
            return Optional.of(Integer.parseInt(text));
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!sender.hasPermission(PermissionNodes.RULES_MANAGE)) {
            return List.of();
        }
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        if (args.length <= 1) {
            options.addAll(MANAGING);
        } else if (args[0].equalsIgnoreCase("preset") && args.length == 2) {
            options.addAll(PRESET_WORDS);
        } else if (args[0].equalsIgnoreCase("preset") && args.length == 3
                && List.of("show", "take", "apply", "delete", "save").contains(args[1].toLowerCase(Locale.ROOT))) {
            services.get().rules().book().presets().stream().map(RulePreset::name).forEach(options::add);
        } else if (args[0].equalsIgnoreCase("punishment") && args.length >= 3) {
            options.addAll(List.of("warn,", "kick,", "mute 1h,", "ban 3d,", "ban"));
        } else if ((args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("punishment")) && args.length == 2) {
            for (int number = 1; number <= services.get().rules().book().rules().size(); number++) {
                options.add(String.valueOf(number));
            }
        }
        return options.stream().filter(option -> option.startsWith(typed)).toList();
    }

    @Override
    public String permission() {
        return PermissionNodes.RULES;
    }
}
