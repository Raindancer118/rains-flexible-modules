package de.raindancer.modules.hungergames.service;

import de.raindancer.core.ui.effect.Effect;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.effect.ParticleSequence;
import de.raindancer.core.ui.effect.SoundSequence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every noise and every puff of particles a Hunger Games round makes, and what each one is by default.
 *
 * <h2>The bug this class exists to fix, which was silence</h2>
 * The module was calling {@code effects.play(uuid, "hungergames:countdown")} and three other cue names, and
 * <em>none of them was defined anywhere</em>. Core answers an unknown cue by logging one warning and playing
 * nothing, which is the right thing for Core to do and meant this module ran a whole tournament in silence:
 * no countdown tick, no bell at the start, no launch, no lamps. The failure is invisible in a test and
 * inaudible in a way that reads as a broken server.
 *
 * <p>Worse, the sound design was not missing — it was tuned. A live server's {@code config.yml} carried a
 * sixteen-sound cannon, a nine-sound elimination, a six-sound pair of boots. All of it would have been
 * discarded on the first start.
 *
 * <h2>Why the defaults are here rather than in {@code config.yml}</h2>
 * Because a cue is a <em>binding</em>, not a setting. Core owns one {@link Effects} registry for the whole
 * server precisely so that a server owner who rebinds what a countdown sounds like rebinds it once, for
 * every plugin, from one screen — rather than once per plugin in one file per plugin. This class is what
 * puts this module's forty-odd cues into that registry with sensible values; changing them afterwards is
 * Core's job and Core's screen.
 *
 * <p>{@code defineIfAbsent} is therefore the wrong verb and is deliberately not used: these are defined
 * outright at start-up, and anything a server owner has rebound is reapplied over the top from their own
 * store afterwards. A cue that quietly kept last release's binding is how a rebinding survives a version
 * that meant to change it.
 *
 * <h2>The notation</h2>
 * Both halves use Core's own — {@link SoundSequence} for sounds, {@link ParticleSequence} for particles —
 * so that what is written here is exactly what a server owner may type, and what
 * {@code LegacyConfigImport} can read out of the old plugin's file without a second parser.
 */
public final class HungerGamesCues {

    /** The prefix every cue this module owns carries, so nothing collides with Core's or another module's. */
    public static final String PREFIX = "hungergames:";

    // ==================== the round ====================

    public static final String COUNTDOWN = PREFIX + "countdown";
    public static final String GAME_START = PREFIX + "game-start";
    public static final String GRACE_END = PREFIX + "grace-end";
    public static final String KILL = PREFIX + "kill";
    public static final String ELIMINATION = PREFIX + "elimination";
    public static final String VICTORY = PREFIX + "victory";

    /**
     * The cannon.
     *
     * <p>Its own cue rather than a second name for {@link #ELIMINATION}, because they are two different
     * events heard by two different people: the elimination is heard by whoever died, and the cannon is
     * heard by everybody else, from wherever they happen to be. On a live server this was the most heavily
     * layered cue in the file.
     */
    public static final String CANNON = PREFIX + "cannon";

    // ==================== the run-up ====================

    public static final String STARTUP_LAUNCH = PREFIX + "startup-launch";
    public static final String STARTUP_ARRIVE = PREFIX + "startup-arrive";
    public static final String STARTUP_LAMP = PREFIX + "startup-lamp";

    // ==================== the Capitol ====================

    public static final String SUPPLY_DROP_WARNING = PREFIX + "supply-drop-warning";
    public static final String SUPPLY_DROP_LANDED = PREFIX + "supply-drop-landed";
    public static final String SPONSOR_TOKEN_EARNED = PREFIX + "sponsor-token-earned";
    public static final String SPONSOR_BEACON_SPAWN = PREFIX + "sponsor-beacon-spawn";
    public static final String SPONSOR_PURCHASE = PREFIX + "sponsor-purchase-success";
    public static final String SPONSOR_REFUSED = PREFIX + "sponsor-purchase-failed";

    // ==================== the deathmatch ====================

    public static final String DEATHMATCH_WARNING = PREFIX + "deathmatch-warning";
    public static final String DEATHMATCH_START = PREFIX + "deathmatch-start";

    // ==================== the items ====================

    public static final String ITEM_FIENDFINDER = PREFIX + "item-fiendfinder";
    public static final String ITEM_SMOKE_BOMB = PREFIX + "item-smoke-bomb";
    public static final String ITEM_MEDIKIT = PREFIX + "item-medikit";
    public static final String ITEM_LIGHTNING = PREFIX + "item-lightning";
    public static final String ITEM_HERMES_BOOTS = PREFIX + "item-hermes-boots";
    public static final String ITEM_HERMES_WARNING = PREFIX + "item-hermes-warning";
    public static final String ITEM_KRUECKAU_THROW = PREFIX + "item-krueckau-throw";
    public static final String ITEM_KRUECKAU_IMPACT = PREFIX + "item-krueckau-impact";
    public static final String ITEM_AURA = PREFIX + "item-aura";
    public static final String ITEM_GRAPPLING = PREFIX + "item-grappling";
    public static final String ITEM_REPULSE = PREFIX + "item-repulse";
    public static final String ITEM_FEAST = PREFIX + "item-feast";
    public static final String ITEM_WAR_KIT = PREFIX + "item-war-kit";
    public static final String ITEM_LEAP = PREFIX + "item-leap";
    public static final String ITEM_STUPIDNESS_PROTECTOR = PREFIX + "item-stupidness-protector";
    public static final String ITEM_EXMATRIKULATOR = PREFIX + "item-exmatrikulator";

    private HungerGamesCues() {
    }

    /**
     * Every cue this module plays, and what it is by default.
     *
     * <p>A map rather than a list of calls, so that {@link #names()} can answer what exists without a
     * registry — which is what lets a test assert that every cue name the module <em>plays</em> is one this
     * class <em>defines</em>. That test is the one that would have caught the silence.
     *
     * <h2>Where these values come from</h2>
     * Not the old plugin's defaults. These are the bindings a real tournament arrived at over a season of
     * evenings, taken from the live server's own {@code config.yml} — the sixteen-sound cannon, the
     * nine-sound elimination, the six-layer boots. Shipping the old defaults instead would mean every server
     * starting from a sound design somebody had already decided was not good enough, and the tuned version
     * existing only in one file on one machine.
     *
     * <p>The one thing deliberately not taken from there is anything about <em>timing the round</em>. A cue
     * is a noise; how long an evening lasts is not, and is nobody else's decision to inherit.
     */
    public static Map<String, Effect> defaults() {
        Map<String, Effect> cues = new LinkedHashMap<>();

        // ---- the round
        sound(cues, COUNTDOWN,
                "BLOCK_NOTE_BLOCK_PLING; ENTITY_EXPERIENCE_ORB_PICKUP; ENTITY_WARDEN_HEARTBEAT");
        both(cues, GAME_START,
                "ENTITY_ENDER_DRAGON_GROWL; BLOCK_END_PORTAL_SPAWN",
                "FIREWORK@60~0.8; FLAME@30~0.5");
        sound(cues, GRACE_END, "ENTITY_ENDER_DRAGON_GROWL~1.4");
        both(cues, KILL, "ENTITY_LIGHTNING_BOLT_THUNDER", "DUST@40~0.5#ff2020; CRIT@30~0.5");
        both(cues, ELIMINATION,
                "ENTITY_WITHER_HURT; ENTITY_FIREWORK_ROCKET_LARGE_BLAST; "
                + "ENTITY_GENERIC_EXPLODE; ENTITY_ZOMBIE_VILLAGER_CURE; "
                + "ENTITY_LIGHTNING_BOLT_THUNDER; ENTITY_LIGHTNING_BOLT_IMPACT^2; "
                + "ENTITY_BLAZE_SHOOT; ENTITY_WARDEN_SONIC_BOOM; ITEM_TOTEM_USE",
                "LARGE_SMOKE@40~0.5; SOUL@25~0.4");
        both(cues, VICTORY,
                "UI_TOAST_CHALLENGE_COMPLETE; BLOCK_BEACON_POWER_SELECT; "
                + "ENTITY_PLAYER_LEVELUP; ENTITY_WITHER_DEATH",
                "TOTEM_OF_UNDYING@80~1.0; FIREWORK@40~0.8");
        // Fifteen sounds and seven particle layers, and every one of them earns its place: the totem chime
        // and the near explosion land together, a quieter second explosion follows two tenths later, and the
        // thunder rolls in at 1.25 seconds so the cannon reads as distant rather than as one flat bang. This
        // is why SoundSequence exists at all.
        both(cues, CANNON,
                "ITEM_TOTEM_USE~0.9; ENTITY_GENERIC_EXPLODE~0.5; "
                + "ENTITY_GENERIC_EXPLODE@0.4~0.4>200; ENTITY_GHAST_SHOOT; "
                + "ENTITY_ENDER_DRAGON_FLAP; ENTITY_LIGHTNING_BOLT_THUNDER>1250; "
                + "ENTITY_LIGHTNING_BOLT_IMPACT; BLOCK_GLASS_BREAK^1; BLOCK_GLASS_BREAK; "
                + "ENTITY_GENERIC_BIG_FALL; ENTITY_ITEM_BREAK; BLOCK_STEM_BREAK; "
                + "ENTITY_DRAGON_FIREBALL_EXPLODE~0.5; ENTITY_BLAZE_SHOOT~0.5; "
                + "ENTITY_LIGHTNING_BOLT_THUNDER",
                "SONIC_BOOM; SCULK_SOUL; REVERSE_PORTAL; DUST_PLUME; ASH; SOUL; "
                + "DAMAGE_INDICATOR");

        // ---- the run-up
        both(cues, STARTUP_LAUNCH,
                "ENTITY_ILLUSIONER_CAST_SPELL",
                "FLAME@25~0.3; CLOUD@15~0.3");
        both(cues, STARTUP_ARRIVE,
                "BLOCK_STONE_PLACE",
                "HAPPY_VILLAGER@1~0.1; CHERRY_LEAVES");
        sound(cues, STARTUP_LAMP, "BLOCK_NOTE_BLOCK_BASS~0.5");

        // ---- the Capitol
        sound(cues, SUPPLY_DROP_WARNING,
                "custom.halt; BLOCK_NOTE_BLOCK_BASS");
        both(cues, SUPPLY_DROP_LANDED, "ENTITY_FIREWORK_ROCKET_LARGE_BLAST",
                "END_ROD@50~0.3; HAPPY_VILLAGER@20~0.5");
        sound(cues, SPONSOR_TOKEN_EARNED,
                "ENTITY_EXPERIENCE_ORB_PICKUP; BLOCK_NOTE_BLOCK_HARP");
        both(cues, SPONSOR_BEACON_SPAWN, "BLOCK_BEACON_ACTIVATE", "GLOW@40~0.4; END_ROD@30~0.3");
        sound(cues, SPONSOR_PURCHASE,
                "ENTITY_PLAYER_LEVELUP; BLOCK_BELL_USE; ENTITY_EXPERIENCE_ORB_PICKUP; "
                + "ENTITY_FIREWORK_ROCKET_LARGE_BLAST");
        sound(cues, SPONSOR_REFUSED, "BLOCK_NOTE_BLOCK_BASS");

        // ---- the deathmatch
        sound(cues, DEATHMATCH_WARNING, "custom.halt");
        both(cues, DEATHMATCH_START, "ENTITY_WITHER_SPAWN",
                "SOUL_FIRE_FLAME@60~0.8; DUST@40~0.6#aa00ff");

        // ---- the items
        sound(cues, ITEM_FIENDFINDER, "BLOCK_BEACON_ACTIVATE~2.0; ENTITY_ENDER_EYE_DEATH~0.5");
        both(cues, ITEM_SMOKE_BOMB, "ENTITY_TNT_PRIMED~1.4; BLOCK_FIRE_EXTINGUISH~0.8",
                "LARGE_SMOKE@140~2.2; CAMPFIRE_COSY_SMOKE@60~2.0");
        both(cues, ITEM_MEDIKIT, "ITEM_TOTEM_USE~1.4; ENTITY_PLAYER_LEVELUP~1.6", "HEART@20~0.5");
        both(cues, ITEM_LIGHTNING,
                "ENTITY_LIGHTNING_BOLT_THUNDER",
                "ELECTRIC_SPARK@40~0.6; FLASH@2; FLAME; LAVA; TOTEM_OF_UNDYING; FIREWORK");
        both(cues, ITEM_HERMES_BOOTS,
                "ENTITY_PHANTOM_FLAP@2~1.6^5; ENTITY_ENDER_DRAGON_FLAP^2; "
                + "BLOCK_CONDUIT_ACTIVATE@0.75~0.5^1; ENTITY_ITEM_BREAK>4000; "
                + "BLOCK_BEACON_ACTIVATE~0.6; BLOCK_BELL_RESONATE",
                "SHRIEK; SNOWFLAKE; POOF; CLOUD; GUST");
        sound(cues, ITEM_HERMES_WARNING, "BLOCK_NOTE_BLOCK_PLING~1.6");
        sound(cues, ITEM_KRUECKAU_THROW,
                "ENTITY_SPLASH_POTION_THROW; ENTITY_ILLUSIONER_CAST_SPELL; "
                + "ENTITY_SPLASH_POTION_BREAK; ENTITY_SQUID_DEATH");
        both(cues, ITEM_KRUECKAU_IMPACT,
                "ENTITY_SPLASH_POTION_BREAK; ENTITY_ILLUSIONER_CAST_SPELL; "
                + "ENTITY_EVOKER_CAST_SPELL>500; ENTITY_ZOMBIE_VILLAGER_CURE; "
                + "ENTITY_ELDER_GUARDIAN_CURSE@0.25; ENTITY_ITEM_BREAK",
                "LARGE_SMOKE@60~1.5; ITEM_SLIME@40~1.5");
        both(cues, ITEM_AURA, "BLOCK_BEACON_POWER_SELECT; BLOCK_CONDUIT_ACTIVATE",
                "ENCHANT@10~1.0; END_ROD@4~0.6");
        sound(cues, ITEM_GRAPPLING, "ENTITY_FISHING_BOBBER_THROW; ENTITY_ENDER_DRAGON_FLAP");
        both(cues, ITEM_REPULSE, "ENTITY_WIND_CHARGE_WIND_BURST; ITEM_MACE_SMASH_AIR",
                "GUST@1; SWEEP_ATTACK@8~1.0");
        both(cues, ITEM_FEAST, "ENTITY_PLAYER_BURP; ENTITY_VILLAGER_CELEBRATE",
                "HAPPY_VILLAGER@25~0.6");
        sound(cues, ITEM_WAR_KIT, "ITEM_ARMOR_EQUIP_IRON~0.8; BLOCK_ANVIL_USE~1.4");
        both(cues, ITEM_LEAP, "ENTITY_SLIME_JUMP; ENTITY_FIREWORK_ROCKET_LAUNCH",
                "CLOUD@20~0.3; FIREWORK@15~0.3");
        both(cues, ITEM_STUPIDNESS_PROTECTOR, "ITEM_TOTEM_USE", "TOTEM_OF_UNDYING@60~1.0");
        both(cues, ITEM_EXMATRIKULATOR, "ENTITY_LIGHTNING_BOLT_THUNDER~0.8; BLOCK_BEACON_POWER_SELECT",
                "ELECTRIC_SPARK@30~1.2; FLASH@1");

        return Map.copyOf(cues);
    }

    /** Every cue name this module owns. */
    public static List<String> names() {
        return List.copyOf(defaults().keySet());
    }

    /**
     * Puts them all into Core's registry.
     *
     * <p>Called once, at start-up, before anything can play one. Returns how many were defined so the module
     * can say so out loud — a count of zero in the log is the symptom of the silence this class exists to
     * prevent, and it is worth being able to see rather than having to notice.
     */
    public static int defineAllIn(Effects effects) {
        Map<String, Effect> cues = defaults();
        cues.forEach(effects::define);
        return cues.size();
    }

    /**
     * Rebinds one cue from what a server owner has written.
     *
     * <p>Used by the legacy config import and by anything else reading a tuned value out of a file. An
     * unreadable line leaves the default in place rather than silencing the cue, because a typo in a sound
     * name should cost the tuning and not the sound.
     *
     * @return whether it was rebound
     */
    public static boolean rebind(Effects effects, String cue, String writtenSounds,
                                String writtenParticles) {
        SoundSequence sounds = SoundSequence.parseAndExpand(writtenSounds);
        ParticleSequence bursts = ParticleSequence.parse(writtenParticles);
        if (sounds.isSilent() && bursts.isNothing()
                && (writtenSounds == null || writtenSounds.isBlank())
                && (writtenParticles == null || writtenParticles.isBlank())) {
            return false;
        }
        // Whichever half was not written keeps whatever is already bound, so somebody who tuned only the
        // sound of an explosion does not lose its particles as a side effect.
        Effect existing = effects.boundTo(cue).orElse(Effect.silence());
        effects.define(cue, new Effect(
                writtenSounds == null || writtenSounds.isBlank() ? existing.sounds() : sounds,
                writtenParticles == null || writtenParticles.isBlank() ? existing.bursts() : bursts));
        return true;
    }

    private static void sound(Map<String, Effect> cues, String name, String written) {
        cues.put(name, Effect.of(SoundSequence.parseAndExpand(written)));
    }

    private static void both(Map<String, Effect> cues, String name, String sounds, String particles) {
        cues.put(name, Effect.of(SoundSequence.parseAndExpand(sounds),
                ParticleSequence.parse(particles)));
    }
}
