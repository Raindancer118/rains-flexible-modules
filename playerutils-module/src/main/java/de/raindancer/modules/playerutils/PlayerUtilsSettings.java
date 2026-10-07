package de.raindancer.modules.playerutils;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import de.raindancer.modules.playerutils.rules.SudoRule;
import org.bukkit.Material;

import java.util.List;

@Settings(id = "playerutils", topics = {
        @Topic(path = "playerutils", title = "Player utils", icon = Material.PLAYER_HEAD),
        @Topic(path = "playerutils/telling", title = "Telling people", icon = Material.OAK_SIGN),
        @Topic(path = "playerutils/flight", title = "Flight", icon = Material.ELYTRA),
        @Topic(path = "playerutils/danger", title = "The dangerous ones", icon = Material.TNT),
})
public record PlayerUtilsSettings(
        @In("playerutils/telling") @Title("Tell people what was done to them")
        @Describe("When somebody heals, launches or shrinks another player, that player is told.")
        @Key("notify.targets")
        boolean notifyTargets,

        @In("playerutils/telling") @Title("Say who did it")
        @Describe("Whether that notice names who did it. Off says only what happened.")
        @Key("notify.name-the-actor")
        boolean nameTheActor,

        @In("playerutils") @Title("Heal also takes the bad effects off")
        @Describe("Poison, wither, slowness and the rest. Good effects stay.")
        @Key("heal.cures-bad-effects")
        boolean healCuresBadEffects,

        @In("playerutils") @Title("Heal also puts out fire")
        @Key("heal.extinguishes")
        boolean healExtinguishes,

        @In("playerutils/flight") @Title("Given flight lasts")
        @Describe("Survives relogs, deaths, world and gamemode changes until it is taken away again.")
        @Key("flight.persists")
        boolean flightPersists,

        @In("playerutils/flight") @Title("Soft landing")
        @Describe("Somebody whose flight is taken away mid-air takes no fall damage on that landing.")
        @Key("flight.soft-landing")
        boolean softLanding,

        @In("playerutils") @Title("Furthest /near looks") @Range(min = 1, max = 100000)
        @Key("near.max-radius")
        int nearMaxRadius,

        @In("playerutils") @Title("Most players one selector may hit") @Range(min = 1, max = 1000)
        @Describe("@a on a full server is a lot of heals — or a lot of wipes.")
        @Key("selectors.max-targets")
        int maxSelectorTargets,

        @In("playerutils/danger") @Title("Explosions may break blocks")
        @Describe("Off: /explode never breaks blocks, whatever is typed. On: only with 'blocks' and the "
                + "node rainsplayerutils.explode.blocks.")
        @Key("explode.blocks-allowed")
        boolean explosionsBreakBlocks,

        @In("playerutils/danger") @Title("Wipe asks first")
        @Describe("A typed /wipe needs 'confirm' (players get a confirmation window).")
        @Key("wipe.confirm")
        boolean wipeConfirms,

        @In("playerutils/danger") @Title("Sudo")
        @Describe("Whether /sudo exists at all on this server.")
        @Key("sudo.enabled")
        boolean sudoEnabled,

        @In("playerutils/danger") @Title("Never run as somebody else")
        @Describe("Command words /sudo refuses, whatever namespace they are written with.")
        @Key("sudo.blocked")
        List<String> sudoBlocked) {

    public static final PlayerUtilsSettings DEFAULTS = new PlayerUtilsSettings(true, true, true, true, true,
            true, 1000, 50, false, true, true, SudoRule.DEFAULT_BLOCKED);

    public PlayerUtilsSettings {
        sudoBlocked = sudoBlocked == null ? List.of() : List.copyOf(sudoBlocked);
    }
}
