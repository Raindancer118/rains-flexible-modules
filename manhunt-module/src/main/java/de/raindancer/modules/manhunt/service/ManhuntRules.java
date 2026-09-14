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
/*
 * What this class cannot have a unit test for, and why
 * ----------------------------------------------------
 * Every method below touches org.bukkit.GameRules, whose constants are resolved through Paper's
 * RegistryAccess and which therefore cannot even be class-initialised outside a running server —
 * referencing GameRules.LOCATOR_BAR in a test throws NoClassDefFoundError before any assertion runs.
 * That is the same limitation MannequinEquipServiceTest documents for real ItemStacks and
 * Enchantments, and it is why no ManhuntRulesTest exists beside the other service tests. The
 * borrowing here is verified by code review and against a running server, not by JUnit.
 *
 * It is also worth knowing that this is exactly the class most likely to break across Paper builds:
 * GameRule is deprecated-for-removal on 26.2, this module compiles against one build and servers run
 * another, and a moved constant arrives as an Error rather than an exception. ManhuntModule.step()
 * exists so that when that happens it costs this class only, and not the compass handout beside it.
 */
public final class ManhuntRules implements Listener {

    private final Plugin plugin;
    private final ManhuntService manhunt;

    /** What the world had before this hunt borrowed it. Empty when nothing has been borrowed. */
    private final Map<String, Object> borrowed = new HashMap<>();
    private String borrowedFrom;

    /**
     * What each world's locator bar was set to before this hunt borrowed it, by world name.
     *
     * <p>Its own map, and keyed by world, because this is the one borrowed rule that is not taken
     * from the configured world alone — see {@link #arm()}.
     */
    private final Map<String, Boolean> locatorBarBefore = new HashMap<>();

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
        borrowLocatorBar(config.locatorBarDuringHunt());
    }

    /**
     * The locator bar, in every loaded world rather than only the configured one.
     *
     * <h2>Why every world</h2>
     * A Manhunt is played across the overworld, the Nether and the End, and this is a per-world rule.
     * Off in the configured world alone would leave it on for exactly the half of a hunt that happens
     * after a Runner takes a portal — the half where finding them is meant to be hard, and the half
     * the tracking compass' whole cross-world behaviour exists for.
     *
     * <h2>Why its default is OFF and not UNCHANGED</h2>
     * Every other rule here defaults to leaving the server alone. This one does not, because the bar
     * puts every player's direction on everybody's screen: left on, the Hunters do not need a compass,
     * a portal memory, or any of the rest of this module. A server that wants it anyway says so with
     * UNCHANGED or ON.
     */
    private void borrowLocatorBar(RuleOverride override) {
        if (override == RuleOverride.UNCHANGED) {
            return;
        }
        boolean wanted = override == RuleOverride.ON;
        for (World world : plugin.getServer().getWorlds()) {
            Boolean before = world.getGameRuleValue(GameRules.LOCATOR_BAR);
            locatorBarBefore.put(world.getName(), before != null ? before : Boolean.TRUE);
            world.setGameRule(GameRules.LOCATOR_BAR, wanted);
        }
    }

    /** The hunt is over: everything borrowed goes back exactly as it was. */
    public void disarm() {
        // The locator bar first, and outside the borrowedFrom guard below: it is borrowed from every
        // loaded world rather than from the configured one, so a hunt whose world had gone missing —
        // regenerated, unloaded, renamed — would otherwise leave every world's bar switched off for
        // good, with no hunt running to explain it.
        giveLocatorBarBack();
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

    /** Each world's locator bar back to exactly what it was, and never a guess at what it was. */
    private void giveLocatorBarBack() {
        if (locatorBarBefore.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Boolean> was : locatorBarBefore.entrySet()) {
            World world = plugin.getServer().getWorld(was.getKey());
            if (world != null) {
                world.setGameRule(GameRules.LOCATOR_BAR, was.getValue());
            }
        }
        locatorBarBefore.clear();
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
