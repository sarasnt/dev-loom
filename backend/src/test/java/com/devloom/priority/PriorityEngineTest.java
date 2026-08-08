package com.devloom.priority;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PriorityEngineTest {

    private final PriorityEngine engine = new PriorityEngine();

    @Test
    void scoreIsWeightedSumRoundedTo2dp() {
        double s = engine.score(List.of(
                new SignalComponent("a", "", 0.5, 0.4),   // 0.20
                new SignalComponent("b", "", 1.0, 0.6))); // 0.60
        assertThat(s).isEqualTo(0.80);
    }

    @Test
    void ranksHighestScoreFirstAndAssigns1BasedRanks() {
        record Item(String id, List<SignalComponent> sig) {}
        Item low = new Item("low", List.of(new SignalComponent("x", "", 0.2, 0.5)));   // 0.10
        Item high = new Item("high", List.of(new SignalComponent("x", "", 0.9, 1.0))); // 0.90
        Item mid = new Item("mid", List.of(new SignalComponent("x", "", 0.5, 0.8)));   // 0.40

        List<PriorityEngine.Scored<Item>> ranked =
                engine.rank(List.of(low, high, mid), Item::sig);

        assertThat(ranked).extracting(r -> r.item().id()).containsExactly("high", "mid", "low");
        assertThat(ranked).extracting(PriorityEngine.Scored::rank).containsExactly(1, 2, 3);
        assertThat(ranked.getFirst().score()).isEqualTo(0.90);
    }

    @Test
    void rejectsOutOfRangeNormalized() {
        try {
            new SignalComponent("bad", "", 1.5, 1.0);
            assertThat(false).as("expected IllegalArgumentException").isTrue();
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("normalized");
        }
    }
}
