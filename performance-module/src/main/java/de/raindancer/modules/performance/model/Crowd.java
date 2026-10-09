package de.raindancer.modules.performance.model;

/** How many animals are near a new one: of its own kind, and breedable ones of any kind. */
public record Crowd(int sameKind, int animals) {
}
