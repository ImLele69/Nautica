package it.gabrieledev.nautica.blueprint;

import it.gabrieledev.nautica.fixture.FixtureType;

import java.util.UUID;

public record FixtureEntry(FixtureType type, int dx, int dy, int dz, float yaw, UUID id) {

    public FixtureEntry withId(UUID newId) {
        return new FixtureEntry(type, dx, dy, dz, yaw, newId);
    }
}
