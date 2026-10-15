package de.raindancer.modules.enchants.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AutopilotTest {

    @Test
    @DisplayName("the camera hangs behind and above, looking at the flier")
    void camera() {
        Autopilot.Point at = new Autopilot.Point(100, 100, 0, 0, 0, Autopilot.Phase.CRUISE);
        Autopilot.Point camera = Autopilot.camera(at, new double[]{1, 0});
        assertThat(camera.x()).isLessThan(100);
        assertThat(camera.y()).isGreaterThan(100);
        assertThat(camera.pitch()).as("looking down at it").isPositive();
        assertThat((double) camera.yaw()).as("facing +x").isCloseTo(-90, within(1e-3));
    }

    @Test
    @DisplayName("the cooldown: the setting at I, half at II, none at III")
    void cooldown() {
        assertThat(Autopilot.cooldownSeconds(1, 60)).isEqualTo(60);
        assertThat(Autopilot.cooldownSeconds(2, 60)).isEqualTo(30);
        assertThat(Autopilot.cooldownSeconds(3, 60)).isZero();
    }

    @Test
    @DisplayName("six cameras, cycled either way; each but the first-person one looks at the flier from its own side")
    void cameras() {
        assertThat(Autopilot.Camera.values()[0]).as("the one a flight starts with").isEqualTo(Autopilot.Camera.ORBIT);
        assertThat(Autopilot.Camera.CHASE.next()).isEqualTo(Autopilot.Camera.SIDE);
        assertThat(Autopilot.Camera.FIRST_PERSON.next()).isEqualTo(Autopilot.Camera.ORBIT);
        assertThat(Autopilot.Camera.ORBIT.previous()).isEqualTo(Autopilot.Camera.FIRST_PERSON);
        Autopilot.Point at = new Autopilot.Point(100, 100, 0, 0, 0, Autopilot.Phase.CRUISE);
        double[] heading = {1, 0};
        Autopilot.Point front = Autopilot.camera(at, heading, Autopilot.Camera.FRONT).orElseThrow();
        assertThat(front.x()).as("ahead of it").isGreaterThan(100);
        assertThat((double) front.yaw()).as("looking back along -x").isCloseTo(90, within(1e-3));
        Autopilot.Point side = Autopilot.camera(at, heading, Autopilot.Camera.SIDE).orElseThrow();
        assertThat(Math.abs(side.z())).as("off to its side").isGreaterThan(5);
        Autopilot.Point overhead = Autopilot.camera(at, heading, Autopilot.Camera.OVERHEAD).orElseThrow();
        assertThat(overhead.y()).isGreaterThan(110);
        assertThat((double) overhead.pitch()).as("looking down").isGreaterThan(60);
        assertThat(Autopilot.camera(at, heading, Autopilot.Camera.FIRST_PERSON)).as("through its own eyes").isEmpty();
        assertThat(Autopilot.camera(at, heading, Autopilot.Camera.CHASE).orElseThrow())
                .isEqualTo(Autopilot.camera(at, heading));
    }

    @Test
    @DisplayName("the orbit camera: around the flier at the yaw and pitch given (the mouse's, or its own slow circling), looking at it")
    void orbit() {
        Autopilot.Point at = new Autopilot.Point(100, 100, 0, 0, 0, Autopilot.Phase.CRUISE);
        java.util.Set<String> quarters = new java.util.HashSet<>();
        for (int yaw = 0; yaw < 360; yaw += 7) {
            Autopilot.Point camera = Autopilot.orbit(at, yaw, 20);
            double distance = Math.sqrt(Math.pow(camera.x() - 100, 2) + Math.pow(camera.y() - 101, 2) + Math.pow(camera.z(), 2));
            assertThat(distance).isCloseTo(Autopilot.ORBIT_RADIUS, within(1e-6));
            assertThat((double) camera.yaw()).isEqualTo((double) (float) yaw);
            assertThat((double) camera.pitch()).isEqualTo(20.0);
            quarters.add((camera.x() > 100 ? "+" : "-") + (camera.z() > 0 ? "+" : "-"));
        }
        assertThat(quarters).as("all the way round").hasSize(4);
        Autopilot.Point down = Autopilot.orbit(at, 0, 60);
        assertThat(down.y()).as("looking down at it from above").isGreaterThan(101);
        assertThat(Autopilot.orbitPitch(200)).isEqualTo(Autopilot.ORBIT_HIGHEST);
        assertThat(Autopilot.orbitPitch(-200)).isEqualTo(Autopilot.ORBIT_LOWEST);
    }
}
