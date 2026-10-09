package de.raindancer.modules.performance.model;

/** One crowded chunk: how many entities it ticks, and which kind is most of them. */
public record Hotspot(String world, int chunkX, int chunkZ, int total, String mostCommon, int mostCommonCount) {

    public int blockX() {
        return (chunkX << 4) + 8;
    }

    public int blockZ() {
        return (chunkZ << 4) + 8;
    }
}
