package de.raindancer.modules.roles;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

import java.time.Duration;

/** What an owner decides about roles. The roles themselves are in roles.yml. */
@Settings(id = "roles", topics = {
        @Topic(path = "roles", title = "Roles", icon = Material.NAME_TAG),
})
public record RolesSettings(

        @In("roles") @Title("Roles change prices")
        @Describe("Whether a role's perks count in the shop. Off, everybody keeps their role and pays the "
                + "same as everybody else.")
        @Key("perks") boolean perks,

        @In("roles") @Title("Roles have abilities")
        @Describe("Small things a role does better in the game: a Cook gets hungry slower, a Miner's pickaxe "
                + "wears less, a Hunter hits monsters a little harder. They grow with the role like the perks. "
                + "Off, roles only change prices.")
        @Key("abilities") boolean abilities,

        @In("roles") @Title("Change role again after") @Range(min = 0, max = 8760)
        @Describe("Hours between one choice and the next. The first role is picked whenever; staff with "
                + "/role bypass skip the wait. Zero lets anybody change whenever they like.")
        @Key("change-every-hours") int changeEveryHours,

        @In("roles") @Title("Tell everybody who took a role")
        @Describe("A line in chat when somebody becomes a Cook, a Builder…")
        @Key("announce") boolean announce,

        @In("roles") @Title("Remind players without a role")
        @Describe("A line a few seconds after joining, for anybody who has not picked one yet.")
        @Key("remind-on-join") boolean remindOnJoin,

        @In("roles") @Title("A new role starts at") @Range(min = 0, max = 100)
        @Describe("Percent of its perks' full size. They grow from there the longer the role is kept, so "
                + "swapping roles to chase a discount starts each one small again.")
        @Key("perks.start-share") int startShare,

        @In("roles") @Title("Perks are full after") @Range(min = 0, max = 365)
        @Describe("Days of keeping a role until its perks reach the size written in roles.yml. Zero: full at once.")
        @Key("perks.full-after-days") int fullAfterDays,

        @In("roles") @Title("Sell roles")
        @Describe("Lets players buy and rent the roles that have a price or rent in roles.yml. Off, those roles "
                + "stay closed, nothing is charged and no rent is collected.")
        @Key("sell-roles") boolean sellRoles) {

    public static final RolesSettings DEFAULTS = new RolesSettings(true, true, 72, true, true, 40, 14, false);

    public Duration changeEvery() {
        return Duration.ofHours(Math.max(0, changeEveryHours));
    }
}
