package de.raindancer.modules.anticheat.rules;

import de.raindancer.modules.anticheat.AntiCheatSettings;
import de.raindancer.modules.anticheat.model.CheckType;

/** What one failed check leads to, from its violation level and the owner's settings. */
public final class ActionRule implements IAntiCheatRule {

    /**
     * @param act  set back or cancel, whichever the check does
     * @param ban  the ban level is reached and the owner switched bans on: the rules punish, else a fixed ban
     */
    public record Decision(boolean alert, boolean act, boolean kick, boolean ban) {

        public static final Decision NOTHING = new Decision(false, false, false, false);
    }

    public Decision decide(CheckType check, double level, AntiCheatSettings settings) {
        if (!settings.enabled() || settings.disabled(check.key())) {
            return Decision.NOTHING;
        }
        if (check.experimental() && !settings.experimentalChecks()) {
            return Decision.NOTHING;
        }
        boolean alert = settings.alerts() && level >= check.alertAt();
        boolean passive = check.experimental() || settings.silent(check.key());
        boolean act = !passive && switch (check.action()) {
            case SETBACK -> settings.setbacks();
            case CANCEL -> settings.cancel();
            case NONE -> false;
        };
        double scale = settings.punishScale() / 100.0;
        boolean kick = !passive && settings.autoKick() && check.kickAt() > 0 && level >= check.kickAt() * scale;
        boolean ban = !passive && settings.autoBan() && check.banAt() > 0 && level >= check.banAt() * scale;
        return new Decision(alert, act, kick && !ban, ban);
    }

    /** Whether a check runs at all. */
    public boolean runs(CheckType check, AntiCheatSettings settings) {
        return settings.enabled() && !settings.disabled(check.key())
                && (!check.experimental() || settings.experimentalChecks());
    }

    @Override
    public String describe() {
        return "what a failed check leads to: an alert, a setback or cancel, a kick, a ban";
    }
}
