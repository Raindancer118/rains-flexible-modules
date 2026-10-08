package de.raindancer.modules.economy.service;

import de.raindancer.core.ui.effect.Effect;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.effect.SoundSequence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The casino's sounds, as Core cues by meaning — so an owner can rebind any of them like every other sound
 * on the server. Defined only where nothing is bound yet, so an owner's choice is never overwritten.
 */
public final class GameSounds {

    public static final String TICK = "economy:tick";
    public static final String COIN = "economy:coin-spin";
    public static final String DICE = "economy:dice";
    public static final String REEL_STOP = "economy:reel-stop";
    public static final String WHEEL = "economy:wheel";
    public static final String CHIPS = "economy:chips";
    public static final String WIN = "economy:win";
    public static final String JACKPOT = "economy:jackpot";
    public static final String LOSE = "economy:lose";
    public static final String CASH = "economy:cash";
    public static final String CARD = "economy:card";
    public static final String WIN_STEP = "economy:win-step";
    public static final String GEM = "economy:gem";
    public static final String BOOM = "economy:boom";
    public static final String SCRATCH = "economy:scratch";
    public static final String GALLOP = "economy:gallop";
    public static final String BELL = "economy:bell";
    public static final String CRASH = "economy:crash";

    /** A win at least this many times the stake is a jackpot, and sounds like one. */
    public static final long JACKPOT_TIMES = 10;

    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put(TICK, "BLOCK_NOTE_BLOCK_HAT@0.5~1.8");
        DEFAULTS.put(COIN, "ENTITY_EXPERIENCE_ORB_PICKUP@0.35~1.5");
        DEFAULTS.put(DICE, "BLOCK_BONE_BLOCK_HIT@0.7~1.4");
        DEFAULTS.put(REEL_STOP, "BLOCK_NOTE_BLOCK_BASEDRUM@0.8~1.1");
        DEFAULTS.put(WHEEL, "BLOCK_NOTE_BLOCK_HAT@0.35~1.3");
        DEFAULTS.put(CHIPS, "BLOCK_CHAIN_PLACE@0.6~1.6");
        DEFAULTS.put(WIN, "BLOCK_NOTE_BLOCK_BELL~1.0;BLOCK_NOTE_BLOCK_BELL~1.26>120;BLOCK_NOTE_BLOCK_BELL~1.5>240");
        DEFAULTS.put(JACKPOT, "UI_TOAST_CHALLENGE_COMPLETE@0.9;ENTITY_FIREWORK_ROCKET_TWINKLE@0.8>500;"
                + "ENTITY_FIREWORK_ROCKET_TWINKLE@0.8~1.2>800");
        DEFAULTS.put(LOSE, "BLOCK_NOTE_BLOCK_BASS~0.8;BLOCK_NOTE_BLOCK_BASS~0.6>180");
        DEFAULTS.put(CARD, "ITEM_BOOK_PAGE_TURN@0.8~1.3");
        DEFAULTS.put(WIN_STEP, "BLOCK_NOTE_BLOCK_PLING@0.6~1.4");
        DEFAULTS.put(GEM, "BLOCK_AMETHYST_BLOCK_CHIME@0.9~1.2");
        DEFAULTS.put(BOOM, "ENTITY_GENERIC_EXPLODE@0.6~1.2");
        DEFAULTS.put(SCRATCH, "BLOCK_SAND_BREAK@0.8~1.6");
        DEFAULTS.put(GALLOP, "ENTITY_HORSE_GALLOP@0.4~1.1");
        DEFAULTS.put(BELL, "BLOCK_BELL_USE@0.8~1.0");
        DEFAULTS.put(CRASH, "ENTITY_GENERIC_EXPLODE@0.5~0.8;BLOCK_NOTE_BLOCK_BASS~0.5>100");
        DEFAULTS.put(CASH, "ENTITY_ITEM_PICKUP@0.7~1.4;BLOCK_CHAIN_PLACE@0.4~1.8>60");
    }

    private final Effects effects;

    public GameSounds(Effects effects) {
        this.effects = effects;
        DEFAULTS.forEach((cue, written) -> {
            if (!effects.isDefined(cue)) {
                effects.define(cue, Effect.of(SoundSequence.parse(written)));
            }
        });
    }

    public void play(UUID player, String cue) {
        effects.play(player, cue);
    }

    /** Win, jackpot or lose, by what came back against what was staked. */
    public void outcome(UUID player, long stake, long payout) {
        if (payout <= 0) {
            play(player, LOSE);
        } else if (stake > 0 && payout >= stake * JACKPOT_TIMES) {
            play(player, JACKPOT);
        } else {
            play(player, WIN);
        }
    }
}
