package com.bobby.bobbypipes.transit.drift;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a drifting item can go next, and how the choice is made.
 *
 * <p>Drifting items are the dumb-pipe transport: anything can push an item into a plain
 * pipe and it travels on, picking a direction at random at every junction. Nothing plans
 * its route, which is what lets a plain pipe act as a general purpose tube for any mod
 * rather than only as fabric for the logistics network.
 *
 * <p>Items only ever move forward. Reaching a pipe with no onward side ends the journey
 * there rather than sending the item back the way it came.
 *
 * <p>Kept free of Minecraft types so the choice rules can be tested directly. The world
 * facing side only has to classify each side and act on the answer.
 */
public final class DriftExit {

    /** What lies beyond one side of a pipe. */
    public enum Kind {
        /** More plain pipe: keep drifting. */
        DUMB_PIPE,
        /** A routed pipe: offer the item to the logistics network, else keep drifting. */
        SMART_PIPE,
        /** A container: put the item in. */
        INVENTORY,
        /** Nothing usable that way. */
        NONE
    }

    /**
     * One candidate direction.
     *
     * @param side   caller's own direction handle, returned untouched
     * @param kind   what is on the far side
     * @param routed whether this side is a routed connection to the logistics network,
     *               the green arm a player sees. Only routed pipes ever have one
     */
    public record Option<S>(S side, Kind kind, boolean routed) {

        public Option(S side, Kind kind) {
            this(side, kind, false);
        }
    }

    private DriftExit() {
    }

    /**
     * Picks where a drifting item goes next.
     *
     * <p>Items never turn round. The side an item arrived through is not a candidate at
     * all, so a pipe whose only neighbour is where the item came from counts as a dead end
     * and the caller puts the item down. Allowing a reversal would let items wander back
     * and forth through a network they had already failed to find an exit from.
     *
     * @param options   every side worth considering, in a stable order
     * @param cameFrom  the side the item arrived through, or null if it was just inserted
     * @param roll      uniform value in [0, 1)
     * @return the chosen option, or null for a dead end
     */
    public static <S> Option<S> choose(List<Option<S>> options, S cameFrom, float roll) {
        List<Option<S>> forward = new ArrayList<>(options.size());
        boolean anyRouted = false;
        for (Option<S> option : options) {
            if (option.kind() == Kind.NONE) {
                continue;
            }
            if (cameFrom != null && option.side().equals(cameFrom)) {
                continue;
            }
            anyRouted |= option.routed();
            forward.add(option);
        }
        if (anyRouted) {
            // A pipe that is on the network sends the item along it rather than gambling on
            // wandering off into plain pipe. Only the unrouted pipe sides drop out: an
            // inventory is somewhere to put the item down, not a worse guess at a route,
            // so it still competes.
            forward.removeIf(option -> !option.routed() && isPipe(option.kind()));
        }
        return forward.isEmpty() ? null : forward.get(index(forward.size(), roll));
    }

    private static boolean isPipe(Kind kind) {
        return kind == Kind.DUMB_PIPE || kind == Kind.SMART_PIPE;
    }

    /** Maps a roll in [0, 1) onto an index, clamped so a roll of exactly 1 is safe. */
    static int index(int size, float roll) {
        if (size <= 1) {
            return 0;
        }
        int i = (int) (roll * size);
        return Math.max(0, Math.min(size - 1, i));
    }
}
