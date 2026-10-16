package de.raindancer.modules.ctf;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import de.raindancer.modules.ctf.model.Building;
import de.raindancer.modules.ctf.model.GameType;
import de.raindancer.modules.ctf.model.Mutator;
import de.raindancer.modules.ctf.model.Role;
import de.raindancer.modules.ctf.model.TieBreak;
import org.bukkit.Material;

import java.util.Arrays;
import java.util.List;

/**
 * What an owner decides about Capture the Flag. The record is the schema of {@code ctf.yml} and of the
 * {@code /settings} pages. Where things are — maps, the lobby, the parkour — is not a setting: {@code /ctf admin}.
 */
@Settings(id = "ctf", topics = {
        @Topic(path = "ctf", title = "Capture the Flag", icon = Material.RED_BANNER),
        @Topic(path = "ctf/rules", title = "Rules of a round", icon = Material.CLOCK),
        @Topic(path = "ctf/flags", title = "Flags", icon = Material.WHITE_BANNER),
        @Topic(path = "ctf/fun", title = "Power-ups and twists", icon = Material.FIREWORK_ROCKET),
        @Topic(path = "ctf/rewards", title = "Rewards and progress", icon = Material.EMERALD),
        @Topic(path = "ctf/lobby", title = "Lobby", icon = Material.OAK_DOOR),
})
public record CtfSettings(

        // ------------------------------------------------------------------ ctf
        @In("ctf") @Title("World the maps are built in")
        @Describe("An empty world made for the rounds; every map is built into it in turn. Do not use a world "
                + "anybody lives in.")
        @Key("world")
        String world,

        @In("ctf") @Title("Fewest players to start") @Range(min = 2, max = 64)
        @Key("min-players")
        int minPlayers,

        @In("ctf") @Title("Most players in a round") @Range(min = 2, max = 128)
        @Describe("Who joins past this watches instead.")
        @Key("max-players")
        int maxPlayers,

        @In("ctf") @Title("Lobby countdown, seconds") @Range(min = 5, max = 300)
        @Describe("Once enough players are in: how long the votes stay open before the round starts.")
        @Key("countdown-seconds")
        int countdownSeconds,

        @In("ctf") @Title("Countdown when full, seconds") @Range(min = 3, max = 120)
        @Describe("The countdown drops to this once the round is full — nobody waits for more who cannot come.")
        @Key("full-countdown-seconds")
        int fullCountdownSeconds,

        @In("ctf") @Title("Rounds start on their own")
        @Describe("Off: a round only starts when staff start it (/ctf admin start).")
        @Key("auto-start")
        boolean autoStart,

        @In("ctf") @Title("Sides in a round") @Range(min = 2, max = 4)
        @Describe("Two is classic. Three or four need a map with that many bases; on a map with fewer, fewer "
                + "play.")
        @Key("sides")
        int sides,

        @In("ctf") @Title("Kind of round when nobody votes")
        @Key("default-type")
        GameType defaultType,

        @In("ctf") @Title("The lobby votes on the map")
        @Key("vote-maps")
        boolean voteMaps,

        @In("ctf") @Title("The lobby votes on the kind of round")
        @Key("vote-types")
        boolean voteTypes,

        @In("ctf") @Title("The lobby votes on a twist")
        @Describe("Off: a twist comes by chance (below), or never.")
        @Key("vote-mutators")
        boolean voteMutators,

        @In("ctf") @Title("Chance of a twist without a vote, percent") @Range(min = 0, max = 100)
        @Key("mutator-chance")
        int mutatorChance,

        @In("ctf") @Title("Twists that may come up")
        @Describe("The names from the list of twists; take one out to never have it.")
        @Key("mutators")
        List<String> mutators,

        @In("ctf") @Title("Players can join a running round")
        @Describe("They go to the smaller side. Off: they watch until the next round.")
        @Key("join-running")
        boolean joinRunning,

        // ------------------------------------------------------------------ ctf/rules
        @In("ctf/rules") @Title("Captures to win") @Range(min = 1, max = 20)
        @Key("captures-to-win")
        int capturesToWin,

        @In("ctf/rules") @Title("Time limit, minutes") @Range(min = 2, max = 60)
        @Describe("When it runs out the side ahead wins. A one-flag round has half of it per half.")
        @Key("time-limit")
        int timeLimit,

        @In("ctf/rules") @Title("A tie when time runs out")
        @Describe("SUDDEN_DEATH: both flags move to the middle, the next capture wins. NEXT_CAPTURE: play on, the "
                + "next capture wins. DRAW: it is a draw.")
        @Key("tie-break")
        TieBreak tieBreak,

        @In("ctf/rules") @Title("Overtime, minutes") @Range(min = 1, max = 15)
        @Describe("How long overtime may run before it is a draw after all.")
        @Key("overtime")
        int overtime,

        @In("ctf/rules") @Title("Respawn time, seconds") @Range(min = 0, max = 30)
        @Key("respawn-seconds")
        int respawnSeconds,

        @In("ctf/rules") @Title("Teammates can hurt each other")
        @Key("friendly-fire")
        boolean friendlyFire,

        @In("ctf/rules") @Title("Building during a round")
        @Describe("NONE: the map stays as it is. OWN_BLOCKS: place blocks, break only those placed this round. "
                + "FREE: break the map too. Never near a flag or a spawn. Everything is put back after the round.")
        @Key("building")
        Building building,

        @In("ctf/rules") @Title("Kept clear around flags and spawns, blocks") @Range(min = 1, max = 16)
        @Key("keep-clear")
        int keepClear,

        @In("ctf/rules") @Title("Players get hungry")
        @Key("hunger")
        boolean hunger,

        @In("ctf/rules") @Title("Classes")
        @Describe("Off: everybody plays the class below.")
        @Key("classes")
        boolean classes,

        @In("ctf/rules") @Title("Class for somebody who never picked")
        @Key("default-class")
        Role defaultClass,

        // ------------------------------------------------------------------ ctf/flags
        @In("ctf/flags") @Title("A capture counts within, blocks") @Range(min = 1, max = 8)
        @Key("capture-radius")
        int captureRadius,

        @In("ctf/flags") @Title("Your own flag must be home to score")
        @Describe("The classic rule. Off: a side scores even while its own flag is away.")
        @Key("own-flag-home")
        boolean ownFlagHome,

        @In("ctf/flags") @Title("Taking a flag from its stand takes, seconds") @Range(min = 0, max = 10)
        @Describe("Standing at it this long. Scouts take it at once; a dropped flag is grabbed at once.")
        @Key("pickup-seconds")
        int pickupSeconds,

        @In("ctf/flags") @Title("A dropped flag goes home after, seconds") @Range(min = 5, max = 300)
        @Key("return-seconds")
        int returnSeconds,

        @In("ctf/flags") @Title("Flags can be thrown to a teammate")
        @Describe("A carrier right clicks with the flag in hand; it flies where they look and lands as a dropped "
                + "flag for a teammate to grab.")
        @Key("throwing")
        boolean throwing,

        @In("ctf/flags") @Title("Hot flag: glows after, seconds") @Range(min = 0, max = 300)
        @Describe("A carrier holding on this long glows for everybody. 0: never.")
        @Key("glow-after")
        int glowAfter,

        @In("ctf/flags") @Title("Hot flag: slows after, seconds") @Range(min = 0, max = 300)
        @Describe("Then they also slow down a little. 0: never.")
        @Key("slow-after")
        int slowAfter,

        @In("ctf/flags") @Title("A carrier leaves a trail")
        @Key("trail")
        boolean trail,

        @In("ctf/flags") @Title("A side's compass points at its stolen flag")
        @Key("compass")
        boolean compass,

        @In("ctf/flags") @Title("A stop this close to the carrier's base is a clutch, blocks") @Range(min = 1, max = 20)
        @Key("clutch-radius")
        int clutchRadius,

        // ------------------------------------------------------------------ ctf/fun
        @In("ctf/fun") @Title("Power-ups")
        @Describe("Where the map has spots for them: speed, strength, shield, grappling hook, double jump, heal.")
        @Key("power-ups")
        boolean powerUps,

        @In("ctf/fun") @Title("A power-up comes back after, seconds") @Range(min = 5, max = 600)
        @Key("power-up-seconds")
        int powerUpSeconds,

        @In("ctf/fun") @Title("Supply drops")
        @Describe("A chest falls from the sky at one of the map's drop spots, with a beam to find it.")
        @Key("supply-drops")
        boolean supplyDrops,

        @In("ctf/fun") @Title("A supply drop every, seconds") @Range(min = 30, max = 900)
        @Key("supply-seconds")
        int supplySeconds,

        @In("ctf/fun") @Title("Jump pads")
        @Describe("A pressure plate on a slime block throws whoever steps on it. Map builders place them; "
                + "engineers build their own.")
        @Key("jump-pads")
        boolean jumpPads,

        @In("ctf/fun") @Title("Control point")
        @Describe("Where the map has one: the side holding it comes back to life faster.")
        @Key("control-point")
        boolean controlPoint,

        @In("ctf/fun") @Title("Control point: seconds to take it") @Range(min = 2, max = 60)
        @Key("control-seconds")
        int controlSeconds,

        @In("ctf/fun") @Title("Control point: faster respawn, seconds") @Range(min = 0, max = 30)
        @Key("control-bonus")
        int controlBonus,

        @In("ctf/fun") @Title("Announcer")
        @Describe("First blood, double kills, streaks, \"Flag taken!\", \"Denied!\" — on screen and in sound.")
        @Key("announcer")
        boolean announcer,

        @In("ctf/fun") @Title("Fireworks for a capture")
        @Key("capture-show")
        boolean captureShow,

        @In("ctf/fun") @Title("Awards at the end of a round")
        @Key("awards")
        boolean awards,

        @In("ctf/fun") @Title("Each side talks in its own voice group")
        @Describe("Needs a voice plugin that offers groups (Simple Voice Chat through Rain's Voice Bridge).")
        @Key("team-voice")
        boolean teamVoice,

        @In("ctf/fun") @Title("Spectators follow the flag")
        @Describe("Who watches is carried along with a flag carrier; sneaking lets go.")
        @Key("follow-flag")
        boolean followFlag,

        // ------------------------------------------------------------------ ctf/rewards
        @In("ctf/rewards") @Title("Levels, unlocks and experience")
        @Key("levels")
        boolean levels,

        @In("ctf/rewards") @Title("Daily challenges")
        @Key("challenges")
        boolean challenges,

        @In("ctf/rewards") @Title("Challenges a day") @Range(min = 1, max = 6)
        @Key("challenges-per-day")
        int challengesPerDay,

        @In("ctf/rewards") @Title("Achievements")
        @Key("achievements")
        boolean achievements,

        @In("ctf/rewards") @Title("Money for a capture") @Range(min = 0, max = 100000)
        @Describe("In whole units of the server's currency; 0 pays nothing. Needs an economy.")
        @Key("pay-capture")
        int payCapture,

        @In("ctf/rewards") @Title("Money for a return") @Range(min = 0, max = 100000)
        @Key("pay-return")
        int payReturn,

        @In("ctf/rewards") @Title("Money for a kill") @Range(min = 0, max = 100000)
        @Key("pay-kill")
        int payKill,

        @In("ctf/rewards") @Title("Money for each winner") @Range(min = 0, max = 100000)
        @Key("pay-win")
        int payWin,

        @In("ctf/rewards") @Title("Entry fee") @Range(min = 0, max = 100000)
        @Describe("Paid when joining a round; all fees go to the winners, shared. Nobody without the money is "
                + "turned away from watching.")
        @Key("entry-fee")
        int entryFee,

        @In("ctf/rewards") @Title("House cut of the fees, percent") @Range(min = 0, max = 100)
        @Key("house-cut")
        int houseCut,

        // ------------------------------------------------------------------ ctf/lobby
        @In("ctf/lobby") @Title("Lobby items")
        @Describe("A banner to open the menu, a compass to vote, a bed to leave.")
        @Key("lobby-items")
        boolean lobbyItems,

        @In("ctf/lobby") @Title("Parkour in the lobby")
        @Describe("Where staff have set a start and a finish (/ctf admin parkour).")
        @Key("parkour")
        boolean parkour,

        @In("ctf/lobby") @Title("Flag throwing range in the lobby")
        @Describe("A practice flag to throw from where staff have set the line; the distance is shown.")
        @Key("throw-range")
        boolean throwRange,

        @In("ctf/lobby") @Title("Everybody joining the server joins the lobby")
        @Describe("For a server of its own. Off: players type /ctf join.")
        @Key("lobby-on-join")
        boolean lobbyOnJoin) {

    public static final CtfSettings DEFAULTS = new CtfSettings(
            "ctf_arena", 2, 24, 45, 10, true, 2, GameType.CLASSIC, true, true, true, 35,
            Arrays.stream(Mutator.values()).filter(mutator -> mutator != Mutator.NONE).map(Mutator::name).toList(),
            true,
            3, 12, TieBreak.SUDDEN_DEATH, 3, 5, false, Building.NONE, 4, false, true, Role.SOLDIER,
            2, true, 1, 30, true, 15, 30, true, true, 5,
            true, 45, true, 180, true, true, 10, 3, true, true, true, true, true,
            true, true, 3, true, 50, 15, 5, 100, 0, 0,
            true, true, true, false);

    /** The twists that may come up, as read from the list; unknown names are left out. */
    public List<Mutator> allowedMutators() {
        return mutators == null ? List.of() : mutators.stream().map(Mutator::parse).flatMap(java.util.Optional::stream)
                .filter(mutator -> mutator != Mutator.NONE).distinct().toList();
    }

    public int sidesClamped() {
        return Math.max(2, Math.min(4, sides));
    }

    public int maxPlayersClamped() {
        return Math.max(minPlayers, maxPlayers);
    }
}
