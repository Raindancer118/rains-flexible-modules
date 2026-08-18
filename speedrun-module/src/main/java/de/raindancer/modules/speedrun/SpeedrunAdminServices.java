package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.messages.Messages;

/**
 * What every command in this module needs — built once {@code SpeedrunModule.enable} has an actual
 * {@link SpeedrunLobby}, handed to {@code SpeedrunCommands.ready}. See {@code RtpServices} for the
 * same shape one module over.
 */
public record SpeedrunAdminServices(SpeedrunLobby lobby, Messages messages) {
}
