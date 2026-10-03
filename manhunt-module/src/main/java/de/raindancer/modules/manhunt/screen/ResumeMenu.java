package de.raindancer.modules.manhunt.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.manhunt.stats.HuntRecord;
import de.raindancer.modules.manhunt.stats.HuntSummary;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.Optional;

/**
 * Picking a hunt up after a restart: from zero, from where the last hunt was cut off, or from a time
 * typed in chat — see {@code SpeedrunLobby.resume} for what a resume leaves alone.
 */
public final class ResumeMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Pages pages;

    public ResumeMenu(Pages pages, Player viewer, Menu parent) {
        super(viewer, pages.services().brand(), parent, 3);
        this.pages = pages;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Resume at which time?");
    }

    @Override
    public String breadcrumb() {
        return "Resume";
    }

    @Override
    protected void render() {
        band(1, 2, Icons.of(Material.CLOCK, "<aqua>From 0:00", "<gray>The clock starts over."),
                click -> resume("0:00"));
        Optional<HuntRecord> cut = pages.services().chronicle().history().latest()
                .filter(record -> record.winner() == HuntRecord.Winner.NOBODY);
        if (cut.isPresent()) {
            String at = HuntSummary.clock(cut.get().durationMillis());
            band(1, 4, Icons.of(Material.RECOVERY_COMPASS, "<gold>Where hunt #" + cut.get().number() + " stopped",
                    "<gray>At <white>" + at + "<gray>, when it was cut off."), click -> resume(at));
        }
        band(1, 6, Icons.of(Material.WRITABLE_BOOK, "<white>Type a time…", "<gray>Like 42:05 or 1:02:03, in chat."),
                click -> pages.askResumeTime(viewer));
    }

    private void resume(String time) {
        viewer.closeInventory();
        pages.run(viewer, "manhunt resume " + time);
    }
}
