package de.raindancer.modules.ctf.model;

import org.bukkit.FireworkEffect;
import org.bukkit.Material;

/** The firework a capture sets off. Unlocked by level. */
public enum CaptureShow {
    BURST("Burst", Material.FIREWORK_ROCKET, FireworkEffect.Type.BURST, 1),
    BALL("Big ball", Material.SLIME_BALL, FireworkEffect.Type.BALL_LARGE, 4),
    STAR("Stars", Material.NETHER_STAR, FireworkEffect.Type.STAR, 8),
    CREEPER("Creeper", Material.CREEPER_HEAD, FireworkEffect.Type.CREEPER, 12),
    TWINKLE("Twinkle", Material.GLOWSTONE_DUST, FireworkEffect.Type.BALL, 18);

    private final String title;
    private final Material icon;
    private final FireworkEffect.Type type;
    private final int level;

    CaptureShow(String title, Material icon, FireworkEffect.Type type, int level) {
        this.title = title;
        this.icon = icon;
        this.type = type;
        this.level = level;
    }

    public String title() {
        return title;
    }

    public Material icon() {
        return icon;
    }

    public FireworkEffect.Type type() {
        return type;
    }

    public int level() {
        return level;
    }

    /** Twinkle is a small ball with a flicker; the others are what their type says. */
    public boolean flickers() {
        return this == TWINKLE || this == STAR;
    }
}
