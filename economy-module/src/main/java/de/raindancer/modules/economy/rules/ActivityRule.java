package de.raindancer.modules.economy.rules;

/** Whether somebody is playing or just logged in and gone to make tea. */
public final class ActivityRule implements IEconomyRule {

    public boolean active(long lastMovedAt, long now, int afkMinutes) {
        return now - lastMovedAt < Math.max(1, afkMinutes) * 60_000L;
    }

    @Override
    public String describe() {
        return "whether a player counts as active for their salary";
    }
}
