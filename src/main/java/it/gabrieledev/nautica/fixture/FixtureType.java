package it.gabrieledev.nautica.fixture;

import java.util.Locale;

public enum FixtureType {
    HELM,
    SEAT,
    PAD,
    PERCH;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static FixtureType byId(String id) {
        for (FixtureType t : values()) {
            if (t.id().equalsIgnoreCase(id)) {
                return t;
            }
        }
        return null;
    }
}
