package de.raindancer.modules.chat.model;

import java.util.UUID;

/**
 * One {@code @name} in a chat line that means somebody: where it stands, whom it means, and whether they
 * can be pinged right now. Somebody offline — or hidden from the sender, which must look the same — is
 * still a mention: drawn like one, and told when they are back.
 *
 * @param start     index of the {@code @} in the line
 * @param end       index just past the name
 * @param player    whom it means
 * @param name      their real name
 * @param reachable online and visible to the sender, so pinged now
 */
public record Mention(int start, int end, UUID player, String name, boolean reachable) {
}
