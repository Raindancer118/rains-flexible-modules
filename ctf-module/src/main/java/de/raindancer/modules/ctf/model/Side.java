package de.raindancer.modules.ctf.model;

import de.raindancer.core.social.team.TeamColour;

/**
 * One side of a round. {@code base} is the index of the map base it plays from — sides swap bases in
 * {@link Mutator#SWAP_BASES}. {@code group} is the guild it stands for in a guild war, else empty.
 */
public record Side(int index, TeamColour colour, String name, int base, String group) {

    public Side withBase(int newBase) {
        return new Side(index, colour, name, newBase, group);
    }

    /** The name in MiniMessage, in the side's colour. */
    public String tagged() {
        return "<" + colour.textColour().asHexString() + ">" + name + "</" + colour.textColour().asHexString() + ">";
    }
}
