package de.raindancer.modules.ctf.rules;

import de.raindancer.modules.ctf.model.Fighter;
import de.raindancer.modules.ctf.model.Flag;
import de.raindancer.modules.ctf.model.GameType;
import de.raindancer.modules.ctf.model.Point;
import de.raindancer.modules.ctf.model.Role;
import de.raindancer.modules.ctf.model.Round;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("touching a flag, and bringing one home")
class FlagRuleTest {

    private final FlagRule rule = new FlagRule();

    @Nested
    @DisplayName("classic")
    class Classic {

        private final Round round = Rounds.round(GameType.CLASSIC);
        private final Fighter red = Rounds.join(round, "red", 0, Role.SOLDIER);
        private final Fighter blue = Rounds.join(round, "blue", 1, Role.SOLDIER);
        private final Flag redFlag = round.flagOf(0).orElseThrow();
        private final Flag blueFlag = round.flagOf(1).orElseThrow();

        @Test
        @DisplayName("the other side's flag at home is taken")
        void takesTheirs() {
            assertThat(rule.touch(round, red, blueFlag)).isEqualTo(FlagRule.Touch.TAKE);
        }

        @Test
        @DisplayName("your own flag at home is left alone")
        void ownAtHome() {
            assertThat(rule.touch(round, red, redFlag)).isEqualTo(FlagRule.Touch.NOTHING);
        }

        @Test
        @DisplayName("your own flag lying on the ground goes home when you touch it")
        void returnsOwn() {
            redFlag.drop(Point.of(30, 10, 0), 1000);
            assertThat(rule.touch(round, red, redFlag)).isEqualTo(FlagRule.Touch.RETURN);
        }

        @Test
        @DisplayName("a dropped flag of theirs is picked up again")
        void retakesDropped() {
            blueFlag.drop(Point.of(30, 10, 0), 1000);
            assertThat(rule.touch(round, red, blueFlag)).isEqualTo(FlagRule.Touch.TAKE);
        }

        @Test
        @DisplayName("a carried flag cannot be touched")
        void carried() {
            blueFlag.take(red.id(), 0);
            assertThat(rule.touch(round, Rounds.join(round, "red2", 0, Role.SOLDIER), blueFlag))
                    .isEqualTo(FlagRule.Touch.NOTHING);
            assertThat(rule.touch(round, blue, blueFlag)).isEqualTo(FlagRule.Touch.NOTHING);
        }

        @Test
        @DisplayName("a tank escorts, never carries")
        void tank() {
            Fighter tank = Rounds.join(round, "tank", 0, Role.TANK);
            assertThat(rule.touch(round, tank, blueFlag)).isEqualTo(FlagRule.Touch.MAY_NOT_CARRY);
            redFlag.drop(Point.of(30, 10, 0), 0);
            assertThat(rule.touch(round, tank, redFlag)).as("but returns its own").isEqualTo(FlagRule.Touch.RETURN);
        }

        @Test
        @DisplayName("somebody already carrying a flag does not take a second")
        void oneAtATime() {
            Round three = Rounds.round(GameType.CLASSIC);
            Fighter runner = Rounds.join(three, "runner", 0, Role.SOLDIER);
            three.flagOf(1).orElseThrow().take(runner.id(), 0);
            Flag another = new Flag(2, Point.of(50, 10, 50), 0);
            three.addFlag(another);
            assertThat(rule.touch(three, runner, another)).isEqualTo(FlagRule.Touch.NOTHING);
        }

        @Test
        @DisplayName("the dead touch nothing")
        void dead() {
            red.died(5000);
            assertThat(rule.touch(round, red, blueFlag)).isEqualTo(FlagRule.Touch.NOTHING);
        }

        @Test
        @DisplayName("a capture counts at your own flag's spot while your own flag is home")
        void captures() {
            blueFlag.take(red.id(), 0);
            assertThat(rule.mayCapture(round, red, Point.of(1, 10, 1), 2.0, true)).isTrue();
        }

        @Test
        @DisplayName("not while your own flag is away — get it back first")
        void ownAway() {
            blueFlag.take(red.id(), 0);
            redFlag.take(blue.id(), 0);
            assertThat(rule.mayCapture(round, red, Point.of(1, 10, 1), 2.0, true)).isFalse();
            assertThat(rule.mayCapture(round, red, Point.of(1, 10, 1), 2.0, false))
                    .as("unless the server says your own flag need not be home").isTrue();
        }

        @Test
        @DisplayName("not from too far away, and height does not matter much")
        void reach() {
            blueFlag.take(red.id(), 0);
            assertThat(rule.mayCapture(round, red, Point.of(3, 10, 0), 2.0, true)).isFalse();
            assertThat(rule.mayCapture(round, red, Point.of(0, 11.5, 0), 2.0, true)).isTrue();
            assertThat(rule.mayCapture(round, red, Point.of(0, 16, 0), 2.0, true)).as("but not a floor up").isFalse();
        }

        @Test
        @DisplayName("sudden death: both flags in the middle, a side scores at its own base, home or not")
        void suddenDeath() {
            round.suddenDeath();
            redFlag.moveHome(Point.of(48, 10, 0), 0);
            blueFlag.moveHome(Point.of(52, 10, 0), 0);
            blueFlag.take(red.id(), 0);
            redFlag.take(blue.id(), 0);
            assertThat(rule.mayCapture(round, red, Point.of(0, 10, 0), 2.0, true)).isTrue();
            assertThat(rule.mayCapture(round, red, Point.of(48, 10, 0), 2.0, true)).isFalse();
        }

        @Test
        @DisplayName("bases swapped: a side scores at its new home")
        void swapped() {
            round.side(0, round.side(0).withBase(1));
            round.side(1, round.side(1).withBase(0));
            redFlag.moveHome(Rounds.BLUE_FLAG, 0);
            blueFlag.moveHome(Rounds.RED_FLAG, 0);
            blueFlag.take(red.id(), 0);
            assertThat(rule.mayCapture(round, red, Rounds.BLUE_FLAG, 2.0, true)).isTrue();
        }

        @Test
        @DisplayName("nothing to capture without a flag")
        void emptyHanded() {
            assertThat(rule.mayCapture(round, red, Point.of(0, 10, 0), 2.0, true)).isFalse();
        }
    }

    @Nested
    @DisplayName("neutral")
    class Neutral {

        private final Round round = Rounds.round(GameType.NEUTRAL);
        private final Fighter red = Rounds.join(round, "red", 0, Role.SOLDIER);
        private final Flag middle = round.flagOf(Flag.NEUTRAL).orElseThrow();

        @Test
        @DisplayName("anybody takes the middle flag")
        void anybody() {
            assertThat(rule.touch(round, red, middle)).isEqualTo(FlagRule.Touch.TAKE);
        }

        @Test
        @DisplayName("it is scored in the other side's base, not your own")
        void theirBase() {
            middle.take(red.id(), 0);
            assertThat(rule.mayCapture(round, red, Rounds.BLUE_FLAG, 2.0, true)).isTrue();
            assertThat(rule.mayCapture(round, red, Rounds.RED_FLAG, 2.0, true)).isFalse();
        }

        @Test
        @DisplayName("dropped, anybody picks it up again — there is no side to return it home")
        void dropped() {
            middle.drop(Point.of(40, 10, 0), 0);
            assertThat(rule.touch(round, red, middle)).isEqualTo(FlagRule.Touch.TAKE);
        }
    }

    @Nested
    @DisplayName("one flag")
    class OneFlag {

        private final Round round = Rounds.round(GameType.ONE_FLAG);
        private final Fighter attacker = Rounds.join(round, "red", 0, Role.SOLDIER);
        private final Fighter defender = Rounds.join(round, "blue", 1, Role.SOLDIER);
        private final Flag flag = round.flagOf(1).orElseThrow();

        @Test
        @DisplayName("the attackers take the defenders' flag to their own base")
        void attack() {
            assertThat(rule.touch(round, attacker, flag)).isEqualTo(FlagRule.Touch.TAKE);
            flag.take(attacker.id(), 0);
            assertThat(rule.mayCapture(round, attacker, Rounds.RED_FLAG, 2.0, true))
                    .as("the attackers have no flag of their own to have at home").isTrue();
        }

        @Test
        @DisplayName("the defenders return it when it lies about")
        void defend() {
            flag.drop(Point.of(50, 10, 0), 0);
            assertThat(rule.touch(round, defender, flag)).isEqualTo(FlagRule.Touch.RETURN);
        }
    }

    @Nested
    @DisplayName("time")
    class Time {

        @Test
        @DisplayName("a dropped flag goes home by itself after the return time")
        void returnsByItself() {
            Flag flag = new Flag(0, Rounds.RED_FLAG, 0);
            flag.drop(Point.of(10, 10, 0), 1_000);
            assertThat(rule.returnDue(flag, 30_999, 30)).isFalse();
            assertThat(rule.returnDue(flag, 31_000, 30)).isTrue();
        }

        @Test
        @DisplayName("a neutral flag lying about goes back to the middle the same way")
        void neutralToo() {
            Flag flag = new Flag(Flag.NEUTRAL, Rounds.MIDDLE, 0);
            flag.drop(Point.of(10, 10, 0), 0);
            assertThat(rule.returnDue(flag, 30_000, 30)).isTrue();
        }

        @Test
        @DisplayName("a flag held too long gets hot: it glows, then it slows its carrier")
        void hot() {
            assertThat(rule.heat(14_999, 15, 30)).isEqualTo(FlagRule.Heat.COOL);
            assertThat(rule.heat(15_000, 15, 30)).isEqualTo(FlagRule.Heat.GLOWING);
            assertThat(rule.heat(30_000, 15, 30)).isEqualTo(FlagRule.Heat.SLOWING);
            assertThat(rule.heat(60_000, 0, 0)).as("switched off").isEqualTo(FlagRule.Heat.COOL);
        }

        @Test
        @DisplayName("a scout takes a flag at once; everybody else holds on for the pickup time")
        void pickupTime() {
            assertThat(rule.pickupMillis(Role.SCOUT, 1.5, true)).isZero();
            assertThat(rule.pickupMillis(Role.SOLDIER, 1.5, true)).isEqualTo(1500);
            assertThat(rule.pickupMillis(Role.SOLDIER, 1.5, false)).as("a dropped flag is grabbed").isZero();
        }

        @Test
        @DisplayName("a return is a clutch when the carrier was about to score")
        void clutch() {
            Round round = Rounds.round(GameType.CLASSIC);
            assertThat(rule.isClutch(round, 1, Point.of(103, 10, 0), 5)).as("near blue's own base: blue's carrier")
                    .isTrue();
            assertThat(rule.isClutch(round, 1, Point.of(50, 10, 0), 5)).isFalse();
        }
    }
}
