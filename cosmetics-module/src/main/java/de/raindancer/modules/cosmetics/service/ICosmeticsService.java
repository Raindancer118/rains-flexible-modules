package de.raindancer.modules.cosmetics.service;

import de.raindancer.modules.cosmetics.CosmeticsSettings;

/** Something that acts. Takes a fresh settings snapshot on every reload, whether it reads it or not. */
public interface ICosmeticsService {

    void settings(CosmeticsSettings settings);

    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
