package de.raindancer.modules.oneblock.service;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.modules.oneblock.model.Content;
import de.raindancer.modules.oneblock.store.ContentStore;
import de.raindancer.modules.oneblock.store.IslandBook;
import de.raindancer.modules.oneblock.store.IslandStorage;

/** Everything only the island server has: the islands, their worlds and the block. */
public record IslandServer(IslandBook book, IslandStorage storage, ContentStore content, IslandWorlds worlds,
                           IslandTravel travel, IslandService islands, OneBlockService oneBlock,
                           IslandLifecycle lifecycle, AdminService admin, OwnIslands host, Brand brand) {

    public Content currentContent() {
        return content.current();
    }
}
