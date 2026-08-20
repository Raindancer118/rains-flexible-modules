package de.raindancer.modules.xaeromap.rules;

import de.raindancer.modules.xaeromap.model.ClaimFacts;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.UUID;

/**
 * What colour a claim is drawn in, from one player's point of view.
 *
 * <h2>Why a stranger's colour is derived rather than configured</h2>
 * Your own claims and the ones you are trusted on are worth telling apart at a glance, so those two are
 * the server's to set. Everybody else's cannot be one colour: on a map where every stranger's claim is
 * the same shade, four neighbours read as one enormous claim, and the thing a player actually wants
 * from the map — where does mine end and theirs begin — is exactly what is lost. So a stranger's claim
 * takes a hue from their uuid: stable across restarts, the same on every client, and different for
 * neighbours without anybody having to pick colours for two hundred players.
 *
 * <p>Saturation and value are fixed rather than derived too. A hue is enough to tell claims apart, and
 * letting the other two vary produces the dark browns and near-whites that are unreadable against
 * terrain — the map is the thing being read, not a palette.
 */
public final class ClaimColourRule implements IXaeroMapRule {

    private static final float SATURATION = 0.65f;
    private static final float BRIGHTNESS = 0.95f;

    private final int own;
    private final int shared;

    public ClaimColourRule(NamedTextColor own, NamedTextColor shared) {
        this.own = own == null ? NamedTextColor.GREEN.value() : own.value();
        this.shared = shared == null ? NamedTextColor.AQUA.value() : shared.value();
    }

    /** Packed {@code 0xRRGGBB} for this claim, seen by this player. */
    public int colourFor(UUID viewer, ClaimFacts claim) {
        if (viewer != null && viewer.equals(claim.owner())) {
            return own;
        }
        if (claim.belongsTo(viewer)) {
            return shared;
        }
        return hueOf(claim.owner());
    }

    /** The stable hue a player's claims are drawn in when they are not the viewer's. */
    public static int hueOf(UUID owner) {
        if (owner == null) {
            return NamedTextColor.WHITE.value();
        }
        // Knuth's multiplicative mix before the modulo: uuids of two players who joined seconds apart
        // differ in very few bits, and taking those bits straight modulo 360 puts them on neighbouring
        // hues — which is the one thing this is for avoiding.
        long mixed = (owner.getMostSignificantBits() ^ owner.getLeastSignificantBits()) * 2654435761L;
        int degrees = (int) Math.floorMod(mixed >>> 16, 360L);
        return rgb(degrees / 360f, SATURATION, BRIGHTNESS);
    }

    /**
     * Hue/saturation/brightness to packed {@code 0xRRGGBB}.
     *
     * <p>Written out rather than calling {@code java.awt.Color}: that is the whole {@code java.desktop}
     * module dragged into a headless server for six lines of arithmetic.
     */
    static int rgb(float hue, float saturation, float brightness) {
        float sector = (hue - (float) Math.floor(hue)) * 6f;
        float offset = sector - (float) Math.floor(sector);
        float dim = brightness * (1f - saturation);
        float fading = brightness * (1f - saturation * offset);
        float rising = brightness * (1f - saturation * (1f - offset));
        return switch ((int) sector) {
            case 0 -> packed(brightness, rising, dim);
            case 1 -> packed(fading, brightness, dim);
            case 2 -> packed(dim, brightness, rising);
            case 3 -> packed(dim, fading, brightness);
            case 4 -> packed(rising, dim, brightness);
            default -> packed(brightness, dim, fading);
        };
    }

    private static int packed(float red, float green, float blue) {
        return channel(red) << 16 | channel(green) << 8 | channel(blue);
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255f)));
    }

    @Override
    public String describe() {
        return "the colour a claim is drawn in: yours, one shared with you, or a hue from its owner";
    }
}
