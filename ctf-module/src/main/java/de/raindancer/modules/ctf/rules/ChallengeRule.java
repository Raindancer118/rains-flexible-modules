package de.raindancer.modules.ctf.rules;

import de.raindancer.modules.ctf.model.Challenge;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Which challenges a day has — the same for everybody — and how one moves on. */
public final class ChallengeRule implements ICtfRule {

    /** A challenge's count after a step, and whether that step finished it. */
    public record Step(int now, boolean justDone) {
    }

    public List<Challenge> today(LocalDate day, int count) {
        List<Challenge> all = new ArrayList<>(Arrays.asList(Challenge.values()));
        Collections.shuffle(all, new Random(day.toEpochDay() * 31 + 7));
        return List.copyOf(all.subList(0, Math.max(0, Math.min(count, all.size()))));
    }

    public Step step(Challenge challenge, int before, int by) {
        int now = Math.min(challenge.goal(), Math.max(0, before) + Math.max(0, by));
        return new Step(now, before < challenge.goal() && now >= challenge.goal());
    }
}
