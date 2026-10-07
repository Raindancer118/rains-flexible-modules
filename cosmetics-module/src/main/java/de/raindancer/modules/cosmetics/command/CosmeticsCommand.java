package de.raindancer.modules.cosmetics.command;

import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.ui.prompt.Parsed;
import de.raindancer.core.ui.effect.ParticleShape;
import de.raindancer.core.ui.text.NameStyle;
import de.raindancer.modules.cosmetics.CosmeticsServices;
import de.raindancer.modules.cosmetics.model.Catalogue;
import de.raindancer.modules.cosmetics.model.ClearScope;
import de.raindancer.modules.cosmetics.model.PaletteColour;
import de.raindancer.modules.cosmetics.model.Preset;
import de.raindancer.modules.cosmetics.rules.TypedStyleRule;
import de.raindancer.modules.cosmetics.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * {@code /cosmetics} — the menus, plus what is quicker typed than clicked:
 * {@code name preset <id>}, {@code name set <colours…> [decorations…]}, {@code name reset [player]},
 * {@code particle <name>|off|shape <shape>|colour <colour>}, {@code clear [name|particles|all] [player]},
 * {@code teleport depart|arrive|wait <sound|particle|default|none>}, {@code reload}.
 */
public final class CosmeticsCommand implements ICosmeticsCommand {

    private final Supplier<CosmeticsServices> services;
    private final TypedStyleRule typed = new TypedStyleRule();

    public CosmeticsCommand(Supplier<CosmeticsServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        CosmeticsServices live = services.get();
        CommandSender sender = source.getSender();
        String first = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";

        if (first.equals("reload")) {
            if (!sender.hasPermission(PermissionNodes.ADMIN)) {
                live.messages().send(sender, "cosmetics.no-permission");
                return;
            }
            Catalogue loaded = live.reloading().reload();
            live.messages().send(sender, "cosmetics.reloaded",
                    "colours", loaded.palette().size(), "presets", loaded.presets().size());
            return;
        }
        if (first.equals("clear")) {
            clear(live, sender, Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (first.equals("name") && args.length > 1) {
            name(live, sender, Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if ((first.equals("particle") || first.equals("particles")) && args.length > 1) {
            particle(live, sender, Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if ((first.equals("teleport") || first.equals("tp")) && args.length > 1) {
            teleport(live, sender, Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (sender instanceof Player player) {
            switch (first) {
                case "name" -> live.screens().nameStyle(player);
                case "particle", "particles" -> live.screens().particles(player);
                case "teleport", "tp" -> live.screens().teleports(player);
                default -> live.screens().hub(player);
            }
            return;
        }
        live.messages().send(sender, "cosmetics.usage");
    }

    /** {@code teleport depart|arrive|wait <sound or particle|default|none>} — the same as the menu's rows. */
    private void teleport(CosmeticsServices live, CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "cosmetics.only-a-player");
            return;
        }
        Optional<de.raindancer.modules.cosmetics.model.TeleportPart> part =
                de.raindancer.modules.cosmetics.model.TeleportPart.of(args[0]);
        if (part.isEmpty() || args.length != 2) {
            live.messages().send(player, "cosmetics.teleport.usage");
            return;
        }
        live.teleports().choose(player, part.get(), args[1]);
    }

    /** {@code clear [name|particles|all] [player]} — a scope word is optional, so {@code clear Steve} clears all of Steve's. */
    private void clear(CosmeticsServices live, CommandSender sender, String[] args) {
        Optional<ClearScope> scope = args.length > 0 ? ClearScope.of(args[0]) : Optional.empty();
        int next = scope.isPresent() ? 1 : 0;
        if (args.length > next + 1) {
            live.messages().send(sender, "cosmetics.clear.usage");
            return;
        }
        List<OfflinePlayer> targets;
        if (args.length > next) {
            targets = Targets.anybody(live.server(), live.messages(), sender, args[next]);
            if (targets.isEmpty()) {
                return;
            }
        } else if (sender instanceof Player self) {
            targets = List.of(self);
        } else {
            live.messages().send(sender, "cosmetics.only-a-player");
            return;
        }
        for (OfflinePlayer target : targets) {
            boolean self = sender instanceof Player who && who.getUniqueId().equals(target.getUniqueId());
            if (!live.clearing().may(sender, self)) {
                live.messages().send(sender, self ? "cosmetics.clear.not-allowed" : "cosmetics.clear.not-allowed-others");
                return;
            }
            live.clearing().clear(sender, target, scope.orElse(ClearScope.ALL));
        }
    }

    private void name(CosmeticsServices live, CommandSender sender, String[] args) {
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("reset") && args.length > 1) {
            if (!sender.hasPermission(PermissionNodes.ADMIN)) {
                live.messages().send(sender, "cosmetics.no-permission");
                return;
            }
            for (OfflinePlayer target : Targets.anybody(live.server(), live.messages(), sender, args[1])) {
                live.names().resetOther(sender, target);
            }
            return;
        }
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "cosmetics.only-a-player");
            return;
        }
        switch (action) {
            case "reset" -> live.names().wear(player, NameStyle.NONE);
            case "preset" -> {
                if (args.length < 2) {
                    live.messages().send(player, "cosmetics.usage");
                    return;
                }
                live.offered().preset(args[1]).ifPresentOrElse(
                        preset -> live.names().wear(player, preset),
                        () -> live.messages().send(player, "cosmetics.unknown-preset", "preset", args[1]));
            }
            case "set" -> {
                Parsed<NameStyle> read = typed.read(List.of(args).subList(1, args.length), live.offered());
                if (!read.isOk()) {
                    live.messages().send(player, "cosmetics.not-a-style", "detail", read.problem());
                    return;
                }
                live.names().wear(player, read.value());
            }
            default -> live.messages().send(player, "cosmetics.usage");
        }
    }

    private void particle(CosmeticsServices live, CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "cosmetics.only-a-player");
            return;
        }
        String word = args[0].toLowerCase(Locale.ROOT);
        switch (word) {
            case "off" -> live.particles().takeOff(player, true);
            case "shape" -> {
                java.util.Optional<ParticleShape> shape =
                        args.length > 1 ? ParticleShape.of(args[1]) : java.util.Optional.empty();
                if (shape.isEmpty()) {
                    live.messages().send(player, "cosmetics.particle.unknown-shape");
                    return;
                }
                if (live.particles().current(player).isNone()) {
                    live.messages().send(player, "cosmetics.particle.none-worn");
                    return;
                }
                live.particles().shape(player, shape.get());
                live.messages().send(player, "cosmetics.particle.shaped", "shape", shape.get().title());
            }
            case "density" -> {
                java.util.Optional<de.raindancer.modules.cosmetics.model.ParticleDensity> density =
                        args.length > 1 ? de.raindancer.modules.cosmetics.model.ParticleDensity.of(args[1])
                                : java.util.Optional.empty();
                if (density.isEmpty()) {
                    live.messages().send(player, "cosmetics.particle.unknown-density");
                    return;
                }
                if (live.particles().current(player).isNone()) {
                    live.messages().send(player, "cosmetics.particle.none-worn");
                    return;
                }
                live.particles().density(player, density.get());
                live.messages().send(player, "cosmetics.particle.densified", "density", density.get().title());
            }
            case "speed" -> {
                java.util.Optional<de.raindancer.modules.cosmetics.model.ParticleSpeed> speed =
                        args.length > 1 ? de.raindancer.modules.cosmetics.model.ParticleSpeed.of(args[1])
                                : java.util.Optional.empty();
                if (speed.isEmpty()) {
                    live.messages().send(player, "cosmetics.particle.unknown-speed");
                    return;
                }
                if (live.particles().current(player).isNone()) {
                    live.messages().send(player, "cosmetics.particle.none-worn");
                    return;
                }
                live.particles().speed(player, speed.get());
                live.messages().send(player, "cosmetics.particle.sped", "speed", speed.get().title());
            }
            case "colour", "color" -> {
                TextColor colour = args.length > 1 ? live.offered().colourNamed(args[1])
                        .map(PaletteColour::colour).orElseGet(() -> NameStyle.colourOf(args[1])) : null;
                if (colour == null) {
                    live.messages().send(player, "cosmetics.not-a-style",
                            "detail", "Name a palette colour, a chat colour or a #hex code.");
                    return;
                }
                if (live.particles().current(player).isNone()) {
                    live.messages().send(player, "cosmetics.particle.none-worn");
                    return;
                }
                live.particles().colour(player, colour.value());
                live.messages().send(player, "cosmetics.particle.coloured");
            }
            case "gradient" -> {
                if (live.particles().current(player).isNone()) {
                    live.messages().send(player, "cosmetics.particle.none-worn");
                    return;
                }
                if (args.length > 1 && (args[1].equalsIgnoreCase("off") || args[1].equalsIgnoreCase("none"))) {
                    live.particles().colourTo(player, null);
                    live.messages().send(player, "cosmetics.particle.gradient-off");
                    return;
                }
                TextColor to = args.length > 1 ? live.offered().colourNamed(args[1])
                        .map(PaletteColour::colour).orElseGet(() -> NameStyle.colourOf(args[1])) : null;
                if (to == null) {
                    live.messages().send(player, "cosmetics.not-a-style",
                            "detail", "Name a palette colour, a chat colour or a #hex code — or off.");
                    return;
                }
                live.particles().colourTo(player, to.value());
                live.messages().send(player, "cosmetics.particle.gradient-set");
            }
            default -> live.particles().wear(player, word, true);
        }
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        CosmeticsServices live = services.get();
        CommandSender sender = source.getSender();
        boolean admin = sender.hasPermission(PermissionNodes.ADMIN);
        List<String> options = new ArrayList<>();
        if (args.length <= 1) {
            options.add("name");
            options.add("particle");
            options.add("clear");
            options.add("teleport");
            if (admin) {
                options.add("reload");
            }
        } else if ((args[0].equalsIgnoreCase("teleport") || args[0].equalsIgnoreCase("tp")) && args.length == 2) {
            for (var part : de.raindancer.modules.cosmetics.model.TeleportPart.values()) {
                options.add(part.key());
            }
        } else if ((args[0].equalsIgnoreCase("teleport") || args[0].equalsIgnoreCase("tp")) && args.length == 3) {
            options.add("default");
            options.add("none");
            var part = de.raindancer.modules.cosmetics.model.TeleportPart.of(args[1]);
            if (part.isPresent() && part.get().isSound()) {
                options.addAll(live.config().teleportSounds());
            } else if (part.isPresent()) {
                live.particles().offered().stream().map(name -> name.toLowerCase(Locale.ROOT)).forEach(options::add);
            }
        } else if (args[0].toLowerCase(Locale.ROOT).startsWith("particle") && args.length == 2) {
            options.addAll(List.of("off", "shape", "colour", "gradient", "density", "speed"));
            live.particles().offered().stream().map(name -> name.toLowerCase(Locale.ROOT)).forEach(options::add);
        } else if (args[0].toLowerCase(Locale.ROOT).startsWith("particle") && args.length == 3
                && args[1].equalsIgnoreCase("shape")) {
            for (ParticleShape shape : ParticleShape.values()) {
                options.add(shape.key());
            }
        } else if (args[0].toLowerCase(Locale.ROOT).startsWith("particle") && args.length == 3
                && args[1].equalsIgnoreCase("speed")) {
            for (var speed : de.raindancer.modules.cosmetics.model.ParticleSpeed.values()) {
                options.add(speed.key());
            }
        } else if (args[0].toLowerCase(Locale.ROOT).startsWith("particle") && args.length == 3
                && args[1].equalsIgnoreCase("density")) {
            for (var density : de.raindancer.modules.cosmetics.model.ParticleDensity.values()) {
                options.add(density.key());
            }
        } else if (args[0].toLowerCase(Locale.ROOT).startsWith("particle") && args.length == 3
                && (args[1].toLowerCase(Locale.ROOT).startsWith("colo")
                || args[1].equalsIgnoreCase("gradient"))) {
            live.offered().palette().stream().map(PaletteColour::label)
                    .map(label -> label.replace(' ', '_')).forEach(options::add);
        } else if (args[0].equalsIgnoreCase("clear") && args.length == 2) {
            options.addAll(ClearScope.keys());
            if (sender.hasPermission(PermissionNodes.CLEAR_OTHERS)) {
                options.addAll(knownPlayers(live, sender, args[1]));
            }
        } else if (args[0].equalsIgnoreCase("clear") && args.length == 3
                && ClearScope.of(args[1]).isPresent() && sender.hasPermission(PermissionNodes.CLEAR_OTHERS)) {
            options.addAll(knownPlayers(live, sender, args[2]));
        } else if (args[0].equalsIgnoreCase("name") && args.length == 2) {
            options.addAll(List.of("preset", "set", "reset"));
        } else if (args[0].equalsIgnoreCase("name") && args[1].equalsIgnoreCase("preset") && args.length == 3) {
            live.offered().presets().stream().map(Preset::id).forEach(options::add);
        } else if (args[0].equalsIgnoreCase("name") && args[1].equalsIgnoreCase("reset") && args.length == 3
                && admin) {
            options.addAll(knownPlayers(live, sender, args[2]));
        } else if (args[0].equalsIgnoreCase("name") && args[1].equalsIgnoreCase("set") && args.length >= 3) {
            live.offered().palette().stream().map(PaletteColour::label)
                    .map(label -> label.replace(' ', '_')).forEach(options::add);
            for (TextDecoration decoration : TextDecoration.values()) {
                options.add(decoration.name().toLowerCase(Locale.ROOT));
            }
        }
        String typing = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(typing)).toList();
    }

    /** Names and nicknames of everybody known, leaving out online players the sender may not see. */
    private static List<String> knownPlayers(CosmeticsServices live, CommandSender sender, String typed) {
        Predicate<Player> visible = sender instanceof Player viewer
                ? who -> live.vanish().canSee(viewer.getUniqueId(), who.getUniqueId())
                : who -> true;
        return PlayerTargets.suggest(live.server(), sender, typed, visible);
    }

    @Override
    public String permission() {
        return PermissionNodes.USE;
    }

    @Override
    public String describe() {
        return "your name's colours, gradients and decorations, and a particle effect";
    }
}
