package de.raindancer.modules.moderation.rules;

import de.raindancer.modules.moderation.AntiCheatSettings;
import de.raindancer.modules.moderation.model.CheckType;

/** What one failed check leads to, from its violation level and the owner's settings. */
public final class ActionRule implements IModerationRule {

    /** @param ban the ban level is reached and the owner switched bans on: the rules punish, else a fixed ban */
    public record Decision(boolean alert, boolean kick, boolean ban) {

        public static final Decision NOTHING = new Decision(false, false, false);
    }

    public Decision decide(CheckType check, double level, AntiCheatSettings settings) {
        if (!settings.enabled() || settings.disabled(check.key())) {
            return Decision.NOTHING;
        }
        boolean alert = settings.alerts() && level >= check.alertAt();
        boolean passive = settings.silent(check.key());
        double scale = settings.punishScale() / 100.0;
        boolean kick = !passive && settings.autoKick() && check.kickAt() > 0 && level >= check.kickAt() * scale;
        boolean ban = !passive && settings.autoBan() && check.banAt() > 0 && level >= check.banAt() * scale;
        return new Decision(alert, kick && !ban, ban);
    }

    /** Whether a check runs at all. */
    public boolean runs(CheckType check, AntiCheatSettings settings) {
        return settings.enabled() && !settings.disabled(check.key());
    }

    @Override
    public String describe() {
        return "what a level leads to: an alert, a kick, a ban";
    }
}
