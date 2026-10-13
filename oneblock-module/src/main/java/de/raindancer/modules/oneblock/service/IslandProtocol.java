package de.raindancer.modules.oneblock.service;

/** The topics the other servers send to the island server. Each answer is a {@code Reply} on the wire. */
public final class IslandProtocol {

    /** {@code from}, {@code fromName}, {@code to}, {@code kind}. */
    public static final String INVITE = "oneblock:invite";
    /** {@code player}, {@code name}, {@code from} (may be empty), {@code accept}. */
    public static final String ANSWER = "oneblock:answer";

    private IslandProtocol() {
    }
}
