package com.bobby.bobbypipes.transit;

import net.minecraft.core.Direction;

/**
 * What an energy parcel carries: some amount of FE, plus the promise it settles.
 *
 * <p>Unlike {@link ItemShipment} there is no resource identity to carry, energy has no
 * type, only an amount. Everything else about the shape matches items on purpose: same
 * promise-id-over-matching-by-contents reasoning, same {@code entrySide} for the renderer
 * and for {@code PipeNetwork}'s origin-hop arm timing.
 *
 * @param amountFe  how much FE this packet carries, always positive
 * @param promiseId the {@code DeliveryLedger} promise this settles
 * @param entrySide the energy storage face this packet was drawn out of, or null when it
 *                  did not come from a real capability (there is no drift equivalent for
 *                  energy, so in practice this is always set)
 */
public record EnergyShipment(int amountFe, long promiseId, Direction entrySide) {

    public EnergyShipment {
        if (amountFe <= 0) {
            throw new IllegalArgumentException("shipment amount must be positive, got " + amountFe);
        }
    }

    public EnergyShipment(int amountFe, long promiseId) {
        this(amountFe, promiseId, null);
    }

    /** Cleared when a shipment re-enters the network at a cross-dim link mouth. */
    public EnergyShipment withoutEntrySide() {
        return entrySide == null ? this : new EnergyShipment(amountFe, promiseId, null);
    }

    /**
     * How dense this packet is, derived from {@link #amountFe} rather than stored.
     *
     * <p>Nothing picks a tier and then fills it, so there is no second piece of state here
     * that could disagree with the amount. See {@link ParcelTier}.
     */
    public ParcelTier tier() {
        return ParcelTier.forFe(amountFe);
    }

    @Override
    public String toString() {
        return amountFe + " FE " + tier().label() + " (promise " + promiseId + ")";
    }
}
