package de.raindancer.modules.guilds.model;

import org.bukkit.Material;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** What a rank may do inside its guild. The leader may do all of it, always. */
public enum GuildPermission {

    INVITE("Invite players", Material.WRITABLE_BOOK, "Invite players and accept applications."),
    KICK("Kick members", Material.IRON_BOOTS, "Remove members of a lower rank."),
    PROMOTE("Promote and demote", Material.GOLDEN_HELMET, "Move members of a lower rank up or down, below their own."),
    WITHDRAW("Take from the bank", Material.GOLD_INGOT, "Take money out of the guild bank, up to their daily limit — their own, else the rank's."),
    PAY("Pay from the bank", Material.EMERALD, "Pay anybody from the guild bank, any sum it holds — no daily limit."),
    SET_HOME("Set the guild home", Material.RED_BED, "Move the guild home to where they stand."),
    USE_HOME("Use the guild home", Material.ENDER_PEARL, "Travel to the guild home."),
    EDIT_INFO("Edit the guild", Material.NAME_TAG, "Change the message of the day, colour, emblem and how people join."),
    MANAGE_RANKS("Manage ranks", Material.LADDER, "Create, rename and change ranks below their own."),
    RELATIONS("Alliances and rivals", Material.WHITE_BANNER, "Ask for or end alliances, declare and end rivalries."),
    OFFICER_CHAT("Officer chat", Material.BELL, "Read and write the officers' channel."),
    VIEW_LOG("Read the log", Material.BOOK, "Read the guild's log and the bank's statement."),
    VAULT("Take from the vault", Material.ENDER_CHEST, "Take items out of the guild vault. Anybody may put things in."),
    PERKS("Spend perk points", Material.NETHER_STAR, "Buy perks with the guild's points and take them back.");

    private final String title;
    private final Material icon;
    private final String description;

    GuildPermission(String title, Material icon, String description) {
        this.title = title;
        this.icon = icon;
        this.description = description;
    }

    public String title() {
        return title;
    }

    public Material icon() {
        return icon;
    }

    public String description() {
        return description;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static Optional<GuildPermission> named(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String wanted = typed.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (GuildPermission each : values()) {
            if (each.key().equals(wanted)) {
                return Optional.of(each);
            }
        }
        return Optional.empty();
    }

    public static Set<GuildPermission> all() {
        return EnumSet.allOf(GuildPermission.class);
    }

    public static Set<GuildPermission> none() {
        return EnumSet.noneOf(GuildPermission.class);
    }
}
