package de.raindancer.modules.manhunt.service;

import de.raindancer.core.content.achievement.Achievement;
import de.raindancer.core.content.achievement.Achievements;
import de.raindancer.modules.manhunt.model.ManhuntTeams;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A thin wrapper around {@link Achievements} for this module's own curated set — the same "one place
 * owns the rules, everything else just calls it" shape {@link ChaosService} already gives the chaos
 * actions.
 *
 * <h2>Seven in the GUI, two command-only</h2>
 * A menu with thirty achievements crammed into one band is not curated, it is a wall of icons nobody
 * reads — see {@code MenuLayout}'s own note on why a band tops out at seven columns. So only the
 * handful worth a permanent icon in {@code ManhuntAchievementsMenu} are shown there; the rest —
 * currently two, both {@code hidden(true)} so knowing they exist does not spoil them — are reachable
 * only through {@code /manhunt achievements}, which lists everything.
 */
public final class ManhuntAchievements {

    private static final String PLUGIN = "manhunt";

    public static final String FIRST_HUNT = PLUGIN + ":first-hunt";
    public static final String RUNNER_PORTAL = PLUGIN + ":runner-portal";
    public static final String RUNNER_ADVANCEMENT = PLUGIN + ":runner-advancement";
    public static final String HUNTER_ELIMINATION = PLUGIN + ":hunter-elimination";
    public static final String HUNTER_TIMEOUT = PLUGIN + ":hunter-timeout";
    public static final String CHAOS_AGENT = PLUGIN + ":chaos-agent";
    public static final String GATEKEEPER = PLUGIN + ":gatekeeper";
    public static final String OPEN_DOORS = PLUGIN + ":open-doors";
    public static final String CHAOS_VETERAN = PLUGIN + ":chaos-veteran";

    /** The seven shown in {@code ManhuntAchievementsMenu}, in the fixed order they render there. */
    private static final List<String> GUI_KEYS = List.of(
            FIRST_HUNT, RUNNER_PORTAL, RUNNER_ADVANCEMENT, HUNTER_ELIMINATION,
            HUNTER_TIMEOUT, CHAOS_AGENT, GATEKEEPER);

    private final Achievements achievements;

    public ManhuntAchievements(Achievements achievements) {
        this.achievements = Objects.requireNonNull(achievements, "achievements");
    }

    // ------------------------------------------------------------------------ defining

    /** Ships the nine defaults — safe to call every startup, an owner's own edit always wins. */
    public void defineAll() {
        achievements.defineIfAbsent(Achievement.builder(PLUGIN, "first-hunt")
                .title("<gold>Into the Woods")
                .description("Join a hunt that actually starts.")
                .icon(Material.COMPASS)
                .points(5)
                .build());
        achievements.defineIfAbsent(Achievement.builder(PLUGIN, "runner-portal")
                .title("<gold>Through the Portal")
                .description("Win as a Runner by reaching the exit portal.")
                .icon(Material.ENDER_EYE)
                .points(15)
                .build());
        achievements.defineIfAbsent(Achievement.builder(PLUGIN, "runner-advancement")
                .title("<gold>Overachiever")
                .description("Win as a Runner by earning the configured advancement.")
                .icon(Material.NETHER_STAR)
                .points(15)
                .build());
        achievements.defineIfAbsent(Achievement.builder(PLUGIN, "hunter-elimination")
                .title("<gold>Pack Leader")
                .description("Win as a Hunter by hunting down every Runner.")
                .icon(Material.IRON_SWORD)
                .points(15)
                .build());
        achievements.defineIfAbsent(Achievement.builder(PLUGIN, "hunter-timeout")
                .title("<gold>Ran Out The Clock")
                .description("Win as a Hunter by outlasting the timer.")
                .icon(Material.CLOCK)
                .points(15)
                .build());
        achievements.defineIfAbsent(Achievement.builder(PLUGIN, "chaos-agent")
                .title("<gold>Agent of Chaos")
                .description("Throw 5 chaos actions at a running hunt.")
                .icon(Material.BLAZE_POWDER)
                .points(10)
                .goal(5)
                .build());
        achievements.defineIfAbsent(Achievement.builder(PLUGIN, "gatekeeper")
                .title("<gold>Gatekeeper")
                .description("Close the server whitelist during a hunt.")
                .icon(Material.PAPER)
                .points(5)
                .build());
        achievements.defineIfAbsent(Achievement.builder(PLUGIN, "open-doors")
                .title("<gold>Open Doors")
                .description("Open the server whitelist again.")
                .icon(Material.IRON_DOOR)
                .points(5)
                .hidden(true)
                .build());
        achievements.defineIfAbsent(Achievement.builder(PLUGIN, "chaos-veteran")
                .title("<gold>Force of Nature")
                .description("Throw 20 chaos actions at running hunts.")
                .icon(Material.TRIDENT)
                .points(20)
                .goal(20)
                .hidden(true)
                .build());
    }

    // ------------------------------------------------------------------------ awarding

    /** Every hunt that actually starts, to whoever of {@code everybody} is online right now. */
    public void awardFirstHunt(Set<UUID> everybody) {
        if (everybody == null) {
            return;
        }
        for (UUID id : everybody) {
            if (Bukkit.getPlayer(id) != null) {
                achievements.award(id, FIRST_HUNT);
            }
        }
    }

    /**
     * Maps a finished run's {@code SpeedrunOutcome.reason()} to the achievement the winning side
     * earns — {@code "manual"}, {@code "plugin-disable"} or anything else unrecognised means nobody
     * actually won, so nobody is awarded.
     */
    public void awardWin(Set<UUID> everybody, ManhuntTeams teams, String reason) {
        if (everybody == null || teams == null || reason == null) {
            return;
        }
        if (reason.equals("portal-exit")) {
            awardToOnline(teams.runners(), everybody, RUNNER_PORTAL);
        } else if (reason.startsWith("advancement:")) {
            awardToOnline(teams.runners(), everybody, RUNNER_ADVANCEMENT);
        } else if (reason.equals("all-runners-dead")) {
            awardToOnline(teams.hunters(), everybody, HUNTER_ELIMINATION);
        } else if (reason.equals("timeout")) {
            awardToOnline(teams.hunters(), everybody, HUNTER_TIMEOUT);
        }
        // "manual", "plugin-disable" and anything else: nobody actually won, nobody is awarded.
    }

    private void awardToOnline(Set<UUID> side, Set<UUID> everybody, String key) {
        for (UUID id : side) {
            if (everybody.contains(id) && Bukkit.getPlayer(id) != null) {
                achievements.award(id, key);
            }
        }
    }

    /** One chaos action thrown, towards both {@code chaos-agent} and {@code chaos-veteran}. */
    public void progressChaos(Player thrower) {
        if (thrower == null) {
            return;
        }
        UUID id = thrower.getUniqueId();
        achievements.progress(id, CHAOS_AGENT, 1);
        achievements.progress(id, CHAOS_VETERAN, 1);
    }

    public void awardGatekeeper(Player closer) {
        if (closer != null) {
            achievements.award(closer.getUniqueId(), GATEKEEPER);
        }
    }

    public void awardOpenDoors(Player opener) {
        if (opener != null) {
            achievements.award(opener.getUniqueId(), OPEN_DOORS);
        }
    }

    // ------------------------------------------------------------------------ reading

    /** The seven curated ones, in the fixed order {@code ManhuntAchievementsMenu} shows them. */
    public List<Achievement> guiAchievements() {
        return GUI_KEYS.stream().map(key -> achievements.byKey(key).orElseThrow()).toList();
    }

    /** Everything this module defines. */
    public List<Achievement> all() {
        return achievements.ofPlugin(PLUGIN);
    }

    /** What {@code player} should see: everything, minus the hidden ones they have not earned. */
    public List<Achievement> visibleTo(UUID player) {
        return all().stream()
                .filter(achievement -> !achievement.hidden() || achievements.hasEarned(player, achievement.key()))
                .toList();
    }

    /** For a console listing, with nobody to check "earned" against: every non-hidden achievement. */
    public List<Achievement> visibleList() {
        return all().stream().filter(achievement -> !achievement.hidden()).toList();
    }

    /** How many stay out of {@link #visibleList()} — for the console listing to say so honestly. */
    public long hiddenCount() {
        return all().stream().filter(Achievement::hidden).count();
    }

    public boolean hasEarned(UUID player, Achievement achievement) {
        return achievements.hasEarned(player, achievement.key());
    }

    public Optional<Instant> earnedAt(UUID player, Achievement achievement) {
        return achievements.earnedAt(player, achievement.key());
    }

    public int progressOf(UUID player, Achievement achievement) {
        return achievements.progressOf(player, achievement.key());
    }

    public int pointsOf(UUID player) {
        return achievements.pointsOf(player);
    }
}
