package de.raindancer.modules.chat.rules;

import de.raindancer.core.platform.rule.Verdict;

/** Whether a paid ad may be placed, judged on its text and the time since the same player's last one. */
public final class AdRule {

    public Verdict judge(boolean enabled, String text, int mostLength, long millisSinceLastAd, int cooldownSeconds) {
        if (!enabled) {
            return Verdict.refused("chat.ad.off");
        }
        if (text == null || text.isBlank()) {
            return Verdict.refused("chat.ad.empty");
        }
        if (text.strip().length() > mostLength) {
            return Verdict.refused("chat.ad.too-long", mostLength);
        }
        long remaining = cooldownSeconds * 1000L - millisSinceLastAd;
        if (cooldownSeconds > 0 && remaining > 0) {
            return Verdict.refused("chat.ad.cooldown", (remaining + 999) / 1000);
        }
        return Verdict.allowed();
    }
}
