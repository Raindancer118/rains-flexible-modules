package de.raindancer.modules.moderation.model;

/** Where somebody stood when a report was filed: the world by name, the block they were on. */
public record ReportPlace(String world, int x, int y, int z) {

    public String describe() {
        return world + " " + x + " " + y + " " + z;
    }
}
