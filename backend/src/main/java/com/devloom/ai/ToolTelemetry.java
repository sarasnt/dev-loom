package com.devloom.ai;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What a model actually did during one turn — the process, not the answer.
 *
 * <p>Answer quality can only be judged against a known-correct result, which the app never has at
 * runtime. Process quality can be judged always: a run that asked for the same file three times,
 * invented a tool name, or answered a question about a repository without opening it went wrong in
 * a way that is visible without knowing the right answer. That is what this records, and what
 * {@link RunQuality} turns into a score.
 */
public final class ToolTelemetry {

    private int steps;
    private int toolCalls;
    private int repeatedCalls;
    private int unknownTools;
    private int toolErrors;
    private boolean hitStepCap;
    private boolean stoppedSpinning;
    private boolean gathered;
    private int writes;
    private final Set<String> toolsUsed = new LinkedHashSet<>();

    void step() { steps++; }

    void call(String tool) {
        toolCalls++;
        toolsUsed.add(tool);
    }

    void repeated() { repeatedCalls++; }

    void unknownTool() { unknownTools++; }

    void toolError() { toolErrors++; }

    /** A tool returned real content — the model has something grounded to answer from. */
    void gatheredSomething() { gathered = true; }

    /** A file was actually written. The point of an edit run, and the thing to check it did. */
    void wroteFile() { writes++; }

    void hitStepCap() { hitStepCap = true; }

    void stoppedSpinning() { stoppedSpinning = true; }

    public int steps() { return steps; }
    public int toolCalls() { return toolCalls; }
    public int repeatedCalls() { return repeatedCalls; }
    public int unknownTools() { return unknownTools; }
    public int toolErrors() { return toolErrors; }
    public boolean didHitStepCap() { return hitStepCap; }
    public boolean didStopSpinning() { return stoppedSpinning; }
    public boolean gathered() { return gathered; }
    public int writes() { return writes; }
    public Set<String> toolsUsed() { return Set.copyOf(toolsUsed); }

    /** Whether tools were available and offered at all — scoring means something different if not. */
    private boolean hadTools;
    void toolsOffered() { hadTools = true; }
    public boolean hadTools() { return hadTools; }

    @Override
    public String toString() {
        return "steps=" + steps + " calls=" + toolCalls + " repeats=" + repeatedCalls
                + " unknown=" + unknownTools + " errors=" + toolErrors
                + " capped=" + hitStepCap + " spun=" + stoppedSpinning + " gathered=" + gathered
                + " writes=" + writes;
    }
}
