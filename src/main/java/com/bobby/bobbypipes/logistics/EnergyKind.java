package com.bobby.bobbypipes.logistics;

/**
 * Stand-in "item identity" for energy promises.
 *
 * <p>{@link com.bobby.bobbypipes.request.DeliveryLedger} is generic over what is being
 * promised so it can tell one open promise from another by matching on that identity.
 * Items have a real identity (an {@code ItemResource}); energy does not, there is only one
 * kind of it. This single-value enum fills that generic slot without inventing a fake item
 * type, every energy promise on a level uses the same {@link #ENERGY} constant.
 */
public enum EnergyKind {
    ENERGY
}
