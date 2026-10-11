package de.raindancer.modules.sends.store;

import de.raindancer.modules.sends.model.Send;

import java.util.List;

/** Where sends are kept between restarts. */
public interface SendStorage {

    /** Every send somebody still has a claim on. */
    List<Send> loadOpen();

    /** Writes the send as it is now; false when it could not be written. */
    boolean save(Send send);
}
