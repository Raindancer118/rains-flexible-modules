package de.raindancer.modules.xaeromap.service;

import de.raindancer.modules.xaeromap.XaeroMapSettings;

/**
 * Something this module does.
 *
 * <p>Takes the settings whether or not it currently reads any of them, so the service that starts
 * reading one is not the service somebody forgot to wire a reload into — see {@code MODULE-LAYOUT.md}.
 */
public interface IXaeroMapService {

    /** A reload happened; these are the values from now on. */
    void settings(XaeroMapSettings settings);
}
