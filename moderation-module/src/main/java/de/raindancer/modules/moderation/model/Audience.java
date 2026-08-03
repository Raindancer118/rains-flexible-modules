package de.raindancer.modules.moderation.model;

/**
 * Who hears about something.
 *
 * <p>Three answers rather than a boolean, because {@link #NOBODY} is a real one: a server that wants
 * its moderation invisible should be able to have it, and the alternative is an owner switching a
 * feature off to silence it.
 *
 * <p>In practice {@code AnnouncementRule} never chooses {@link #NOBODY} for a punishment — staff always
 * get the line, because a punishment nobody but its author knows about is one nobody can answer for.
 */
public enum Audience {

    /** Everybody on the server. */
    EVERYBODY("everybody on the server"),

    /** Whoever may read the moderation log. */
    STAFF("the staff"),

    /** Written to the record and to nobody's chat. */
    NOBODY("nobody");

    private final String description;

    Audience(String description) {
        this.description = description;
    }

    /** Who this means, for the lore line on a button that says who will hear. */
    public String describe() {
        return description;
    }
}
