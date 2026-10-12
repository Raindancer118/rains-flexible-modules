package de.raindancer.modules.guilds.model;

import java.util.Locale;

/**
 * What happened when somebody asked a guild to change. One vocabulary for every refusal, so a screen greying a
 * button and the command refusing the same thing say the same sentence: {@code guilds.outcome.<key>}.
 */
public enum GuildOutcome {
    SUCCESS,
    /** An alliance was asked for and waits on the other guild. */
    ASKED,
    SWITCHED_OFF,
    NO_GUILD,
    ALREADY_IN_GUILD,
    NOT_A_MEMBER,
    NO_SUCH_GUILD,
    NO_SUCH_RANK,
    NOT_ALLOWED,
    OUTRANKED,
    YOURSELF,
    NAME_TAKEN,
    TAG_TAKEN,
    NAME_TOO_SHORT,
    NAME_TOO_LONG,
    NAME_CHARACTERS,
    NAME_BANNED,
    TAG_TOO_SHORT,
    TAG_TOO_LONG,
    TAG_CHARACTERS,
    TAG_BANNED,
    FULL,
    CLOSED,
    NOT_INVITED,
    ALREADY_INVITED,
    ALREADY_APPLIED,
    NO_APPLICATION,
    LEADER_MUST_HAND_OVER,
    CANNOT_AFFORD,
    BANK_EMPTY,
    OVER_DAILY_LIMIT,
    ECONOMY_DOWN,
    INVALID_AMOUNT,
    NO_HOME,
    HOME_LOCKED,
    TOO_MANY_ALLIES,
    ALREADY_ALLIED,
    NOT_ALLIED,
    ALREADY_RIVALS,
    NOT_RIVALS,
    SAME_GUILD,
    RANK_IN_USE,
    TOO_MANY_RANKS,
    LEADER_RANK,
    PERMISSION_NOT_HELD,
    /** Painting the tag or the name needs a higher guild level. */
    STYLE_LOCKED,
    NOT_SAVED,
    PERK_MAXED,
    LEVEL_TOO_LOW,
    NO_PERK_POINTS,
    NOTHING_TO_RESPEC,
    ON_COOLDOWN,
    NOT_TOP_LEVEL,
    VAULT_NOT_EMPTY,
    VAULT_FULL;

    public boolean isSuccess() {
        return this == SUCCESS || this == ASKED;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public String messageKey() {
        return "guilds.outcome." + key();
    }
}
