package de.raindancer.modules.moderation;

import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.modules.moderation.model.Sentence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every default, spelled out.
 *
 * <h2>Why a test that repeats the defaults is not a tautology</h2>
 * Because {@link ModerationSettings} is a record with a positional constructor and thirty components,
 * and two swapped {@code int}s compile perfectly. The failure mode is not "the value is wrong", it is
 * "the report cooldown is now the number of days records are kept" — silent, and only visible on a live
 * server weeks later. The same test on the claims settings caught exactly that, twice.
 *
 * <p>So this is written by reading the record's <em>accessors</em>, never its constructor: it fails when
 * the wiring is wrong and passes when it is right.
 */
class ModerationSettingsTest {

    private final ModerationSettings defaults = ModerationSettings.DEFAULTS;

    @Nested
    @DisplayName("what a punishment does by default")
    class Punishing {

        @Test
        @DisplayName("a ban is permanent, a mute is an hour, a freeze is a quarter of one")
        void theDefaultLengths() {
            assertThat(defaults.defaultBanLength()).isEqualTo("perm");
            assertThat(defaults.defaultMuteLength()).isEqualTo("1h");
            assertThat(defaults.defaultFreezeLength()).isEqualTo("15m");
        }

        @Test
        @DisplayName("the default lengths are lengths this module can actually read")
        void theDefaultLengthsParse() {
            // A default nobody parsed is a default that turns into "unreadable length" the first time a
            // moderator uses the command without an argument.
            assertThat(Sentence.parse(defaults.defaultBanLength()))
                    .hasValueSatisfying(sentence -> assertThat(sentence.isPermanent()).isTrue());
            assertThat(Sentence.parse(defaults.defaultMuteLength()))
                    .hasValueSatisfying(sentence -> assertThat(sentence.length())
                            .contains(Duration.ofHours(1)));
            assertThat(Sentence.parse(defaults.defaultFreezeLength())).isPresent();
        }

        @Test
        @DisplayName("the ladder is on, or the presets are decoration")
        void escalationIsOn() {
            assertThat(defaults.useEscalation()).isTrue();
        }

        @Test
        @DisplayName("a ban takes effect now rather than at the next login")
        void banningKicks() {
            assertThat(defaults.kickOnBan()).isTrue();
        }

        @Test
        @DisplayName("bans are mirrored to the server's own list, and read back from it")
        void vanillaStaysInStep() {
            // Both on: a server that removes this plugin keeps its bans, and a ban typed into the
            // console before it was installed is not silently ignored.
            assertThat(defaults.mirrorToVanillaBanList()).isTrue();
            assertThat(defaults.importVanillaBans()).isTrue();
        }

        @Test
        @DisplayName("there is something to tell a banned player about appealing")
        void thereIsAnAppealMessage() {
            assertThat(defaults.appealMessage()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("what gets announced")
    class Announcing {

        @Test
        @DisplayName("bans and lifts are public; kicks and warnings are not")
        void whoHearsWhat() {
            // A ban is a fact about the server everybody notices anyway. A kick is usually a
            // connection problem, and a warning announced to the room is a punishment on top of the
            // warning.
            assertThat(defaults.announceToEveryone()).isTrue();
            assertThat(defaults.announceLifts()).isTrue();
            assertThat(defaults.announceKicks()).isFalse();
            assertThat(defaults.announceWarnings()).isFalse();
        }

        @Test
        @DisplayName("the moderator is not named to the whole server")
        void moderatorsAreNotNamedPublicly() {
            // Staff get the name in the staff line either way. Putting it in the public one is how a
            // moderator ends up being followed around by the friends of whoever they banned.
            assertThat(defaults.showModeratorName()).isFalse();
        }
    }

    @Nested
    @DisplayName("reports")
    class Reports {

        @Test
        @DisplayName("reports are on, with limits that stop the queue being flooded")
        void theLimits() {
            assertThat(defaults.reportsEnabled()).isTrue();
            assertThat(defaults.reportCooldownSeconds()).isEqualTo(120);
            assertThat(defaults.mostOpenReportsPerPlayer()).isEqualTo(3);
            assertThat(defaults.shortestReport()).isEqualTo(8);
        }

        @Test
        @DisplayName("staff are told when one arrives, and the reporter when it is dealt with")
        void bothEndsAreTold() {
            assertThat(defaults.notifyStaffOnReport()).isTrue();
            assertThat(defaults.tellReporterWhenClosed()).isTrue();
        }

        @Test
        @DisplayName("the cooldown as a duration is what the rule is given")
        void theCooldownAsADuration() {
            assertThat(defaults.reportCooldown()).isEqualTo(Duration.ofSeconds(120));
        }
    }

    @Nested
    @DisplayName("suspicious commands")
    class Suspicious {

        @Test
        @DisplayName("watching is on, for /seed, with a wait so five goes are not five reports")
        void theDefaults() {
            assertThat(defaults.suspiciousCommandsEnabled()).isTrue();
            assertThat(defaults.suspiciousCommands()).containsExactly("seed", "seedcracker");
            assertThat(defaults.suspiciousCooldownSeconds()).isEqualTo(600);
        }

        @Test
        @DisplayName("the cooldown as a duration is what the service is given")
        void theCooldownAsADuration() {
            assertThat(defaults.suspiciousCooldown()).isEqualTo(Duration.ofSeconds(600));
        }

        @Test
        @DisplayName("a null list from a hand-edited file reads as no commands watched, not a crash")
        void nullListIsSafe() {
            assertThat(defaults.withSuspiciousCommands(null).suspiciousCommands()).isEmpty();
        }

        @Test
        @DisplayName("each wither changes exactly its own component")
        void withersChangeOneThing() {
            assertThat(defaults.withSuspiciousCommandsEnabled(false).suspiciousCommandsEnabled())
                    .isFalse();
            assertThat(defaults.withSuspiciousCommandsEnabled(false).suspiciousCommands())
                    .isEqualTo(defaults.suspiciousCommands());

            assertThat(defaults.withSuspiciousCommands(List.of("xray")).suspiciousCommands())
                    .containsExactly("xray");

            assertThat(defaults.withSuspiciousCooldownSeconds(60).suspiciousCooldownSeconds())
                    .isEqualTo(60);
            assertThat(defaults.withSuspiciousCooldownSeconds(60).suspiciousCommandsEnabled())
                    .isEqualTo(defaults.suspiciousCommandsEnabled());
        }
    }

    @Nested
    @DisplayName("x-ray detection")
    class Xray {

        @Test
        @DisplayName("watching is on, for the classic valuable ores")
        void theDefaults() {
            assertThat(defaults.xrayDetectionEnabled()).isTrue();
            assertThat(defaults.xrayOres()).contains("DIAMOND_ORE", "ANCIENT_DEBRIS");
            assertThat(defaults.xrayWindowBlocks()).isEqualTo(200);
            assertThat(defaults.xrayMinimumOre()).isEqualTo(3);
            assertThat(defaults.xrayThresholdPercent()).isEqualTo(8);
            assertThat(defaults.xrayCooldownSeconds()).isEqualTo(900);
        }

        @Test
        @DisplayName("learning is on, and can only ever raise the threshold")
        void learningDefaults() {
            assertThat(defaults.xrayLearningEnabled()).isTrue();
            assertThat(defaults.xrayLearnedMultiplier()).isEqualTo(5);
        }

        @Test
        @DisplayName("the cooldown as a duration is what the service is given")
        void theCooldownAsADuration() {
            assertThat(defaults.xrayCooldown()).isEqualTo(Duration.ofSeconds(900));
        }

        @Test
        @DisplayName("a null ore list from a hand-edited file reads as nothing watched, not a crash")
        void nullOreListIsSafe() {
            assertThat(defaults.withXrayOres(null).xrayOres()).isEmpty();
        }

        @Test
        @DisplayName("each wither changes exactly its own component")
        void withersChangeOneThing() {
            assertThat(defaults.withXrayDetectionEnabled(false).xrayDetectionEnabled()).isFalse();
            assertThat(defaults.withXrayOres(List.of("GOLD_ORE")).xrayOres())
                    .containsExactly("GOLD_ORE");
            assertThat(defaults.withXrayWindowBlocks(500).xrayWindowBlocks()).isEqualTo(500);
            assertThat(defaults.withXrayMinimumOre(10).xrayMinimumOre()).isEqualTo(10);
            assertThat(defaults.withXrayThresholdPercent(20).xrayThresholdPercent()).isEqualTo(20);
            assertThat(defaults.withXrayCooldownSeconds(60).xrayCooldownSeconds()).isEqualTo(60);
            assertThat(defaults.withXrayLearningEnabled(false).xrayLearningEnabled()).isFalse();
            assertThat(defaults.withXrayLearnedMultiplier(10).xrayLearnedMultiplier()).isEqualTo(10);

            // None of the above should have touched a sibling field.
            assertThat(defaults.withXrayThresholdPercent(20).xrayWindowBlocks())
                    .isEqualTo(defaults.xrayWindowBlocks());
        }
    }

    @Nested
    @DisplayName("staff")
    class Staff {

        @Test
        @DisplayName("somebody coming on shift is shown what is waiting")
        void openReportsOnJoin() {
            assertThat(defaults.openReportsOnJoin()).isTrue();
            assertThat(defaults.notesShownOnJoin()).isTrue();
        }

        @Test
        @DisplayName("nobody is vanished without asking, and a vanished moderator can fly")
        void vanishDefaults() {
            // Vanishing on join by default is how a moderator spends an evening wondering why nobody
            // answers them.
            assertThat(defaults.vanishOnJoinForStaff()).isFalse();
            assertThat(defaults.flightWhileVanished()).isTrue();
        }

        @Test
        @DisplayName("staff chat is marked, or it is indistinguishable from ordinary chat")
        void staffChatIsMarked() {
            assertThat(defaults.staffChatPrefix()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("records")
    class Records {

        @Test
        @DisplayName("everything is written to the audit trail and kept for ever")
        void theTrail() {
            assertThat(defaults.auditEverything()).isTrue();
            assertThat(defaults.keepRecordsDays())
                    .as("zero means for ever — a moderation record that expires is one an appeal cannot "
                            + "be answered from")
                    .isZero();
        }

        @Test
        @DisplayName("reports and notes reach the disk without waiting for a shutdown")
        void autoSave() {
            assertThat(defaults.autoSaveSeconds()).isEqualTo(300);
        }

        @Test
        @DisplayName("debug is off")
        void debugIsOff() {
            assertThat(defaults.debug()).isFalse();
        }
    }

    @Nested
    @DisplayName("as a schema")
    class AsASchema {

        @Test
        @DisplayName("it is annotated, or there is no config.yml and no settings page")
        void itIsASettingsRecord() {
            Settings annotation = ModerationSettings.class.getAnnotation(Settings.class);

            assertThat(annotation).isNotNull();
            assertThat(annotation.id()).isEqualTo("moderation");
            assertThat(annotation.topics()).isNotEmpty();
        }

        @Test
        @DisplayName("every component has a key, so nothing lands at a path nobody chose")
        void everyComponentIsKeyed() {
            List<String> unkeyed = new ArrayList<>();
            for (RecordComponent component : ModerationSettings.class.getRecordComponents()) {
                if (component.getAnnotation(Key.class) == null) {
                    unkeyed.add(component.getName());
                }
            }

            assertThat(unkeyed)
                    .as("an unkeyed component gets a path derived from its field name, which then "
                            + "changes the moment somebody renames the field — and every server's "
                            + "setting reverts")
                    .isEmpty();
        }

        @Test
        @DisplayName("no two components write to the same key")
        void keysAreUnique() {
            List<String> keys = new ArrayList<>();
            for (RecordComponent component : ModerationSettings.class.getRecordComponents()) {
                Key key = component.getAnnotation(Key.class);
                if (key != null) {
                    keys.add(key.value());
                }
            }

            assertThat(keys).doesNotHaveDuplicates();
            assertThat(keys).isNotEmpty();
        }

        @Test
        @DisplayName("every key is namespaced, so it cannot collide with another plugin's")
        void everyKeyIsNamespaced() {
            // Core keeps one settings registry for the whole server, so a bare key is a key some
            // other plugin may already own. Live on mc-test this fired for real: `debug` was declared
            // by both claims and moderation, and Core warned that `/settings debug` reaches whichever
            // registered first. A dot in every key is what makes that impossible rather than unlikely.
            List<String> bare = new ArrayList<>();
            for (RecordComponent component : ModerationSettings.class.getRecordComponents()) {
                Key key = component.getAnnotation(Key.class);
                if (key != null && !key.value().contains(".")) {
                    bare.add(component.getName() + " -> '" + key.value() + "'");
                }
            }

            assertThat(bare)
                    .as("these keys are not under a section of their own, so another plugin declaring "
                            + "the same word makes both ambiguous")
                    .isEmpty();
        }

        @Test
        @DisplayName("every default is a real value rather than a null nobody meant")
        void nothingIsNull() {
            List<String> nulls = new ArrayList<>();
            for (RecordComponent component : ModerationSettings.class.getRecordComponents()) {
                try {
                    if (component.getAccessor().invoke(defaults) == null) {
                        nulls.add(component.getName());
                    }
                } catch (ReflectiveOperationException unreadable) {
                    throw new AssertionError("could not read " + component.getName(), unreadable);
                }
            }

            assertThat(nulls).isEmpty();
        }
    }
}
