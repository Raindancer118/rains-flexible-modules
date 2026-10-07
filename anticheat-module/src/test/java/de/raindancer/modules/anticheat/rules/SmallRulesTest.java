package de.raindancer.modules.anticheat.rules;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SmallRulesTest {

    @Test
    @DisplayName("clicks: twenty-five in a second is too many; fifteen is a fast hand")
    void cps() {
        ClickRule rule = new ClickRule();
        assertThat(rule.tooFast(25, 20).failed()).isTrue();
        assertThat(rule.tooFast(15, 20).passed()).isTrue();
    }

    @Test
    @DisplayName("clicks: forty clicks exactly two ticks apart are a machine; a human's spread is not")
    void even() {
        ClickRule rule = new ClickRule();
        double[] machine = new double[45];
        java.util.Arrays.fill(machine, 100);
        for (int i = 0; i < machine.length; i++) {
            machine[i] += (i % 3) - 1;
        }
        assertThat(rule.tooEven(machine).failed()).isTrue();

        Random random = new Random(7);
        double[] human = new double[45];
        for (int i = 0; i < human.length; i++) {
            double intended = 100 + random.nextGaussian() * 22;
            human[i] = Math.max(50, Math.round(intended / 50.0) * 50);
        }
        assertThat(rule.tooEven(human).passed()).isTrue();
        assertThat(rule.tooEven(new double[10]).passed()).as("too few to say").isTrue();
    }

    @Test
    @DisplayName("clicks: the statistics are the textbook ones")
    void stats() {
        ClickRule.Stats stats = new ClickRule().stats(new double[]{100, 100, 100, 100});
        assertThat(stats.cps()).isCloseTo(10, within(1e-9));
        assertThat(stats.deviation()).isZero();
        assertThat(stats.distinctTicks()).isEqualTo(1);
    }

    @Test
    @DisplayName("aim: whole mouse counts at any sensitivity share a step; arbitrary angles do not")
    void sensitivity() {
        AimRule rule = new AimRule();
        double f = 0.5 * 0.6 + 0.2;
        double step = f * f * f * 8 * 0.15;
        Random random = new Random(3);
        double[] mouse = new double[30];
        float pitch = 10f;
        for (int i = 0; i < mouse.length; i++) {
            int counts = random.nextInt(40) - 20;
            float next = (float) (pitch + (counts == 0 ? 3 : counts) * step);
            mouse[i] = next - pitch;
            pitch = next;
        }
        assertThat(rule.commonStep(mouse)).isCloseTo(step, within(1e-3));
        assertThat(rule.noSensitivityStep(mouse).passed()).isTrue();

        double[] bot = new double[30];
        for (int i = 0; i < bot.length; i++) {
            bot[i] = random.nextDouble() * 3 - 1.5;
        }
        assertThat(rule.noSensitivityStep(bot).failed()).isTrue();
    }

    @Test
    @DisplayName("aim: a full-circle jump at the seam after a small turn is a bot keeping yaw in range")
    void wrapped() {
        AimRule rule = new AimRule();
        assertThat(rule.wrapped(5, -355).failed()).isTrue();
        assertThat(rule.wrapped(170, -355).passed()).isTrue();
        assertThat(rule.wrapped(5, 40).passed()).isTrue();
    }

    @Test
    @DisplayName("placing: the top face from above is fine; from below it is impossible")
    void face() {
        PlaceRule rule = new PlaceRule();
        BoundingBox cube = new BoundingBox(0, 64, 0, 1, 65, 1);
        assertThat(rule.faceVisible(new Vector(0.5, 66.6, -1), cube, cube, true, 0, 1, 0, 0.03).passed()).isTrue();
        Judgement below = rule.faceVisible(new Vector(0.5, 64.5, -1.5), cube, cube, true, 0, 1, 0, 0.03);
        assertThat(below.failed()).isTrue();
        assertThat(below.reason()).contains("top");
        assertThat(rule.faceVisible(new Vector(0.5, 64.5, -1.5), cube, cube, true, 0, 0, -1, 0.03).passed())
                .as("the north face from the north").isTrue();
        assertThat(rule.faceVisible(new Vector(0.5, 64.5, 2.5), cube, cube, true, 0, 0, -1, 0.03).failed())
                .as("the north face from the south").isTrue();
    }

    @Test
    @DisplayName("placing: a bottom slab's top can be clicked from just above half a block")
    void slab() {
        PlaceRule rule = new PlaceRule();
        BoundingBox cube = new BoundingBox(0, 64, 0, 1, 65, 1);
        BoundingBox slab = new BoundingBox(0, 64, 0, 1, 64.5, 1);
        assertThat(rule.faceVisible(new Vector(0.5, 64.7, 3), cube, slab, false, 0, 1, 0, 0.03).passed()).isTrue();
    }

    @Test
    @DisplayName("placing: against air or a liquid is impossible; into grass is fine")
    void airPlace() {
        PlaceRule rule = new PlaceRule();
        assertThat(rule.againstNothing(true, true, false).failed()).isTrue();
        assertThat(rule.againstNothing(true, false, true).failed()).isTrue();
        assertThat(rule.againstNothing(true, false, false).passed()).isTrue();
        assertThat(rule.againstNothing(false, true, false).passed()).isTrue();
    }

    @Test
    @DisplayName("breaking: ticks from progress, and how early a break came")
    void breaking() {
        BreakRule rule = new BreakRule();
        assertThat(rule.expectedTicks(1.5)).isZero();
        assertThat(rule.expectedTicks(0.1)).isEqualTo(10);
        assertThat(rule.expectedTicks(0.3)).isEqualTo(4);
        assertThat(rule.shortfall(500, 10, 50)).isZero();
        assertThat(rule.shortfall(300, 10, 50)).isCloseTo(150, within(1e-9));
        assertThat(rule.blatant(100, 10).failed()).isTrue();
        assertThat(rule.blatant(400, 10).passed()).isTrue();
    }

    @Test
    @DisplayName("knockback: rising with it passes, staying put fails, a gentle push is not judged")
    void knockback() {
        MotionRule rule = new MotionRule();
        assertThat(rule.knockbackTaken(0.4, 0.4).passed()).isTrue();
        assertThat(rule.knockbackTaken(0.4, 0.0).failed()).isTrue();
        assertThat(rule.knockbackTaken(0.1, 0.0).passed()).isTrue();
    }

    @Test
    @DisplayName("criticals: a hop of a few hundredths is not a fall; a real jump is")
    void criticals() {
        MotionRule rule = new MotionRule();
        assertThat(rule.critical(true, 0.06, 0.0625, 0.0).failed()).isTrue();
        assertThat(rule.critical(true, 0.05, 0.42, 1.2).passed()).isTrue();
        assertThat(rule.critical(true, 0.42, 0.0, 0.08).passed()).as("stepping off a slab").isTrue();
        assertThat(rule.critical(false, 0.0, 0.0, 0.0).passed()).isTrue();
    }
}
