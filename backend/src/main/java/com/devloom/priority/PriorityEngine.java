package com.devloom.priority;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import org.springframework.stereotype.Component;

/**
 * Deterministic, explainable priority engine (SPEC.md §22).
 *
 * <p>Score is a transparent weighted sum: {@code Σ (weight × normalized)}. No LLM,
 * no opaqueness — the components are inspectable and the ranking is reproducible.
 * The LLM only <em>explains</em> the result elsewhere; it never sets the rank.
 */
@Component
public class PriorityEngine {

    public static final String ALGO_VERSION = "v1";

    /** Weighted-sum score for a set of signals, rounded to 2 dp for stable display. */
    public double score(List<SignalComponent> signals) {
        double raw = signals.stream().mapToDouble(SignalComponent::contribution).sum();
        return Math.round(raw * 100.0) / 100.0;
    }

    /**
     * Rank items highest-score-first. Stable: ties preserve input order.
     *
     * @param items       the things to rank
     * @param signalsOf   extracts each item's signals
     */
    public <T> List<Scored<T>> rank(List<T> items, Function<T, List<SignalComponent>> signalsOf) {
        List<Scored<T>> scored = items.stream()
                .map(it -> new Scored<>(it, score(signalsOf.apply(it))))
                .sorted(Comparator.comparingDouble(Scored<T>::score).reversed())
                .toList();
        // assign 1-based ranks
        return java.util.stream.IntStream.range(0, scored.size())
                .mapToObj(i -> scored.get(i).withRank(i + 1))
                .toList();
    }

    /** An item paired with its computed score and 1-based rank. */
    public record Scored<T>(T item, double score, int rank) {
        Scored(T item, double score) {
            this(item, score, 0);
        }
        Scored<T> withRank(int rank) {
            return new Scored<>(item, score, rank);
        }
    }
}
