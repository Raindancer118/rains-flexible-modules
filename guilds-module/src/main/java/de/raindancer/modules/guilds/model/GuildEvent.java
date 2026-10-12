package de.raindancer.modules.guilds.model;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * One line of a guild's log. Bank movements are lines too, so the statement and the log can never disagree.
 *
 * @param amount minor units for a bank line, otherwise 0
 * @param detail free text: a new name, a rank's name, the other guild's name
 */
public record GuildEvent(String guild, long at, Kind kind, Optional<UUID> actor, Optional<UUID> target,
                         long amount, String detail) {

    public enum Kind {
        FOUNDED(Material.BEACON),
        JOINED(Material.LIME_DYE),
        LEFT(Material.GRAY_DYE),
        KICKED(Material.IRON_BOOTS),
        INVITED(Material.PAPER),
        APPLIED(Material.WRITABLE_BOOK),
        RANK_CHANGED(Material.GOLDEN_HELMET),
        LEADER_CHANGED(Material.GOLDEN_HELMET),
        SUCCEEDED(Material.CLOCK),
        RENAMED(Material.NAME_TAG),
        LOOK_CHANGED(Material.WHITE_BANNER),
        RANKS_CHANGED(Material.LADDER),
        SETTINGS_CHANGED(Material.COMPARATOR),
        HOME_SET(Material.RED_BED),
        DEPOSIT(Material.GOLD_NUGGET),
        WITHDRAWAL(Material.GOLD_INGOT),
        LEVEL_UP(Material.EXPERIENCE_BOTTLE),
        ALLIED(Material.LIME_BANNER),
        ALLIANCE_ENDED(Material.GRAY_BANNER),
        RIVALRY(Material.RED_BANNER),
        RIVALRY_ENDED(Material.WHITE_BANNER),
        STAFF(Material.COMMAND_BLOCK),
        CONTRACT_DONE(Material.FILLED_MAP),
        BOOST(Material.BLAZE_POWDER),
        PERK(Material.NETHER_STAR),
        PRESTIGE(Material.BEACON),
        CROWN(Material.GOLDEN_HELMET);

        private final Material icon;

        Kind(Material icon) {
            this.icon = icon;
        }

        public Material icon() {
            return icon;
        }

        public boolean isBank() {
            return this == DEPOSIT || this == WITHDRAWAL;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    public GuildEvent {
        actor = actor == null ? Optional.empty() : actor;
        target = target == null ? Optional.empty() : target;
        detail = detail == null ? "" : detail;
    }

    public static GuildEvent of(String guild, long at, Kind kind, UUID actor, UUID target, long amount, String detail) {
        return new GuildEvent(guild, at, kind, Optional.ofNullable(actor), Optional.ofNullable(target), amount, detail);
    }
}
