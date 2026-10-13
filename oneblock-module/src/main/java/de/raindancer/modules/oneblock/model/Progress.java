package de.raindancer.modules.oneblock.model;

/**
 * Where an island's block is in AOneBlock's phases.
 *
 * @param blocks  AOneBlock's count for the island — what /ob count shows and the phases follow
 * @param percent how far through the current phase, 0–100
 * @param next    the next phase's name; empty when this is the last
 * @param toNext  blocks left until it
 */
public record Progress(String phase, long blocks, int percent, String next, int toNext) {
}
