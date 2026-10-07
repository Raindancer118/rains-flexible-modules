package de.raindancer.modules.voicebridge.rules;

import de.raindancer.modules.voicebridge.model.DiscordTarget;

import java.util.Optional;

/**
 * Whether there is enough to try Discord at all, and if not, which message says what is missing —
 * in the order an owner sets things up, so the answer is always the next step.
 */
public final class ConnectReadinessRule implements IVoiceBridgeRule {

    public Optional<String> refusal(boolean enabled, String token, DiscordTarget target) {
        if (!enabled) {
            return Optional.of("voicebridge.not-ready.off");
        }
        if (token == null || token.isBlank()) {
            return Optional.of("voicebridge.not-ready.no-token");
        }
        if (target.guildId().isEmpty()) {
            return Optional.of("voicebridge.not-ready.no-server");
        }
        if (!DiscordTarget.isSnowflake(target.guildId())) {
            return Optional.of("voicebridge.not-ready.bad-server");
        }
        if (target.channelId().isEmpty()) {
            return Optional.of("voicebridge.not-ready.no-channel");
        }
        if (!DiscordTarget.isSnowflake(target.channelId())) {
            return Optional.of("voicebridge.not-ready.bad-channel");
        }
        return Optional.empty();
    }

    @Override
    public String describe() {
        return "a token, a Discord server id and a voice channel id are all there";
    }
}
