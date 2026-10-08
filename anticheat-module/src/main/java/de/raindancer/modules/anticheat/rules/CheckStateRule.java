package de.raindancer.modules.anticheat.rules;

import de.raindancer.modules.anticheat.model.CheckType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Where a check stands — on, watch only, off — and the step a click takes it to. */
public final class CheckStateRule implements IAntiCheatRule {

    public enum State { ON, WATCH, OFF }

    /** The two setting lists a check's state lives in. */
    public record Lists(List<String> disabled, List<String> silent) {
    }

    public State state(CheckType check, Lists lists) {
        if (contains(lists.disabled(), check)) {
            return State.OFF;
        }
        return contains(lists.silent(), check) ? State.WATCH : State.ON;
    }

    public Lists next(CheckType check, Lists lists) {
        List<String> disabled = without(lists.disabled(), check);
        List<String> silent = without(lists.silent(), check);
        switch (state(check, lists)) {
            case ON -> silent.add(check.key());
            case WATCH -> disabled.add(check.key());
            case OFF -> {
            }
        }
        return new Lists(List.copyOf(disabled), List.copyOf(silent));
    }

    private static boolean contains(List<String> list, CheckType check) {
        return list.stream().anyMatch(entry -> entry != null && entry.trim().toLowerCase(Locale.ROOT).equals(check.key()));
    }

    private static List<String> without(List<String> list, CheckType check) {
        List<String> kept = new ArrayList<>();
        for (String entry : list) {
            if (entry != null && !entry.trim().toLowerCase(Locale.ROOT).equals(check.key())) {
                kept.add(entry.trim());
            }
        }
        return kept;
    }

    @Override
    public String describe() {
        return "where a check stands and where a click takes it";
    }
}
