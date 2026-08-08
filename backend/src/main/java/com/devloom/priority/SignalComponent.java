package com.devloom.priority;

/**
 * A single deterministic priority signal (SPEC.md §22): a normalized value in [0,1]
 * and a weight. Inspectable — the UI renders these as bars.
 *
 * @param name        human label, e.g. "review wait"
 * @param display     mono value shown in the UI, e.g. "51h"
 * @param normalized  0..1 signal strength
 * @param weight      relative importance
 */
public record SignalComponent(String name, String display, double normalized, double weight) {

    public SignalComponent {
        if (normalized < 0.0 || normalized > 1.0) {
            throw new IllegalArgumentException("normalized must be in [0,1], was " + normalized);
        }
        if (weight < 0.0) {
            throw new IllegalArgumentException("weight must be >= 0, was " + weight);
        }
    }

    /** This signal's contribution to the score. */
    public double contribution() {
        return normalized * weight;
    }
}
