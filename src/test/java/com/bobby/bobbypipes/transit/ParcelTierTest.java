package com.bobby.bobbypipes.transit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParcelTierTest {

    @Test
    void energyTiersSplitOnTheirFloors() {
        assertEquals(ParcelTier.STANDARD, ParcelTier.forFe(1));
        assertEquals(ParcelTier.STANDARD, ParcelTier.forFe(ParcelTier.FE_DENSE_FLOOR - 1));
        assertEquals(ParcelTier.DENSE, ParcelTier.forFe(ParcelTier.FE_DENSE_FLOOR));
        assertEquals(ParcelTier.DENSE, ParcelTier.forFe(ParcelTier.FE_BULK_FLOOR - 1));
        assertEquals(ParcelTier.BULK, ParcelTier.forFe(ParcelTier.FE_BULK_FLOOR));
        assertEquals(ParcelTier.BULK, ParcelTier.forFe(ParcelTier.MAX_FE));
    }

    @Test
    void fluidTiersSplitOnTheirOwnFloors() {
        assertEquals(ParcelTier.STANDARD, ParcelTier.forMb(1000));
        assertEquals(ParcelTier.STANDARD, ParcelTier.forMb(ParcelTier.MB_DENSE_FLOOR - 1));
        assertEquals(ParcelTier.DENSE, ParcelTier.forMb(ParcelTier.MB_DENSE_FLOOR));
        assertEquals(ParcelTier.BULK, ParcelTier.forMb(ParcelTier.MB_BULK_FLOOR));
        assertEquals(ParcelTier.BULK, ParcelTier.forMb(ParcelTier.MAX_MB));
    }

    /**
     * A bucket of fluid is a perfectly ordinary shipment and must not read as dense just
     * because 1000 clears energy's floor - the whole reason fluid carries its own numbers.
     */
    @Test
    void aBucketIsStandard() {
        assertEquals(ParcelTier.STANDARD, ParcelTier.forMb(1000));
    }

    @Test
    void shipmentsReportTheirOwnTier() {
        assertEquals(ParcelTier.STANDARD, new EnergyShipment(500, 1L).tier());
        assertEquals(ParcelTier.BULK, new EnergyShipment(ParcelTier.FE_BULK_FLOOR, 1L).tier());
    }

    @Test
    void wireIdsAreDistinctAndNonZero() {
        assertEquals(1, ParcelTier.STANDARD.wireId());
        assertEquals(2, ParcelTier.DENSE.wireId());
        assertEquals(3, ParcelTier.BULK.wireId());
    }

    /**
     * The ceiling has to leave room for a realistic number of parcels in flight to one
     * destination without wrapping the {@code int} the ledger and the capabilities use.
     */
    @Test
    void ceilingLeavesHeadroomBelowIntMax() {
        assertTrue((long) ParcelTier.MAX_FE * 64 < Integer.MAX_VALUE,
                "64 bulk parcels inbound must still fit an int");
    }
}
