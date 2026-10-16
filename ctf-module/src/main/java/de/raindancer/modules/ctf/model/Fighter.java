package de.raindancer.modules.ctf.model;

import java.util.UUID;

/**
 * One player in a round and everything the round counts about them. Guarded by the round, like {@link Flag}.
 */
public final class Fighter {

    private final UUID id;
    private final String name;
    private int side;
    private Role role;
    private Role nextRole;
    private boolean alive = true;
    private long respawnAt;
    private long joinedAt;

    private int kills;
    private int deaths;
    private int captures;
    private int returns;
    private int pickups;
    private int drops;
    private int escortKills;
    private int defenceKills;
    private int carrierKills;
    private int clutchReturns;
    private int powerUps;
    private int assists;
    private long carryMillis;
    private long carryingSince = -1;
    private long fastestCapture = Long.MAX_VALUE;
    private int bestStreak;
    private int doubleJumps;
    private boolean reviveUsed;

    public Fighter(UUID id, String name, int side, Role role, long now) {
        this.id = id;
        this.name = name;
        this.side = side;
        this.role = role;
        this.joinedAt = now;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public int side() {
        return side;
    }

    public void side(int newSide) {
        side = newSide;
    }

    public Role role() {
        return role;
    }

    /** The role for the next life, if they picked one; applied at respawn. */
    public Role nextRole() {
        return nextRole == null ? role : nextRole;
    }

    public void pickRole(Role picked) {
        nextRole = picked;
    }

    /** Becomes the picked role; called when they come back to life. */
    public void takeNextRole() {
        if (nextRole != null) {
            role = nextRole;
            nextRole = null;
        }
        reviveUsed = false;
    }

    public void forceRole(Role forced) {
        role = forced;
        nextRole = null;
    }

    public boolean isAlive() {
        return alive;
    }

    public long respawnAt() {
        return respawnAt;
    }

    public long joinedAt() {
        return joinedAt;
    }

    public void died(long respawnAtMillis) {
        alive = false;
        respawnAt = respawnAtMillis;
        deaths++;
        doubleJumps = 0;
    }

    /** Back to a spawn without having died: half time, a switched side, a late join. */
    public void sendBack(long at) {
        alive = false;
        respawnAt = at;
        doubleJumps = 0;
    }

    public void respawned() {
        alive = true;
    }

    public void killed() {
        kills++;
    }

    public void captured(long tookMillis) {
        captures++;
        fastestCapture = Math.min(fastestCapture, tookMillis);
    }

    public void returned(boolean clutch) {
        returns++;
        if (clutch) {
            clutchReturns++;
        }
    }

    public void pickedUp(long now) {
        pickups++;
        carryingSince = now;
    }

    /** They no longer carry: their carry time is added up. */
    public void stoppedCarrying(long now, boolean dropped) {
        if (carryingSince >= 0) {
            carryMillis += Math.max(0, now - carryingSince);
            carryingSince = -1;
        }
        if (dropped) {
            drops++;
        }
    }

    public long carryingFor(long now) {
        return carryingSince < 0 ? 0 : Math.max(0, now - carryingSince);
    }

    public void escortKill() {
        escortKills++;
    }

    public void defenceKill() {
        defenceKills++;
    }

    public void carrierKill() {
        carrierKills++;
    }

    public void assist() {
        assists++;
    }

    public void tookPowerUp() {
        powerUps++;
    }

    public void streak(int length) {
        bestStreak = Math.max(bestStreak, length);
    }

    public int doubleJumps() {
        return doubleJumps;
    }

    public void doubleJumps(int left) {
        doubleJumps = Math.max(0, left);
    }

    public boolean reviveUsed() {
        return reviveUsed;
    }

    public void useRevive() {
        reviveUsed = true;
    }

    public int kills() {
        return kills;
    }

    public int deaths() {
        return deaths;
    }

    public int captures() {
        return captures;
    }

    public int returns() {
        return returns;
    }

    public int pickups() {
        return pickups;
    }

    public int drops() {
        return drops;
    }

    public int escortKills() {
        return escortKills;
    }

    public int defenceKills() {
        return defenceKills;
    }

    public int carrierKills() {
        return carrierKills;
    }

    public int clutchReturns() {
        return clutchReturns;
    }

    public int powerUps() {
        return powerUps;
    }

    public int assists() {
        return assists;
    }

    public long carryMillis() {
        return carryMillis;
    }

    /** Fastest capture this round, from pick-up to capture; empty if none. */
    public java.util.OptionalLong fastestCapture() {
        return fastestCapture == Long.MAX_VALUE ? java.util.OptionalLong.empty()
                : java.util.OptionalLong.of(fastestCapture);
    }

    public int bestStreak() {
        return bestStreak;
    }

    /** The points the scoreboard and the MVP go by. */
    public int score() {
        return captures * 10 + returns * 4 + carrierKills * 3 + kills * 2 + escortKills * 2 + defenceKills * 2
                + pickups + assists + clutchReturns * 3;
    }
}
