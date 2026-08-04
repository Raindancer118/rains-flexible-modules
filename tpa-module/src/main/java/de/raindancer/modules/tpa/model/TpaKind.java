package de.raindancer.modules.tpa.model;

import java.util.UUID;

/**
 * Which way round a teleport request goes.
 *
 * <h2>Why the wording is whole clauses</h2>
 * Because building the sentence out of fragments produced "wants to teleport to come to them" on a live
 * server. A request is asked about in three places — the line the asker sees, the line the asked person
 * sees, and the button lore — and each needs a different grammatical shape. Storing the fragments and
 * gluing them is how they came out wrong; storing the sentences is how they come out right.
 */
public enum TpaKind {

    /** They come to you: {@code /tpa}. The person who asked is the one who travels. */
    TO("wants to teleport to you", "You asked to teleport to <player>"),

    /** You come to them: {@code /tpahere}. The person who answered is the one who travels. */
    HERE("would like you to teleport to them", "You asked <player> to teleport to you");

    private final String asked;
    private final String asking;

    TpaKind(String asked, String asking) {
        this.asked = asked;
        this.asking = asking;
    }

    /** What the person being asked is told, after their asker's name. */
    public String asked() {
        return asked;
    }

    /** What the asker is told, with {@code <player>} still in it for the caller to fill. */
    public String asking() {
        return asking;
    }

    /**
     * Who actually moves.
     *
     * <p>The one question the direction exists to answer, and the one that teleports the wrong person
     * across the world when it is got backwards.
     */
    public UUID travellerOf(UUID from, UUID to) {
        return this == TO ? from : to;
    }

    /** Who they end up standing next to. */
    public UUID destinationOf(UUID from, UUID to) {
        return this == TO ? to : from;
    }
}
