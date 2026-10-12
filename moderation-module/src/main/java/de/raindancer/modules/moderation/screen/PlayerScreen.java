package de.raindancer.modules.moderation.screen;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.OverviewAccess;
import de.raindancer.modules.moderation.model.PlayerOverview;
import de.raindancer.modules.moderation.service.LogSearchService;
import de.raindancer.modules.moderation.service.PlayerOverviewService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A page about one player: the hub and the three rooms off it. Each gathers the overview afresh when it
 * opens — part of it lives in databases, read off the server's threads — so coming Back from a
 * punishment shows it, and each shows the same header: where they are, what is in force, who they are.
 */
public abstract class PlayerScreen extends ModerationScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    /** Vanilla's own node: going to somebody is the teleport command's power, not a moderation one. */
    static final String TELEPORT = "minecraft.command.teleport";

    protected final UUID subject;
    protected final String subjectName;
    /** Null until gathered; see {@link #open()}. */
    private volatile PlayerOverview overview;

    protected PlayerScreen(ModerationServices services, Player viewer, Menu parent, UUID subject, String subjectName) {
        super(services, viewer, parent);
        this.subject = subject;
        this.subjectName = subjectName == null || subjectName.isBlank() ? "somebody" : subjectName;
    }

    /** What this room is called, in the title and the breadcrumb. */
    protected abstract String room();

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>" + room() + " — <white>" + subjectName);
    }

    @Override
    public String breadcrumb() {
        return room();
    }

    @Override
    public void open() {
        new PlayerOverviewService(services()).gather(subject, subjectName, gathered -> Scheduling.entity(
                services().plugin(), viewer, () -> {
                    overview = gathered;
                    super.open();
                }));
    }

    protected PlayerOverview seen() {
        PlayerOverview gathered = overview;
        return gathered != null ? gathered : PlayerOverview.of(subject, subjectName).build();
    }

    /** Each part of the overview only for whoever may already read it behind its own door. */
    protected OverviewAccess access() {
        return OverviewAccess.of(this::may, viewer.hasPermission(TELEPORT), viewer.isOp());
    }

    /**
     * One group of a room: a coloured pane at each end of the row naming it, the buttons centred between.
     * The colour says which group a button belongs to before its name is read.
     */
    protected void group(int band, org.bukkit.Material pane, String title) {
        set(band * 9, Icons.of(pane, title));
        set(band * 9 + 8, Icons.of(pane, title));
    }

    /** Either side of the head, where the eye already is. */
    static final int PRESENCE = MenuLayout.HEADER_SUBJECT - 1;
    static final int STANDING = MenuLayout.HEADER_SUBJECT + 1;

    /** Online, away or offline · their head and who they are · what is in force. */
    protected void header() {
        PlayerOverview seen = seen();
        Instant now = Instant.now();
        ZoneId zone = LogSearchService.ZONE;
        OverviewAccess access = access();
        List<String> presence = new ArrayList<>(seen.presenceLore(zone, now, access));
        if (services().vanish().isVanished(subject)) {
            presence.add("<dark_gray>Vanished");
        }
        set(PRESENCE, Icons.of(seen.presence().icon(), seen.presence().title(), presence));
        set(STANDING, Icons.of(seen.standing().icon(), seen.standing().title(), seen.standingLore(now)));
        set(MenuLayout.HEADER_SUBJECT, Icons.head(subject, "<white>" + subjectName, seen.identityLore(zone, access)));
    }
}
