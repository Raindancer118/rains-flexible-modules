package de.raindancer.modules.claims.model;

import org.bukkit.Material;
import org.bukkit.WeatherType;

import java.util.Locale;
import java.util.Optional;

/**
 * The weather and time of day a claim shows to the people inside it.
 * <p>
 * Purely client side, per player: Minecraft has no notion of localised weather, so this uses
 * {@code Player#setPlayerWeather} and {@code setPlayerTime}. Everybody outside the claim — and the world
 * itself — is unaffected, which also means crops, sleeping and mob spawning follow the real world, not
 * the illusion. That is a feature rather than a limitation: an eternal-noon claim must not become a
 * mob-free zone by accident.
 */
public final class ClaimAtmosphere {

    /** What a claim does about the weather. */
    public enum WeatherMode {
        INHERIT("Follow the world", "Visitors see the real weather", Material.CLOCK),
        CLEAR("Always clear", "Visitors never see rain here", Material.SUNFLOWER),
        RAIN("Always raining", "Visitors always see rain", Material.WATER_BUCKET),
        // Minecraft has no per-player thunder: the rumble and the sky darkening are world state. This is
        // rain plus harmless visual lightning inside the claim, which is as close as the API allows.
        THUNDER("Storm", "Rain plus lightning flashes over the claim", Material.LIGHTNING_ROD);

        private final String displayName;
        private final String description;
        private final Material icon;

        WeatherMode(String displayName, String description, Material icon) {
            this.displayName = displayName;
            this.description = description;
            this.icon = icon;
        }

        public String displayName() {
            return displayName;
        }

        public String description() {
            return description;
        }

        public Material icon() {
            return icon;
        }

        public WeatherMode next() {
            return values()[(ordinal() + 1) % values().length];
        }

        /** The client weather to send, or empty for {@link #INHERIT}. */
        public Optional<WeatherType> toWeatherType() {
            return switch (this) {
                case INHERIT -> Optional.empty();
                case CLEAR -> Optional.of(WeatherType.CLEAR);
                case RAIN, THUNDER -> Optional.of(WeatherType.DOWNFALL);
            };
        }

        /**
         * How hard it should visibly rain, 0 to 1.
         * <p>
         * Separate from the weather type because the two are separate packets: the type says whether it
         * is raining at all, this says whether any drops are actually drawn.
         */
        public float rainLevel() {
            return switch (this) {
                case RAIN, THUNDER -> 1f;
                default -> 0f;
            };
        }

        /** How dark and stormy the sky gets, 0 to 1. */
        public float thunderLevel() {
            return this == THUNDER ? 1f : 0f;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Optional<WeatherMode> byKey(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            String normalised = raw.trim().toUpperCase(Locale.ROOT);
            for (WeatherMode mode : values()) {
                if (mode.name().equals(normalised)) {
                    return Optional.of(mode);
                }
            }
            return Optional.empty();
        }
    }

    /** Common times of day, so owners do not have to know tick numbers. */
    public enum TimePreset {
        INHERIT("Follow the world", -1, Material.CLOCK),
        DAWN("Dawn", 23000, Material.ORANGE_DYE),
        MORNING("Morning", 1000, Material.YELLOW_DYE),
        NOON("Noon", 6000, Material.SUNFLOWER),
        AFTERNOON("Afternoon", 9000, Material.GOLD_INGOT),
        SUNSET("Sunset", 12000, Material.ORANGE_TULIP),
        NIGHT("Night", 15000, Material.BLACK_DYE),
        MIDNIGHT("Midnight", 18000, Material.ENDER_EYE);

        private final String displayName;
        private final int ticks;
        private final Material icon;

        TimePreset(String displayName, int ticks, Material icon) {
            this.displayName = displayName;
            this.ticks = ticks;
            this.icon = icon;
        }

        public String displayName() {
            return displayName;
        }

        public int ticks() {
            return ticks;
        }

        public Material icon() {
            return icon;
        }

        public boolean inherits() {
            return this == INHERIT;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Optional<TimePreset> byKey(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            String normalised = raw.trim().toUpperCase(Locale.ROOT);
            for (TimePreset preset : values()) {
                if (preset.name().equals(normalised)) {
                    return Optional.of(preset);
                }
            }
            return Optional.empty();
        }

        /** The preset closest to a raw tick value, used to label a custom time. */
        public static TimePreset closest(int ticks) {
            TimePreset best = NOON;
            int bestDistance = Integer.MAX_VALUE;
            for (TimePreset preset : values()) {
                if (preset.inherits()) {
                    continue;
                }
                int distance = Math.abs(preset.ticks() - ticks);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = preset;
                }
            }
            return best;
        }
    }

    /** Ticks in a Minecraft day. */
    public static final int DAY = 24000;

    private WeatherMode weather = WeatherMode.INHERIT;
    private TimePreset timePreset = TimePreset.INHERIT;
    /** Exact tick value when the owner picked one; {@code -1} means use the preset. */
    private int customTicks = -1;

    public WeatherMode weather() {
        return weather;
    }

    public void weather(WeatherMode weather) {
        this.weather = weather == null ? WeatherMode.INHERIT : weather;
    }

    public TimePreset timePreset() {
        return timePreset;
    }

    public void timePreset(TimePreset preset) {
        this.timePreset = preset == null ? TimePreset.INHERIT : preset;
        this.customTicks = -1;
    }

    public int customTicks() {
        return customTicks;
    }

    /** Sets an exact time of day in ticks, overriding the preset. */
    public void customTicks(int ticks) {
        if (ticks < 0) {
            this.customTicks = -1;
            return;
        }
        this.customTicks = ticks % 24000;
        this.timePreset = TimePreset.closest(this.customTicks);
    }

    /**
     * Moves the claim's time by {@code deltaTicks}, either way and round midnight. A claim following the world
     * starts from the world's time ({@code worldTicks}), so the first nudge does not jump somewhere else.
     */
    public void shiftTime(int deltaTicks, long worldTicks) {
        int from = effectiveTicks() >= 0 ? effectiveTicks() : (int) Math.floorMod(worldTicks, (long) DAY);
        customTicks(Math.floorMod(from + deltaTicks, DAY));
    }

    /** Ticks as a clock, "14:30" — tick 0 is six in the morning. */
    public static String clock(int ticks) {
        int minutes = (int) ((Math.floorMod(ticks, DAY) * 1440L) / DAY);
        int hour = (minutes / 60 + 6) % 24;
        return String.format(Locale.ROOT, "%02d:%02d", hour, minutes % 60);
    }

    /** What somebody typed — "14:30" or a tick count from 0 to 23999 — as ticks. */
    public static Optional<Integer> parseTime(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String text = typed.strip();
        try {
            int colon = text.indexOf(':');
            if (colon < 0) {
                int ticks = Integer.parseInt(text);
                return ticks >= 0 && ticks < DAY ? Optional.of(ticks) : Optional.empty();
            }
            int hour = Integer.parseInt(text.substring(0, colon));
            int minute = Integer.parseInt(text.substring(colon + 1));
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                return Optional.empty();
            }
            int sinceSix = Math.floorMod(hour - 6, 24) * 60 + minute;
            // Rounded to the nearest tick, so a typed time reads back as the same clock.
            return Optional.of((int) Math.round(sinceSix * (double) DAY / 1440) % DAY);
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
    }

    public boolean overridesWeather() {
        return weather != WeatherMode.INHERIT;
    }

    public boolean overridesTime() {
        return customTicks >= 0 || !timePreset.inherits();
    }

    public boolean isActive() {
        return overridesWeather() || overridesTime();
    }

    /** The time of day to show, or {@code -1} when the world's time applies. */
    public int effectiveTicks() {
        if (customTicks >= 0) {
            return customTicks;
        }
        return timePreset.inherits() ? -1 : timePreset.ticks();
    }

    public String describeTime() {
        if (!overridesTime()) {
            return "follows the world";
        }
        return customTicks >= 0
                ? timePreset.displayName() + " (" + customTicks + " ticks)"
                : timePreset.displayName();
    }

    public void reset() {
        weather = WeatherMode.INHERIT;
        timePreset = TimePreset.INHERIT;
        customTicks = -1;
    }
}
