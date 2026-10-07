package de.raindancer.modules.playerutils.service;

import de.raindancer.core.RainsCore;
import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.moderation.players.BodyState;
import de.raindancer.core.moderation.players.Outcome;
import de.raindancer.core.moderation.players.PlayerBody;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.playerutils.PlayerUtilsSettings;
import de.raindancer.modules.playerutils.model.Action;
import de.raindancer.modules.playerutils.model.Reading;
import de.raindancer.modules.playerutils.rules.SpeedRule;
import de.raindancer.modules.playerutils.rules.WipeRule;
import de.raindancer.modules.playerutils.util.PermissionNodes;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectTypeCategory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Doing one action to one player — through Core's {@code PlayerAdmin} and {@code PlayerBody}, which own
 * the ranges and the threads — and then saying so: to whoever did it, to whoever it was done to, and to
 * the audit log.
 *
 * <p>Information actions are not here; they read rather than do, see {@link InfoService}.
 */
public final class ActionService implements IPlayerUtilsService {

    private final Plugin plugin;
    private final RainsCore core;
    private final Messages messages;
    private final Targeting targeting;
    private final FlightService flight;
    private final SpectateService spectate;
    private final SudoService sudo;
    private final WipeRule wipeRule;
    private final SpeedRule speedRule;
    private volatile PlayerUtilsSettings settings;

    public ActionService(Plugin plugin, RainsCore core, Messages messages, Targeting targeting,
                         FlightService flight, SpectateService spectate, SudoService sudo, WipeRule wipeRule,
                         SpeedRule speedRule, PlayerUtilsSettings settings) {
        this.plugin = plugin;
        this.core = core;
        this.messages = messages;
        this.targeting = targeting;
        this.flight = flight;
        this.spectate = spectate;
        this.sudo = sudo;
        this.wipeRule = wipeRule;
        this.speedRule = speedRule;
        this.settings = settings;
    }

    @Override
    public void settings(PlayerUtilsSettings fresh) {
        this.settings = fresh;
    }

    /** Whether {@code action} has to be confirmed before it runs, as typed. */
    public boolean needsConfirmation(Action action, Reading reading, int targets) {
        if (reading.isOn("confirm")) {
            return false;
        }
        if (action == Action.WIPE) {
            return settings.wipeConfirms() || targets > 1;
        }
        return action.harm() == Action.Harm.DESTRUCTIVE && targets > 1;
    }

    /** Checks, does, tells. False when it was refused or did nothing. */
    public boolean attempt(CommandSender sender, Action action, Player target, Reading reading) {
        if (!targeting.mayAct(sender, action, target)) {
            return false;
        }
        Result result = perform(sender, action, target, reading);
        report(sender, action, target, result);
        return result.outcome().isDone();
    }

    /** What happened, and the placeholders the wording needs. */
    public record Result(Outcome outcome, String key, List<Object> values) {

        static Result of(Outcome outcome, Object... values) {
            return new Result(outcome, "", List.of(values));
        }

        static Result refused(String key, Object... values) {
            return new Result(Outcome.NOT_UNDERSTOOD, key, List.of(values));
        }

        static Result handled(Outcome outcome) {
            return new Result(outcome, "handled", List.of());
        }
    }

    private Result perform(CommandSender sender, Action action, Player target, Reading reading) {
        java.util.UUID id = target.getUniqueId();
        return switch (action) {
            case HEAL -> heal(target);
            case FEED -> feed(target);
            case DAMAGE -> damage(sender, target, reading.number("hearts"), reading.isOn("lethal"));
            case STARVE -> {
                int level = (int) Math.round(reading.number("drumsticks") * 2);
                yield Result.of(level == 0 ? core.players().starve(id) : core.players().food(id, level),
                        "drumsticks", plain(reading.number("drumsticks")));
            }
            case DROWN -> Result.of(core.bodies().drown(id, reading.number("hearts") * 2),
                    "hearts", plain(reading.number("hearts")));
            case BREATHE -> Result.of(core.bodies().breathe(id));
            case EXTINGUISH -> target.getFireTicks() <= 0 ? Result.of(Outcome.NOTHING_TO_DO)
                    : Result.of(core.players().extinguish(id));
            case IGNITE -> Result.of(core.bodies().ignite(id, (int) Math.round(reading.number("seconds"))),
                    "seconds", plain(reading.number("seconds")));
            case FLY -> {
                FlightService.Change change = flight.set(target, reading.word("state"));
                yield new Result(change.outcome(), change.nowOn() ? "granted" : "taken", List.of());
            }
            case SPEED -> speed(target, reading);
            case LAUNCH -> Result.of(core.bodies().launch(id, launchWay(reading.word("way")),
                    reading.number("power")), "power", plain(reading.number("power")), "way", reading.word("way"));
            case SCALE -> {
                double size = reading.word("reset").equals("reset") ? 1 : reading.number("size");
                yield Result.of(core.bodies().scale(id, size), "size", plain(size));
            }
            case WIPE -> {
                Set<PlayerBody.Wipe> parts = wipeRule.parts(reading.switches());
                yield Result.of(core.bodies().wipe(id, parts), "parts", wipeRule.describe(parts));
            }
            case EXPLODE -> explode(sender, target, reading);
            case SPECTATE -> {
                if (!(sender instanceof Player viewer)) {
                    yield Result.refused("playerutils.only-a-player");
                }
                yield Result.handled(spectate.start(viewer, target));
            }
            case SUDO -> Result.handled(sudo.run(sender, target, reading.rest()));
            case PING, STATUS, HUNGER, EFFECTS, POSITION, NEAR ->
                    throw new IllegalArgumentException(action + " reads rather than does — see InfoService");
        };
    }

    private Result heal(Player target) {
        java.util.UUID id = target.getUniqueId();
        Outcome health = core.players().heal(id);
        boolean more = false;
        if (settings.healExtinguishes() && target.getFireTicks() > 0) {
            core.players().extinguish(id);
            more = true;
        }
        if (settings.healCuresBadEffects() && hasBadEffect(target)) {
            Scheduling.onOwner(plugin, target, () -> target.getActivePotionEffects().stream()
                    .filter(ActionService::isBad)
                    .map(PotionEffect::getType)
                    .toList()
                    .forEach(target::removePotionEffect));
            more = true;
        }
        if (target.getRemainingAir() < target.getMaximumAir()) {
            core.bodies().breathe(id);
        }
        return Result.of(health == Outcome.NOTHING_TO_DO && more ? Outcome.DONE : health);
    }

    private static boolean hasBadEffect(Player target) {
        return target.getActivePotionEffects().stream().anyMatch(ActionService::isBad);
    }

    private static boolean isBad(PotionEffect effect) {
        return effect.getType().getCategory() == PotionEffectTypeCategory.HARMFUL;
    }

    private Result feed(Player target) {
        Outcome food = core.players().feed(target.getUniqueId());
        boolean hungry = target.getSaturation() < 20f;
        Scheduling.onOwner(plugin, target, () -> {
            target.setSaturation(20f);
            target.setExhaustion(0f);
        });
        return Result.of(food == Outcome.NOTHING_TO_DO && hungry ? Outcome.DONE : food);
    }

    private Result damage(CommandSender sender, Player target, double hearts, boolean lethal) {
        double amount = hearts * 2;
        Outcome outcome = core.players().damage(target.getUniqueId(), amount);
        if (outcome == Outcome.WOULD_KILL && lethal) {
            if (!(sender.hasPermission(PermissionNodes.LETHAL) || !(sender instanceof Player))) {
                return Result.refused("playerutils.damage.lethal-not-allowed");
            }
            outcome = core.players().kill(target.getUniqueId());
            return new Result(outcome, "killed", List.of("hearts", plain(hearts)));
        }
        return Result.of(outcome, "hearts", plain(hearts));
    }

    private Result speed(Player target, Reading reading) {
        java.util.UUID id = target.getUniqueId();
        SpeedRule.Kind kind = speedRule.kind(reading.word("kind"), target.isFlying());
        if (reading.word("reset").equals("reset")) {
            return new Result(core.bodies().resetSpeeds(id), "reset", List.of("kind", "walk and fly"));
        }
        int level = (int) Math.round(reading.number("level"));
        Outcome outcome = switch (kind) {
            case WALK -> core.bodies().walkSpeed(id, level);
            case FLY -> core.bodies().flySpeed(id, level);
            case BOTH -> {
                Outcome walk = core.bodies().walkSpeed(id, level);
                yield walk.isDone() ? core.bodies().flySpeed(id, level) : walk;
            }
        };
        String what = switch (kind) {
            case WALK -> "walking";
            case FLY -> "flying";
            case BOTH -> "walking and flying";
        };
        return Result.of(outcome, "level", level, "kind", what);
    }

    private Result explode(CommandSender sender, Player target, Reading reading) {
        boolean blocks = reading.isOn("blocks");
        boolean fire = reading.isOn("fire");
        boolean console = !(sender instanceof Player);
        if (blocks && !(settings.explosionsBreakBlocks()
                && (console || sender.hasPermission(PermissionNodes.EXPLODE_BLOCKS)))) {
            return Result.refused("playerutils.explode.no-blocks");
        }
        if (fire && !(console || sender.hasPermission(PermissionNodes.EXPLODE_FIRE))) {
            return Result.refused("playerutils.explode.no-fire");
        }
        float power = (float) reading.number("power");
        return Result.of(core.bodies().explode(target.getUniqueId(), power, blocks, fire), "power", plain(power));
    }

    private static PlayerBody.Launch launchWay(String word) {
        return switch (word == null ? "up" : word.toLowerCase(Locale.ROOT)) {
            case "forward" -> PlayerBody.Launch.FORWARD;
            case "look" -> PlayerBody.Launch.LOOK;
            default -> PlayerBody.Launch.UP;
        };
    }

    // ------------------------------------------------------------------ saying so

    private void report(CommandSender sender, Action action, Player target, Result result) {
        if (result.key().equals("handled")) {
            // Sudo audits itself, with the exact line it ran.
            if (result.outcome().isDone() && action != Action.SUDO) {
                audit(sender, action, target, "");
            }
            return;
        }
        boolean self = sender instanceof Player player && player.getUniqueId().equals(target.getUniqueId());
        String shown = PlayerTargets.shownName(target);
        List<Object> values = new ArrayList<>(result.values());
        values.add("player");
        values.add(shown);
        if (!result.key().isEmpty() && result.outcome() == Outcome.NOT_UNDERSTOOD) {
            messages.send(sender, result.key(), values.toArray());
            return;
        }
        Outcome outcome = result.outcome();
        if (!outcome.isDone()) {
            String generic = switch (outcome) {
                case NOTHING_TO_DO -> base(action) + ".nothing";
                case NOT_ONLINE -> "playerutils.not-online";
                case WOULD_KILL -> "playerutils.would-kill";
                case OUT_OF_RANGE -> "playerutils.out-of-range";
                default -> "playerutils.not-understood";
            };
            if (outcome == Outcome.NOTHING_TO_DO && !messages.has(generic)) {
                generic = "playerutils.nothing";
            }
            messages.send(sender, generic, values.toArray());
            return;
        }
        String variant = result.key().isEmpty() ? "" : "." + result.key();
        messages.send(sender, base(action) + variant + (self ? ".self" : ".done"), values.toArray());
        core.effects().play(target.getUniqueId(), cueFor(action));
        if (!self && settings.notifyTargets()) {
            String by = settings.nameTheActor() ? actorName(sender) : "somebody";
            values.add("by");
            values.add(by);
            messages.send(target, base(action) + variant + ".notice", values.toArray());
        }
        audit(sender, action, target, String.join(" ", result.values().stream().map(String::valueOf).toList()));
    }

    private static String base(Action action) {
        return "playerutils." + action.word();
    }

    private static String cueFor(Action action) {
        return switch (action) {
            case HEAL, FEED, BREATHE, EXTINGUISH -> Cues.HEAL;
            case DAMAGE, STARVE, DROWN, IGNITE, EXPLODE -> Cues.HURT;
            case WIPE -> Cues.MAGIC;
            default -> Cues.ABILITY;
        };
    }

    static String actorName(CommandSender sender) {
        return sender instanceof Player player ? PlayerTargets.shownName(player) : "the console";
    }

    void audit(CommandSender sender, Action action, Player target, String detail) {
        AuditEntry.Builder entry = AuditEntry.of("playerutils", "used /" + action.word())
                .to(target.getUniqueId(), target.getName())
                .in(target.getWorld().getName());
        if (sender instanceof Player player) {
            entry.by(player.getUniqueId(), player.getName());
        } else {
            entry.by(null, "console");
        }
        if (!detail.isBlank()) {
            entry.saying(detail);
        }
        core.audit().record(entry);
    }

    static String plain(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** A body read for the menu — empty when they are not online. */
    public Optional<BodyState> body(Player target) {
        return core.bodies().stateOf(target.getUniqueId());
    }

    @Override
    public String describe() {
        return "doing one action to one player, and saying so";
    }
}
