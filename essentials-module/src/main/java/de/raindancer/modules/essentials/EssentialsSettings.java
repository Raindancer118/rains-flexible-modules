package de.raindancer.modules.essentials;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

import java.util.List;

/**
 * What an owner can decide about the everyday stuff: spawn, AFK, and the words around joining,
 * leaving and being called something.
 *
 * <h2>Where the nickname blocklist went</h2>
 * Not here. A list of names, grouped into sections each an owner can switch off, reads and edits
 * far better as its own plain YAML file than as one more comma-joined line in this settings screen —
 * see {@link de.raindancer.modules.essentials.store.NicknameBlocklist}.
 */
@Settings(id = "essentials", topics = {
        @Topic(path = "essentials/spawn", title = "Spawn", icon = Material.RED_BED),
        @Topic(path = "essentials/afk", title = "AFK", icon = Material.CLOCK),
        @Topic(path = "essentials/social", title = "Messages & nicknames", icon = Material.WRITABLE_BOOK),
        @Topic(path = "essentials/fun", title = "Roasts & jokes", icon = Material.CAKE,
                description = "/roast and /joke. What they say is in messages.yml under essentials.fun."),
})
public record EssentialsSettings(

        @In("essentials/spawn") @Title("Stand still for") @Range(min = 0, max = 60)
        @Describe("Seconds /spawn makes somebody wait before sending them, so it is not a free way "
                + "out of a fight. Zero sends them at once.")
        int spawnWarmupSeconds,

        @In("essentials/afk") @Title("AFK detection")
        @Describe("Whether standing still, not talking and not typing a command marks somebody AFK "
                + "at all.")
        boolean afkEnabled,

        @In("essentials/afk") @Title("Away after") @Range(min = 15, max = 7200)
        @Describe("Seconds of no movement, chat or command before somebody is marked AFK.")
        int afkTimeoutSeconds,

        @In("essentials/afk") @Title("Announce it")
        @Describe("Whether going AFK and coming back are said to everybody, or kept to the "
                + "nametag and the player list alone.")
        boolean afkBroadcast,

        @In("essentials/social") @Title("Join and quit lines")
        @Describe("Whether this module says who joined and who left, in its own words below. Off "
                + "leaves the vanilla lines exactly as they are.")
        boolean joinQuitEnabled,

        @In("essentials/social") @Title("Welcome a new player")
        @Describe("Whether somebody's very first join gets its own broadcast, separate from the "
                + "ordinary join line.")
        boolean welcomeFirstJoin,

        @In("essentials/social") @Title("Nicknames")
        @Describe("Whether /nick exists at all. Off removes the command; a nickname already set "
                + "keeps working until it is switched back on.")
        boolean nicknamesEnabled,

        @In("essentials/social") @Title("Longest a nickname may be") @Range(min = 2, max = 32)
        @Describe("Characters, after colour and formatting are stripped away — what actually "
                + "appears in chat and the player list.")
        int nicknameMaxLength,

        @In("essentials/social") @Title("Nicknames in the player list and above heads")
        @Describe("On: a /nick name is shown in the tablist and on the nametag too, not only in chat. "
                + "The nametag needs styled nametags switched on (cosmetics).")
        @Key("nickname-shown-everywhere")
        boolean nicknameShownEverywhere,

        @In("essentials/social") @Title("A Say Hi! button on join lines")
        @Describe("Everybody else gets a button under somebody's join line; clicking it says a greeting "
                + "from the list below, then their name, in chat. Once per player per join.")
        @Key("welcome-say-hi")
        boolean sayHiButton,

        @In("essentials/social") @Title("Greetings the button picks from")
        @Describe("{name} is where the newcomer's name goes; without it, the name goes last.")
        @Key("welcome-greetings")
        List<String> hiGreetings,

        @In("essentials/social") @Title("A Congrats! button on advancements")
        @Describe("Everybody else gets a button under an advancement line; clicking it says a phrase from "
                + "the list below, then their name, in chat. Once per player per advancement.")
        @Key("advancement-congrats")
        boolean congratsButton,

        @In("essentials/social") @Title("What the Congrats! button says")
        @Describe("{name} is where their name goes; without it, the name goes last.")
        @Key("advancement-congrats-phrases")
        List<String> congratsPhrases,

        @In("essentials/fun") @Title("/roast")
        @Describe("Says a random roast of somebody, or of yourself, in chat as whoever asked.")
        boolean roastEnabled,

        @In("essentials/fun") @Title("/joke")
        @Describe("Says a random, deliberately terrible joke in chat as whoever asked.")
        boolean jokeEnabled,

        @In("essentials/fun") @Title("Seconds between two") @Range(min = 0, max = 600)
        @Describe("How long somebody waits after a roast or a joke before the next. 0 means no wait.")
        int funCooldownSeconds

) {

    public static final EssentialsSettings DEFAULTS =
            new EssentialsSettings(3, true, 300, true, true, true, true, 16, true, true,
                    List.of("Hi {name}!", "Look who finally showed up — {name}!", "{name} has entered the chat",
                            "Welcome back {name}, we missed you (a bit)", "Oh no, it's {name}",
                            "Hey {name}, wipe your boots", "Yo {name}", "{name}! The legend returns",
                            "Hide your diamonds, {name} is here", "Howdy {name}"),
                    true, List.of("GG {name}", "Took you long enough, {name}", "{name} is carrying the server",
                            "Look at {name} go", "Absolute legend, {name}", "{name}'s mom would be proud",
                            "Someone call the news, {name} did it", "Huge W for {name}",
                            "Not bad for a beginner, {name}", "And they said {name} couldn't do it"),
                    true, true, 20);

    public EssentialsSettings {
        hiGreetings = hiGreetings == null ? List.of() : List.copyOf(hiGreetings);
        congratsPhrases = congratsPhrases == null ? List.of() : List.copyOf(congratsPhrases);
    }

    /** Clamped, so a hand-built settings record cannot make {@code /spawn} instant against its wish. */
    public int spawnWarmup() {
        return Math.max(0, Math.min(60, spawnWarmupSeconds));
    }

    /** Clamped, so a nought or negative timeout cannot mark somebody AFK the instant they join. */
    public int afkTimeout() {
        return Math.max(15, Math.min(7200, afkTimeoutSeconds));
    }

    /** Clamped, so a hand-built record cannot ask for a negative wait. */
    public java.time.Duration funCooldown() {
        return java.time.Duration.ofSeconds(Math.max(0, Math.min(600, funCooldownSeconds)));
    }

    /** Clamped, so a nickname cannot be asked to be longer than the setting that bounds it. */
    public int nicknameLimit() {
        return Math.max(2, Math.min(32, nicknameMaxLength));
    }

    public EssentialsSettings withAfkEnabled(boolean value) {
        return new EssentialsSettings(spawnWarmupSeconds, value, afkTimeoutSeconds, afkBroadcast, joinQuitEnabled, welcomeFirstJoin, nicknamesEnabled, nicknameMaxLength, nicknameShownEverywhere, sayHiButton, hiGreetings, congratsButton, congratsPhrases, roastEnabled, jokeEnabled, funCooldownSeconds);
    }

    public EssentialsSettings withNicknameMaxLength(int value) {
        return new EssentialsSettings(spawnWarmupSeconds, afkEnabled, afkTimeoutSeconds, afkBroadcast, joinQuitEnabled, welcomeFirstJoin, nicknamesEnabled, value, nicknameShownEverywhere, sayHiButton, hiGreetings, congratsButton, congratsPhrases, roastEnabled, jokeEnabled, funCooldownSeconds);
    }

    public EssentialsSettings withNicknameShownEverywhere(boolean value) {
        return new EssentialsSettings(spawnWarmupSeconds, afkEnabled, afkTimeoutSeconds, afkBroadcast, joinQuitEnabled, welcomeFirstJoin, nicknamesEnabled, nicknameMaxLength, value, sayHiButton, hiGreetings, congratsButton, congratsPhrases, roastEnabled, jokeEnabled, funCooldownSeconds);
    }

    public EssentialsSettings withSayHiButton(boolean value) {
        return new EssentialsSettings(spawnWarmupSeconds, afkEnabled, afkTimeoutSeconds, afkBroadcast, joinQuitEnabled, welcomeFirstJoin, nicknamesEnabled, nicknameMaxLength, nicknameShownEverywhere, value, hiGreetings, congratsButton, congratsPhrases, roastEnabled, jokeEnabled, funCooldownSeconds);
    }

    public EssentialsSettings withCongratsButton(boolean value) {
        return new EssentialsSettings(spawnWarmupSeconds, afkEnabled, afkTimeoutSeconds, afkBroadcast, joinQuitEnabled, welcomeFirstJoin, nicknamesEnabled, nicknameMaxLength, nicknameShownEverywhere, sayHiButton, hiGreetings, value, congratsPhrases, roastEnabled, jokeEnabled, funCooldownSeconds);
    }
}
