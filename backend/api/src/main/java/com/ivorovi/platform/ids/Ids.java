package com.ivorovi.platform.ids;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.UUID;

/** Identifier generation. UUIDv7 is time-ordered, which keeps B-tree indexes compact. */
public final class Ids {

    private Ids() {}

    public static UUID newId() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}
