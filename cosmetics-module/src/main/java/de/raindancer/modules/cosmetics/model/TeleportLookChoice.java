package de.raindancer.modules.cosmetics.model;

import de.raindancer.core.ui.effect.SoundCue;
import de.raindancer.core.world.teleport.TravelLook;

/**
 * What a player chose for their teleports. Each part is null for "the server's", {@link #NONE} for
 * nothing at all, or a sound key / particle name.
 */
public record TeleportLookChoice(String depart, String arrive, String waitParticle, String tick) {

    /** What a player types, and what is stored, for "nothing at all". */
    public static final String NONE = "none";

    public static final TeleportLookChoice SERVERS = new TeleportLookChoice(null, null, null, null);

    /** How loud a chosen sound is: the server's own teleport cues sit around here too. */
    private static final float VOLUME = 0.7f;

    public TeleportLookChoice {
        depart = blankIsNull(depart);
        arrive = blankIsNull(arrive);
        waitParticle = blankIsNull(waitParticle);
        tick = blankIsNull(tick);
    }

    private static String blankIsNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public String of(TeleportPart part) {
        return switch (part) {
            case DEPART -> depart;
            case ARRIVE -> arrive;
            case WAIT -> waitParticle;
            case TICK -> tick;
        };
    }

    /** The same with one part changed; null puts that part back to the server's. */
    public TeleportLookChoice with(TeleportPart part, String value) {
        return switch (part) {
            case DEPART -> new TeleportLookChoice(value, arrive, waitParticle, tick);
            case ARRIVE -> new TeleportLookChoice(depart, value, waitParticle, tick);
            case WAIT -> new TeleportLookChoice(depart, arrive, value, tick);
            case TICK -> new TeleportLookChoice(depart, arrive, waitParticle, value);
        };
    }

    /**
     * The same, with every part {@code allowed} refuses put back to the server's. A part set to
     * {@link #NONE} is never asked about: going without is not a privilege.
     */
    public TeleportLookChoice keeping(java.util.function.BiPredicate<TeleportPart, String> allowed) {
        TeleportLookChoice kept = this;
        for (TeleportPart part : TeleportPart.values()) {
            String value = of(part);
            if (value != null && !NONE.equals(value) && !allowed.test(part, value)) {
                kept = kept.with(part, null);
            }
        }
        return kept;
    }

    public boolean isServers() {
        return depart == null && arrive == null && waitParticle == null && tick == null;
    }

    /** As Core's travel asks for it. */
    public TravelLook toLook() {
        return new TravelLook(sound(depart), sound(arrive),
                waitParticle == null ? null : NONE.equals(waitParticle) ? "" : waitParticle, null, sound(tick));
    }

    private static SoundCue sound(String key) {
        if (key == null) {
            return null;
        }
        return NONE.equals(key) ? TravelLook.NOTHING_HEARD : new SoundCue(key, VOLUME, 1f);
    }
}
