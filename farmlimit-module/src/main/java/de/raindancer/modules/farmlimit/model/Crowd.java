package de.raindancer.modules.farmlimit.model;

/** How many animals are near a new one: of its own kind, and breedable ones of any kind. */
public record Crowd(int sameKind, int animals) {
}
