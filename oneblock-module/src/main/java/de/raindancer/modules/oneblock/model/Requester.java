package de.raindancer.modules.oneblock.model;

import java.util.UUID;

/** A player as the island server knows them, wherever on the network they typed the command. */
public record Requester(UUID id, String name) {
}
