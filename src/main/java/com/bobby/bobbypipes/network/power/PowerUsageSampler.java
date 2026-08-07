package com.bobby.bobbypipes.network.power;

import java.util.Arrays;

/**
 * Ring buffer of per-kind FE spends (and optional FE in) for the junction graph.
 *
 * <p>Pure data — no Minecraft types — so unit tests can exercise rollup without a level.
 */
public final class PowerUsageSampler {

    private final int capacity;
    private final int[][] byKind;
    private final int[] totalOut;
    private final int[] totalIn;
    private int head;
    private int size;
    private final int[] accumulating = new int[PowerSpendKind.VALUES.length];
    private int accumulatingIn;

    public PowerUsageSampler(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.byKind = new int[this.capacity][PowerSpendKind.VALUES.length];
        this.totalOut = new int[this.capacity];
        this.totalIn = new int[this.capacity];
    }

    public void recordSpend(PowerSpendKind kind, int fe) {
        if (fe <= 0) {
            return;
        }
        accumulating[kind.ordinal()] += fe;
    }

    public void recordInput(int fe) {
        if (fe > 0) {
            accumulatingIn += fe;
        }
    }

    /** Flushes the current window into the ring and clears accumulators. */
    public void flushSample() {
        int out = 0;
        for (int v : accumulating) {
            out += v;
        }
        System.arraycopy(accumulating, 0, byKind[head], 0, accumulating.length);
        totalOut[head] = out;
        totalIn[head] = accumulatingIn;
        head = (head + 1) % capacity;
        if (size < capacity) {
            size++;
        }
        Arrays.fill(accumulating, 0);
        accumulatingIn = 0;
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return capacity;
    }

    /** Oldest → newest. Index 0 is the oldest retained sample. */
    public Sample sampleAt(int chronologicalIndex) {
        if (chronologicalIndex < 0 || chronologicalIndex >= size) {
            throw new IndexOutOfBoundsException(chronologicalIndex);
        }
        int physical = (head - size + chronologicalIndex + capacity) % capacity;
        return new Sample(totalIn[physical], totalOut[physical], byKind[physical].clone());
    }

    public int currentAccumulatedOut() {
        int out = 0;
        for (int v : accumulating) {
            out += v;
        }
        return out;
    }

    public int currentAccumulatedIn() {
        return accumulatingIn;
    }

    public record Sample(int totalIn, int totalOut, int[] byKind) {
    }
}
