package de.raindancer.modules.ctf.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.ctf.CtfServices;
import de.raindancer.modules.ctf.model.Challenge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

import java.util.List;

/** Today's challenges and how far along each is. */
public final class ChallengesMenu extends Menu implements ICtfScreen {

    private final CtfServices services;

    public ChallengesMenu(CtfServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Today's challenges");
    }

    @Override
    protected void render() {
        List<Challenge> today = services.progress().todays();
        int step = today.size() <= 4 ? 2 : 1;
        int column = today.size() == 4 ? 1 : today.size() <= 3 ? 4 - (today.size() - 1) : 1;
        for (Challenge challenge : today) {
            int done = services.progress().challengeDone(viewer.getUniqueId(), challenge);
            boolean finished = done >= challenge.goal();
            band(2, column, Icons.of(challenge.icon(), (finished ? "<green>✔ " : "<white>") + challenge.describe(),
                    "<gray>" + done + " of " + challenge.goal(), "<gray>Worth <white>" + challenge.xp()
                            + " <gray>experience", finished ? "<green>Done for today." : "<yellow>New ones tomorrow."));
            column += step;
        }
    }

    @Override
    public String breadcrumb() {
        return "challenges";
    }

    @Override
    public String describe() {
        return "today's challenges";
    }
}
