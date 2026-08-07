package com.bobby.bobbypipes.logistics.craft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerLevel;

/**
 * Shows what a crafting pipe is doing, in the spirit of the particles Logistics Pipes puts
 * over a crafter with outstanding orders.
 *
 * <p>Chains fail in ways that leave no trace: a job can sit waiting for an ingredient that
 * will never come, or be blocked behind an upstream crafter that already gave up, and from
 * the outside both look like nothing happening. Each state gets its own particle so the
 * stalled link is obvious at a glance.
 */
public final class CraftParticles {

    /** How often to emit. Every tick would be a blizzard and would spam the network. */
    private static final int INTERVAL_TICKS = 10;

    private CraftParticles() {
    }

    /** What a crafting pipe is currently doing. */
    public enum State {

        /** Waiting on ingredients it has asked for. */
        GATHERING(ParticleTypes.WITCH, 3),

        /** Ingredients are in; waiting for the table to produce. */
        CRAFTING(ParticleTypes.HAPPY_VILLAGER, 3),

        /**
         * Cannot progress until an upstream crafter delivers.
         *
         * <p>The one worth watching for. A chain that stops with a downstream pipe stuck
         * here and nothing happening upstream tells you exactly which link broke.
         */
        BLOCKED_UPSTREAM(ParticleTypes.SMOKE, 2),

        /** Gave up: timed out, lost its pattern, or lost a satellite. */
        FAILED(ParticleTypes.ANGRY_VILLAGER, 5);

        private final SimpleParticleType particle;
        private final int count;

        State(SimpleParticleType particle, int count) {
            this.particle = particle;
            this.count = count;
        }
    }

    /** True on ticks where {@link #emit} would actually draw something. */
    public static boolean shouldEmit(long gameTime) {
        return gameTime % INTERVAL_TICKS == 0;
    }

    /**
     * Draws {@code state} just above {@code pipe}.
     *
     * <p>Emitted regardless of the tick interval, so failures are never missed; callers
     * gate the recurring states with {@link #shouldEmit}.
     */
    public static void emit(ServerLevel level, BlockPos pipe, State state) {
        level.sendParticles(
                state.particle,
                pipe.getX() + 0.5,
                pipe.getY() + 1.1,
                pipe.getZ() + 0.5,
                state.count,
                0.18, 0.05, 0.18,
                0.0);
    }
}
