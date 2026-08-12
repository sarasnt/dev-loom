package com.devloom.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Scores how a model went about a turn, from 1.0 (clean) downwards.
 *
 * <p>This scores PROCESS, not answers. Whether an answer is correct can only be judged against a
 * known result, which the running app never has — that is what {@code eval/} is for. But a run
 * that asked for the same file three times, invented a tool that doesn't exist, burned its whole
 * step budget, or answered a question about a repository without opening a single file went wrong
 * in a way that is visible without knowing the right answer. Those are scoreable every time, on
 * real work, which is what makes this usable outside the eval fixture.
 *
 * <p>The weights are ordered by how much each behaviour actually costs an answer, judged from
 * watching these runs: answering ungrounded is the worst (it is how confabulation happens), and a
 * single repeated call is a wobble rather than a failure.
 */
public final class RunQuality {

    private RunQuality() {}

    /** A behaviour that cost the run something, and how much. */
    public record Penalty(String name, double cost, String detail) {}

    /** The score for one run: a value in [0,1] and the reasons it isn't 1. */
    public record Score(double value, List<Penalty> penalties) {

        public String summary() {
            if (penalties.isEmpty()) return "clean";
            StringBuilder sb = new StringBuilder();
            for (Penalty p : penalties) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(p.name()).append(" -").append(String.format("%.2f", p.cost()));
            }
            return sb.toString();
        }
    }

    // Costs. Kept here, named, rather than scattered as magic numbers at the call sites.
    private static final double UNGROUNDED = 0.40;   // tried to gather, every attempt failed, answered anyway
    private static final double SPUN = 0.25;         // the loop had to stop it repeating itself
    private static final double CAPPED = 0.20;       // ran out of steps mid-investigation
    private static final double UNKNOWN_TOOL = 0.15; // invented a tool name
    private static final double REPEAT = 0.10;       // asked for something it already had
    private static final double TOOL_ERROR = 0.05;   // bad arguments, missing file
    private static final double ASKED_BACK = 0.25;   // ended by asking a question nobody will read
    private static final double NO_CHANGES = 0.60;   // an edit run that edited nothing

    /**
     * @param unattended true for runs with no human on the other end (a Fleet analysis), where
     *                   ending on a question is a non-answer however good the prose is
     */
    public static Score score(ToolTelemetry tel, String answer, boolean unattended) {
        return score(tel, answer, unattended, false);
    }

    /**
     * @param expectedWrites true for an edit run. Without this the worst possible outcome scored
     *                       best: a run asked to add a file wrote the whole file into its reply,
     *                       touched nothing on disk, and came back 1.00 "clean" because it had
     *                       committed none of the faults the rubric knew about. Not doing the task
     *                       has to cost more than doing it clumsily.
     */
    public static Score score(ToolTelemetry tel, String answer, boolean unattended, boolean expectedWrites) {
        List<Penalty> penalties = new ArrayList<>();

        if (expectedWrites && tel.writes() == 0) {
            penalties.add(new Penalty("no-changes", NO_CHANGES,
                    "an edit run that wrote no files — the work, if any, is only in the reply"));
        }

        // Only when it TRIED and failed. Calling no tools at all is not a fault: the run's context
        // already carries the branch, the file list and recent commits, and plenty of real
        // questions are answerable from that. Penalising "didn't call a tool" marked those runs
        // down for being efficient — it fired on a model that answered a question correctly from
        // context, which is the behaviour we want, not the one to discourage.
        if (tel.toolCalls() > 0 && !tel.gathered()) {
            penalties.add(new Penalty("ungrounded", UNGROUNDED,
                    "every tool call failed, and it answered anyway"));
        }
        if (tel.didStopSpinning()) {
            penalties.add(new Penalty("spinning", SPUN, "had to be stopped repeating itself"));
        }
        if (tel.didHitStepCap()) {
            penalties.add(new Penalty("step-cap", CAPPED, "used all " + tel.steps() + " steps"));
        }
        if (tel.unknownTools() > 0) {
            penalties.add(new Penalty("unknown-tool", UNKNOWN_TOOL * tel.unknownTools(),
                    tel.unknownTools() + " call(s) to a tool that doesn't exist"));
        }
        if (tel.repeatedCalls() > 0) {
            penalties.add(new Penalty("repeat", REPEAT * tel.repeatedCalls(),
                    tel.repeatedCalls() + " repeated call(s)"));
        }
        if (tel.toolErrors() > 0) {
            penalties.add(new Penalty("tool-error", TOOL_ERROR * tel.toolErrors(),
                    tel.toolErrors() + " failed call(s)"));
        }
        if (unattended && endsByAsking(answer)) {
            penalties.add(new Penalty("asked-back", ASKED_BACK,
                    "ended with a question, but nothing will answer it"));
        }

        double value = 1.0;
        for (Penalty p : penalties) value -= p.cost();
        return new Score(Math.max(0.0, Math.round(value * 100) / 100.0), List.copyOf(penalties));
    }

    /**
     * Whether a reply ends by putting the work back on the reader. Deliberately narrow: a question
     * in the middle of an explanation is fine, and only the closing move is judged.
     */
    static boolean endsByAsking(String answer) {
        if (answer == null || answer.isBlank()) return false;
        String tail = answer.strip();
        tail = tail.substring(Math.max(0, tail.length() - 300)).toLowerCase();
        return tail.endsWith("?")
                || tail.contains("would you like")
                || tail.contains("let me know")
                || tail.contains("shall i ")
                || tail.contains("do you want")
                || tail.contains("how would you like");
    }
}
