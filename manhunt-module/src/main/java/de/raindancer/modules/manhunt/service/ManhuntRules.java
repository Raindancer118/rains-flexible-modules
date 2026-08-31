package de.raindancer.modules.manhunt.service;

import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.ManhuntSettings.DifficultyOverride;
import de.raindancer.modules.manhunt.ManhuntSettings.RuleOverride;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The rules this module takes over for the length of a hunt, and hands back exactly as it found them:
 * keep-inventory, natural health regeneration and the difficulty of the hunt's world — plus friendly fire,
 * which is not a game rule at all but belongs with them.
 *
 * <h2>Borrowed, never assumed</h2>
 * Every override records what the world had before it changed anything, and puts that value back when
 * the hunt ends. A plugin that sets a game rule at start-up and never restores it silently rewrites a
 * server's configuration on behalf of one feature — and a Manhunt is a thing that happens for an hour
 * on a server that exists the rest of the time. {@link RuleOverride#UNCHANGED} touches nothing at all
 * and is the default for all three, so a server that has already decided keeps its decision.
 *
 * <h2>Why a restore can find a different value than it left</h2>
 * An owner can change a game rule by hand mid-hunt. The snapshot is still what gets written back:
 * the alternative is comparing and deciding, which needs a rule for what to do when they differ, and
 * "put back what was there before we borrowed it" is the promise this class actually made.
 *
 * <h2>Friendly fire is a listener, not a rule</h2>
 * Vanilla's {@code pvp} game rule is all-or-nothing for a world; what a Manhunt wants is that a
 * Runner cannot hit a Runner while a Hunter still can. That is a question about two players' sides,
 * which only this module can answer, so it is a cancelled damage event and not a borrowed rule.
 */
public final class ManhuntRules implements Listener {

    private final Plugin plugin;
    private final ManhuntService manhunt;

    /** What the world had before this hunt borrowed it. Empty when nothing has been borrowed. */
    private final Map<String, Object> borrowed = new HashMap<>();
    private String borrowedFrom;

    private volatile ManhuntSettings settings;

    public ManhuntRules(Plugin plugin, ManhuntService manhunt, ManhuntSettings settings) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.manhunt = Objects.requireNonNull(manhunt, "manhunt");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** Told the live settings whenever they change — wired via {@code SettingsStore.onChange}. */
    public void settings(ManhuntSettings fresh) {
        this.settings = fresh;
    }

    // ------------------------------------------------------------------------ borrowing and giving back

    /** A hunt has started: take over whatever the settings name, remembering what was there. */
    public void arm() {
        ManhuntSettings config = settings;
        World world = plugin.getServer().getWorld(config.worldName());
        if (world == null) {
            return;
        }
        // A hunt that ended badly could have left a snapshot behind; the world it belonged to is the
        // one to hand it back to before taking anything new.
        disarm();
        borrowedFrom = world.getName();
        borrow(world, GameRules.KEEP_INVENTORY, config.keepInventoryDuringHunt());
        borrow(world, GameRules.NATURAL_HEALTH_REGENERATION, config.naturalRegenerationDuringHunt());
        if (config.difficultyDuringHunt() != DifficultyOverride.UNCHANGED) {
            borrowed.put("difficulty", world.getDifficulty());
            world.setDifficulty(difficultyOf(config.difficultyDuringHunt()));
        }
    }

    /** The hunt is over: everything borrowed goes back exactly as it was. */
    public void disarm() {
        if (borrowedFrom == null) {
            return;
        }
        World world = plugin.getServer().getWorld(borrowedFrom);
        if (world != null) {
            giveBack(world, GameRules.KEEP_INVENTORY);
            giveBack(world, GameRules.NATURAL_HEALTH_REGENERATION);
            if (borrowed.get("difficulty") instanceof Difficulty difficulty) {
                world.setDifficulty(difficulty);
            }
        }
        borrowed.clear();
        borrowedFrom = null;
    }

    private void borrow(World world, GameRule<Boolean> rule, RuleOverride override) {
        if (override == RuleOverride.UNCHANGED) {
            return;
        }
        Boolean before = world.getGameRuleValue(rule);
        borrowed.put(rule.getKey().toString(), before != null ? before : Boolean.FALSE);
        world.setGameRule(rule, override == RuleOverride.ON);
    }

    private void giveBack(World world, GameRule<Boolean> rule) {
        if (borrowed.get(rule.getKey().toString()) instanceof Boolean before) {
            world.setGameRule(rule, before);
        }
    }

    private static Difficulty difficultyOf(DifficultyOverride override) {
        return switch (override) {
            case PEACEFUL -> Difficulty.PEACEFUL;
            case EASY -> Difficulty.EASY;
            case HARD -> Difficulty.HARD;
            // NORMAL, and UNCHANGED which never reaches here — see the guard at the call site.
            default -> Difficulty.NORMAL;
        };
    }

    // ------------------------------------------------------------------------ friendly fire

    /**
     * A hit between two players on the same side, cancelled while a hunt is running and friendly fire
     * is off. Mirrors {@code ManhuntLobbyListener.onDamage} in shape, including reading a projectile
     * back to whoever shot it — an arrow from a team-mate is a team-mate's hit.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!manhunt.isRunning() || settings.friendlyFire()) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = attackerOf(event);
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        if (sameSide(attacker.getUniqueId(), victim.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private boolean sameSide(UUID one, UUID other) {
        var teams = manhunt.teams();
        return (teams.isRunner(one) && teams.isRunner(other))
                || (teams.isHunter(one) && teams.isHunter(other));
    }

    private static Player attackerOf(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player direct) {
            return direct;
        }
        if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    public String describe() {
        return "the rules a hunt borrows and hands back, and that a side cannot hurt its own";
    }
}
