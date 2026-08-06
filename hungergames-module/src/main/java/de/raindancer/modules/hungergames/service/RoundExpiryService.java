package de.raindancer.modules.hungergames.service;

import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.GamePhase;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * What happens when the round's clock runs out: somebody is asked, rather than the round simply stopping.
 *
 * <h2>Why the clock does not end the round by itself</h2>
 * Because "the scheduled length" and "the tournament is over" are not the same statement, and the plugin
 * is not the one that knows the difference. The clock running out with four tributes circling each other
 * inside a 200-block border is the most interesting three minutes of the evening, and a round that
 * announces a winner on a head count in the middle of it has thrown away the thing everybody stayed for.
 * Equally, a round with fourteen people still hiding at the two-hour mark should end, and somebody has to
 * say so.
 *
 * <p>Both of those are judgements about the room — who is watching, what the schedule after this is,
 * whether the fight is worth another five minutes. So at the scheduled end this puts the question to the
 * people who can see the room, with two buttons, and does nothing until one of them answers.
 *
 * <h2>Nobody answering is an answer, and it is "keep playing"</h2>
 * The one outcome that must never happen is a tournament ending because nobody was looking. So an
 * unanswered question extends the round by {@link #EXTENSION} and asks again, for as long as that takes —
 * including when there is nobody eligible online at all, which is a server whose staff have gone to bed
 * and whose round should still be running when they get up.
 *
 * <p>The cost of being wrong is asymmetric and that is the whole design: an extra five minutes of a round
 * that should have ended is an inconvenience, and ending a round that should have continued cannot be
 * undone at all.
 *
 * <h2>Where the Adventure components are</h2>
 * Not here. This decides <em>when</em> to ask and what each answer does; {@link Prompt} puts it on the
 * screen, and the module wires that to Core's {@code ChatButtons.ask(…)} — the same clickable pair the
 * teleport requests use, so the buttons an operator clicks here look and behave like every other pair of
 * buttons on the server. Keeping them apart is also what makes every branch below testable without a
 * server: the tests drive this with a fake prompt and answer it however they like.
 */
public final class RoundExpiryService implements IHungerGamesService {

    /**
     * How much longer the round runs when the question goes unanswered, before it is asked again.
     *
     * <p>Five minutes. Long enough that a gamemaster mid-sentence is not being nagged, short enough that
     * a round which genuinely should have ended does not run for another hour because somebody stepped
     * away. Also what the "keep playing" button grants, so that clicking it and ignoring it come to the
     * same thing — an operator should not have to work out which of the two costs less.
     */
    public static final Duration EXTENSION = Duration.ofMinutes(5);

    /** Putting the question in front of one person, with a button for each answer. */
    @FunctionalInterface
    public interface Prompt {

        /**
         * @param who     somebody who may decide, online now
         * @param overrun how far past the scheduled end the round already is
         * @param end     what the "end the round" button does
         * @param extend  what the "keep playing" button does
         */
        void ask(UUID who, Duration overrun, Runnable end, Runnable extend);
    }

    /** Everybody who may decide and is online right now — operators and gamemasters. */
    @FunctionalInterface
    public interface Audience {
        Collection<UUID> whoCanDecide();
    }

    /** Something worth putting in the log, so a round that ran long has a reason in writing. */
    @FunctionalInterface
    public interface Note {
        void say(String message);
    }

    private final GameControlService control;
    private final Audience audience;
    private final Prompt prompt;
    private final Note note;
    private final Supplier<GamePhase> phase;

    private HungerGamesSettings settings = HungerGamesSettings.DEFAULTS;

    /**
     * When the round is currently due to end. Starts as the configured length and moves out by
     * {@link #EXTENSION} every time the question is answered with "keep playing" or not answered at all.
     */
    private Duration deadline;

    /** Whether a question is out and has not been answered. Stops the clock asking again every tick. */
    private boolean waiting;

    /** True once somebody has said "end it", so a second click from somebody else does nothing. */
    private boolean decided;

    public RoundExpiryService(GameControlService control, Audience audience, Prompt prompt, Note note,
                              Supplier<GamePhase> phase) {
        this.control = control;
        this.audience = audience;
        this.prompt = prompt;
        this.note = note;
        this.phase = phase;
        this.deadline = settings.roundDuration();
    }

    /**
     * A reload changes when the round is due to end, unless somebody has already been asked.
     *
     * <p>Not applied while a question is out: an operator looking at "the round is over, end it?" whose
     * deadline silently moves has been asked about something that is no longer true.
     */
    @Override
    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
        if (!waiting && !decided) {
            this.deadline = settings.roundDuration();
        }
    }

    /**
     * Called by the round clock with how long the round has been running.
     *
     * <p>Idempotent and cheap: everything before the deadline returns immediately, and after it nothing
     * happens twice while a question is outstanding.
     */
    public void tick(Duration elapsed) {
        if (decided || waiting || phase.get() != GamePhase.RUNNING) {
            return;
        }
        if (elapsed.compareTo(deadline) < 0) {
            return;
        }
        askOrExtend(elapsed);
    }

    private void askOrExtend(Duration elapsed) {
        Duration overrun = elapsed.minus(settings.roundDuration());
        Collection<UUID> deciders = List.copyOf(audience.whoCanDecide());

        if (deciders.isEmpty()) {
            // Nobody to ask. The round runs on rather than ending on nobody's decision — see the class
            // note on which way round the cost of being wrong falls.
            extend("nobody who could decide was online");
            return;
        }

        waiting = true;
        for (UUID decider : deciders) {
            prompt.ask(decider, overrun, () -> answeredEnd(decider), () -> answeredExtend(decider));
        }
        note.say("The round has reached its scheduled length. " + deciders.size()
                + " operator(s)/gamemaster(s) asked whether to end it; it runs on until one of them says so.");
    }

    /** Somebody clicked "end the round". The first click wins; later ones do nothing. */
    private void answeredEnd(UUID who) {
        if (decided) {
            return;
        }
        decided = true;
        waiting = false;
        boolean ended = control.endRound();
        note.say(ended
                ? "The round was ended on time by " + who + "."
                : "The round was not RUNNING any more by the time " + who + " answered, so nothing "
                        + "happened — it had already finished on its own.");
    }

    /** Somebody clicked "keep playing". Same effect as nobody answering, deliberately. */
    private void answeredExtend(UUID who) {
        if (decided || !waiting) {
            return;
        }
        extend("kept running by " + who);
    }

    private void extend(String because) {
        waiting = false;
        deadline = deadline.plus(EXTENSION);
        note.say("The round runs another " + EXTENSION.toMinutes() + " minutes (" + because
                + "); the question will be put again then.");
    }

    /**
     * When the round is currently due to end, counting every extension so far.
     *
     * <p>For a scoreboard or the admin suite: an operator looking at a round that is past its configured
     * length should be able to see how far it has been carried, rather than reading a clock that says the
     * round ended twenty minutes ago.
     */
    public Duration deadline() {
        return deadline;
    }

    /** Whether a question is currently out and unanswered. */
    public boolean isWaitingForAnAnswer() {
        return waiting;
    }

    /** Who ended the round, once somebody has — for the admin suite. Empty while it is still running. */
    public Optional<Boolean> wasEnded() {
        return decided ? Optional.of(true) : Optional.empty();
    }

    /** Forgets every extension, for the next round. */
    public void reset() {
        deadline = settings.roundDuration();
        waiting = false;
        decided = false;
    }

    @Override
    public String describe() {
        return "asking whether a round that has run its length should end";
    }
}
