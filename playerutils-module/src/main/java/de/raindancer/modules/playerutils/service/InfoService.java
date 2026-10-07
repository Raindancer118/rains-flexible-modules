package de.raindancer.modules.playerutils.service;

import de.raindancer.core.RainsCore;
import de.raindancer.core.moderation.players.PlayerSnapshot;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.text.Markup;
import de.raindancer.modules.playerutils.PlayerUtilsSettings;
import de.raindancer.modules.playerutils.model.Action;
import de.raindancer.modules.playerutils.rules.NearRule;
import de.raindancer.modules.playerutils.rules.PingRule;
import de.raindancer.modules.playerutils.rules.SpeedRule;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Finding things out: ping, status, hunger, effects, position, who is near. Reads each player on their own
 * thread through Core's {@link PlayerSnapshot}, then answers — every line worded in messages.yml so an owner
 * can reshape the whole status card.
 */
public final class InfoService implements IPlayerUtilsService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
            .withZone(ZoneId.systemDefault());

    private final Plugin plugin;
    private final RainsCore core;
    private final Messages messages;
    private final PingRule pings;
    private final NearRule nearRule;
    private final SpeedRule speeds;
    private volatile PlayerUtilsSettings settings;

    public InfoService(Plugin plugin, RainsCore core, Messages messages, PingRule pings, NearRule nearRule,
                       SpeedRule speeds, PlayerUtilsSettings settings) {
        this.plugin = plugin;
        this.core = core;
        this.messages = messages;
        this.pings = pings;
        this.nearRule = nearRule;
        this.speeds = speeds;
        this.settings = settings;
    }

    @Override
    public void settings(PlayerUtilsSettings fresh) {
        this.settings = fresh;
    }

    /** Reads {@code player} on their own thread. Fails after a few seconds if they leave first. */
    public CompletableFuture<PlayerSnapshot> snapshot(Player player) {
        CompletableFuture<PlayerSnapshot> read = new CompletableFuture<>();
        Scheduling.onOwner(plugin, player, () -> {
            try {
                read.complete(PlayerSnapshot.of(player));
            } catch (RuntimeException failed) {
                read.completeExceptionally(failed);
            }
        });
        return read.orTimeout(5, TimeUnit.SECONDS);
    }

    public void show(CommandSender sender, Action action, Player target, int radius) {
        switch (action) {
            case PING -> ping(sender, target);
            case NEAR -> near(sender, target, radius);
            default -> snapshot(target).whenComplete((snapshot, failed) -> {
                if (failed != null) {
                    messages.send(sender, "playerutils.left-meanwhile", "player", PlayerTargets.shownName(target));
                    return;
                }
                switch (action) {
                    case STATUS -> status(sender, target, snapshot);
                    case HUNGER -> hunger(sender, target, snapshot);
                    case EFFECTS -> effects(sender, target, snapshot);
                    case POSITION -> position(sender, target, snapshot);
                    default -> throw new IllegalArgumentException(action + " is not something to read");
                }
            });
        }
    }

    private void ping(CommandSender sender, Player target) {
        PingRule.Grade grade = pings.grade(target.getPing());
        boolean self = sender instanceof Player player && player.equals(target);
        messages.send(sender, self ? "playerutils.ping.self" : "playerutils.ping.done",
                "player", PlayerTargets.shownName(target), "ms", grade.milliseconds(), "bars", Markup.of(grade.drawn()),
                "grade", Markup.of("<" + grade.colour() + ">" + grade.label()));
    }

    private void status(CommandSender sender, Player target, PlayerSnapshot s) {
        messages.send(sender, "playerutils.status.header", "player", PlayerTargets.shownName(target),
                "name", s.name());
        line(sender, "health", "hearts", one(s.hearts()), "max", one(s.maxHealth() / 2),
                "absorption", one(s.absorption() / 2));
        line(sender, "food", "drumsticks", one(s.drumsticks()), "saturation", one(s.saturation()),
                "exhaustion", one(s.exhaustion()));
        line(sender, "air", "air", s.air(), "max", s.maxAir());
        line(sender, "level", "level", s.level(), "progress", Math.round(s.expProgress() * 100),
                "total", s.totalExperience());
        line(sender, "mode", "mode", s.gamemode().toLowerCase(Locale.ROOT), "flight",
                s.flying() ? "flying" : s.allowFlight() ? "may fly" : "on foot",
                "walk", speeds.walkLevel(s.walkSpeed()), "fly", speeds.flyLevel(s.flySpeed()),
                "scale", one(s.scale()));
        line(sender, "where", "world", s.world(), "x", (long) Math.floor(s.x()), "y", (long) Math.floor(s.y()),
                "z", (long) Math.floor(s.z()), "facing", s.facing());
        PingRule.Grade grade = pings.grade(s.ping());
        line(sender, "connection", "ms", s.ping(), "bars", Markup.of(grade.drawn()), "client", s.clientBrand(),
                "locale", s.locale(), "view", s.viewDistance());
        line(sender, "doing", "doing", doing(s), "armour", one(s.armour()), "fire", s.fireTicks() / 20);
        line(sender, "history", "first", DAY.format(Instant.ofEpochMilli(s.firstPlayed())),
                "played", hours(s.playTicks()), "deaths", s.deaths());
        line(sender, "effects-count", "count", s.effects().size());
    }

    private void hunger(CommandSender sender, Player target, PlayerSnapshot s) {
        messages.send(sender, "playerutils.hunger.done", "player", PlayerTargets.shownName(target),
                "bar", Markup.of(bar(s.food(), 20, "gold")), "drumsticks", one(s.drumsticks()),
                "saturation", one(s.saturation()), "exhaustion", one(s.exhaustion()));
    }

    private void effects(CommandSender sender, Player target, PlayerSnapshot s) {
        String shown = PlayerTargets.shownName(target);
        if (s.effects().isEmpty()) {
            messages.send(sender, "playerutils.effects.none", "player", shown);
            return;
        }
        messages.send(sender, "playerutils.effects.header", "player", shown, "count", s.effects().size());
        for (PlayerSnapshot.Effect effect : s.effects()) {
            line(sender, "effect", "effect", effect.name(), "level", roman(effect.level()),
                    "time", effect.isForever() ? "∞" : clock(effect.seconds()));
        }
    }

    private void position(CommandSender sender, Player target, PlayerSnapshot s) {
        long x = (long) Math.floor(s.x());
        long y = (long) Math.floor(s.y());
        long z = (long) Math.floor(s.z());
        String coordinates = x + " " + y + " " + z;
        Location at = new Location(target.getWorld(), s.x(), s.y(), s.z());
        String biome = target.getWorld().getBiome(at.getBlockX(), at.getBlockY(), at.getBlockZ())
                .getKey().getKey().replace('_', ' ');
        messages.send(sender, "playerutils.position.done", "player", PlayerTargets.shownName(target),
                "world", s.world(), "x", x, "y", y, "z", z, "facing", s.facing(),
                "yaw", Math.round(s.yaw()), "pitch", Math.round(s.pitch()),
                "chunkx", x >> 4, "chunkz", z >> 4, "biome", biome);
        Component copy = core.buttons().label("<aqua>[Copy coordinates]").copies(coordinates)
                .tooltip("<gray>" + coordinates).render();
        sender.sendMessage(copy);
    }

    private void near(CommandSender sender, Player viewer, int asked) {
        int radius = nearRule.radius(asked, settings.nearMaxRadius());
        List<Player> others = viewer.getWorld().getPlayers().stream()
                .filter(other -> !other.equals(viewer))
                .filter(other -> core.vanish().canSee(viewer.getUniqueId(), other.getUniqueId()))
                .toList();
        List<CompletableFuture<PlayerSnapshot>> reads = new ArrayList<>();
        reads.add(snapshot(viewer));
        others.forEach(other -> reads.add(snapshot(other).exceptionally(gone -> null)));
        CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).whenComplete((done, failed) -> {
            PlayerSnapshot me = reads.getFirst().getNow(null);
            if (me == null) {
                return;
            }
            List<NearRule.Spot> spots = new ArrayList<>();
            for (CompletableFuture<PlayerSnapshot> read : reads.subList(1, reads.size())) {
                PlayerSnapshot other = read.getNow(null);
                if (other != null) {
                    spots.add(spot(other, nameOf(other)));
                }
            }
            List<NearRule.Nearby> near = nearRule.near(spot(me, me.name()), spots, radius);
            if (near.isEmpty()) {
                messages.send(sender, "playerutils.near.nobody", "radius", radius);
                return;
            }
            messages.send(sender, "playerutils.near.header", "radius", radius, "count", near.size());
            for (NearRule.Nearby nearby : near) {
                line(sender, "near-entry", "player", nearby.name(), "distance", nearby.distance(),
                        "arrow", nearby.arrow(), "height", (nearby.heightDifference() > 0 ? "+" : "")
                                + nearby.heightDifference());
            }
        });
    }

    private String nameOf(PlayerSnapshot snapshot) {
        Player online = plugin.getServer().getPlayer(snapshot.id());
        return online == null ? snapshot.name() : PlayerTargets.shownName(online);
    }

    private static NearRule.Spot spot(PlayerSnapshot s, String name) {
        return new NearRule.Spot(name, s.world(), s.x(), s.y(), s.z(), s.yaw());
    }

    private void line(CommandSender sender, String key, Object... values) {
        // Lines of a card, not separate messages: no prefix on each.
        sender.sendMessage(messages.get("playerutils.lines." + key, values));
    }

    private static String doing(PlayerSnapshot s) {
        List<String> doing = new ArrayList<>();
        if (s.sleeping()) {
            doing.add("sleeping");
        }
        if (s.gliding()) {
            doing.add("gliding");
        }
        if (s.swimming()) {
            doing.add("swimming");
        } else if (s.inWater()) {
            doing.add("in water");
        }
        if (s.sprinting()) {
            doing.add("sprinting");
        }
        if (s.sneaking()) {
            doing.add("sneaking");
        }
        if (s.operator()) {
            doing.add("operator");
        }
        return doing.isEmpty() ? "standing about" : String.join(", ", doing);
    }

    static String bar(int value, int max, String colour) {
        int cells = 10;
        int filled = (int) Math.round((double) value / max * cells);
        return "<" + colour + ">" + "■".repeat(Math.clamp(filled, 0, cells)) + "<dark_gray>"
                + "■".repeat(cells - Math.clamp(filled, 0, cells));
    }

    static String one(double value) {
        double rounded = Math.round(value * 10) / 10.0;
        return rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded);
    }

    static String clock(int seconds) {
        Duration left = Duration.ofSeconds(seconds);
        return left.toHours() > 0
                ? String.format("%d:%02d:%02d", left.toHours(), left.toMinutesPart(), left.toSecondsPart())
                : String.format("%d:%02d", left.toMinutes(), left.toSecondsPart());
    }

    static String hours(long ticks) {
        double hours = ticks / 20.0 / 3600.0;
        return one(hours) + " h";
    }

    static String roman(int level) {
        String[] numerals = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return level >= 0 && level < numerals.length ? numerals[level] : String.valueOf(level);
    }

    @Override
    public String describe() {
        return "finding things out about players";
    }
}
