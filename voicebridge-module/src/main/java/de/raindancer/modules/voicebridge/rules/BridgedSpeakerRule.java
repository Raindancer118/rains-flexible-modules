package de.raindancer.modules.voicebridge.rules;

import java.util.Objects;
import java.util.UUID;

/**
 * Whose voice goes to Discord: only somebody standing in the bridge group.
 *
 * <p>This is the privacy line. Joining the group is the consent — everybody else on the server,
 * in proximity chat or in another group, is never sent anywhere, and with no bridge group set up
 * yet the answer is nobody rather than everybody.
 */
public final class BridgedSpeakerRule implements IVoiceBridgeRule {

    public boolean bridges(UUID bridgeGroup, UUID speakersGroup) {
        return bridgeGroup != null && Objects.equals(bridgeGroup, speakersGroup);
    }

    @Override
    public String describe() {
        return "only players in the bridge group are heard on Discord";
    }
}
