package com.bobby.bobbypipes.logistics.power;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Counts crouch transitions per participant and decides what each one earns.
 *
 * <p>Deliberately free of Minecraft types: the interesting parts are the edge detection and
 * the per-participant cooldown, and those are much easier to be sure of under test than in a
 * world. The first version of this got the cooldown wrong in a way no amount of reading
 * caught - a {@code Long.MIN_VALUE} sentinel made {@code now - last} overflow negative, so
 * the cooldown never elapsed and the generator never paid out once.
 *
 * @param <K> participant identity (a player UUID in game)
 */
public final class TwerkMeter<K> {

    private final int feePerTransition;
    private final int cooldownTicks;

    /** Last observed crouch state, per participant. */
    private final Map<K, Boolean> crouching = new HashMap<>();
    /** Tick of the last awarded transition, per participant. Absent means never. */
    private final Map<K, Long> lastAward = new HashMap<>();

    public TwerkMeter(int feePerTransition, int cooldownTicks) {
        this.feePerTransition = Math.max(0, feePerTransition);
        this.cooldownTicks = Math.max(0, cooldownTicks);
    }

    /**
     * Records one participant's crouch state for this tick.
     *
     * <p>Awards on a <em>change</em> only, so holding crouch earns nothing. The first sighting
     * of a participant establishes a baseline without paying, otherwise walking up already
     * crouched would be worth a free unit.
     *
     * @return FE earned by this observation, or 0
     */
    public int observe(K who, boolean isCrouching, long gameTime) {
        Boolean previous = crouching.put(who, isCrouching);
        if (previous == null || previous == isCrouching) {
            return 0;
        }
        Long last = lastAward.get(who);
        // Explicit null check rather than a sentinel: any "never" sentinel far enough in the
        // past to be safe is also far enough to overflow the subtraction.
        if (last != null && gameTime - last < cooldownTicks) {
            return 0;
        }
        lastAward.put(who, gameTime);
        return feePerTransition;
    }

    /**
     * Drops everyone not in {@code present}, so a participant who leaves and returns is
     * re-baselined rather than being paid for a crouch state that changed while away.
     */
    public void retainOnly(Set<K> present) {
        crouching.keySet().retainAll(present);
        lastAward.keySet().retainAll(present);
    }

    public void clear() {
        crouching.clear();
        lastAward.clear();
    }

    /** How many participants are currently being tracked. */
    public int tracked() {
        return crouching.size();
    }
}
