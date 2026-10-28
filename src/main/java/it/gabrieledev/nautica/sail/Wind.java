package it.gabrieledev.nautica.sail;

import it.gabrieledev.nautica.math.Rot;

// where the wind blows to (a heading) and how hard, from 0 to 1
public record Wind(float toHeading, double strength) {

    // point of sail: 1 running before the wind, falling smoothly to 0.1 head to wind.
    // Not a polar diagram, just enough for "downwind is fast, close-hauled is slow"
    public double pointOfSail(float shipHeading) {
        double angle = Math.toRadians(Math.abs(Rot.delta(toHeading, shipHeading)));
        return Math.max(0.1, 0.55 + 0.45 * Math.cos(angle));
    }
}
