package de.raindancer.modules.jobs.listener;

import org.bukkit.Location;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuestListenerTest {

    @Test
    @DisplayName("travel counts only for somebody turning their view: an idle player carried by water or a cart does not")
    void lookedAround() {
        Location before = new Location(null, 0, 64, 0, 90f, 10f);
        assertThat(QuestListener.lookedAround(before, new Location(null, 40, 64, 0, 90f, 10f))).isFalse();
        assertThat(QuestListener.lookedAround(before, new Location(null, 40, 64, 0, 97f, 10f))).isTrue();
        assertThat(QuestListener.lookedAround(before, new Location(null, 40, 64, 0, 90f, -4f))).isTrue();
    }
}
