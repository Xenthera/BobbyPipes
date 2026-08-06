package com.bobby.bobbypipes.transit;

/**
 * How dense a fluid or energy parcel is.
 *
 * <p>Items ship one stack at a time and need no tier: a stack is already the natural unit.
 * Energy and fluid have no such unit, so the amount one parcel carries used to be pinned to
 * a single per-pulse constant (10k FE, one bucket), which meant moving a million FE took a
 * hundred parcels and forty seconds of pipe no matter what was on either end.
 *
 * <p>A tier is <em>not</em> configured anywhere, and nothing chooses one. It is a label read
 * off the amount a parcel actually ended up carrying, and that amount is decided by the only
 * thing that can honestly decide it: how much the source storage will hand over in a single
 * extract call. A creative energy cell answers with millions and ships {@link #BULK}; a
 * furnace generator answers with its tiny per-tick output and ships {@link #STANDARD}, no
 * matter how much the requester asked for. So a big packet is proof the network really could
 * take that energy out of one block at once, which is exactly the property that makes it
 * fair to move it in one hop.
 *
 * <p>This also means a large request degrades on its own: ask for two million FE from a
 * bank that can give half a million per pull and you get a BULK parcel, then whatever the
 * bank has recovered by the next pulse, tiering down to DENSE and STANDARD as the source
 * runs dry. No splitting logic is needed for that, it falls out of asking the storage.
 *
 * <p>Tier is cosmetic. It picks which parcel texture draws in the pipe and what the
 * goggles say about it; it does not gate which pipes a parcel may travel through, or how
 * fast it moves.
 */
public enum ParcelTier {

    /** Up to a pulse's worth. What a generator trickling into a buffer produces. */
    STANDARD,

    /** Up to a mid-size buffer's worth. A battery bank or a real tank can fill one. */
    DENSE,

    /** Everything above that. Only endgame storage hands this much over in one call. */
    BULK;

    /** FE at or above which a parcel counts as {@link #DENSE}. */
    public static final int FE_DENSE_FLOOR = 10_000;

    /** FE at or above which a parcel counts as {@link #BULK}. */
    public static final int FE_BULK_FLOOR = 500_000;

    /**
     * Most FE one parcel may carry, whatever the source could offer.
     *
     * <p>A cap has to exist or a creative cell empties into a single parcel and the pipe
     * stops being something you watch things travel down. 50x the BULK floor is high enough
     * that it never binds on anything short of creative storage, and low enough that even a
     * destination saturated with in-flight parcels cannot overflow an {@code int} of
     * outstanding promises.
     */
    public static final int MAX_FE = FE_BULK_FLOOR * 50;

    /** mB at or above which a parcel counts as {@link #DENSE}, a small tank's worth. */
    public static final int MB_DENSE_FLOOR = 32_000;

    /** mB at or above which a parcel counts as {@link #BULK}, a big tank's worth. */
    public static final int MB_BULK_FLOOR = 256_000;

    /**
     * Most mB one parcel may carry.
     *
     * <p>Fluid gets its own numbers rather than reusing the FE ones: a bucket is 1000 mB, so
     * fluid's whole useful range sits two orders of magnitude below energy's, and the FE
     * floors would put every realistic tank transfer in STANDARD forever.
     */
    public static final int MAX_MB = MB_BULK_FLOOR * 8;

    /** The tier {@code amountFe} falls in. */
    public static ParcelTier forFe(int amountFe) {
        if (amountFe >= FE_BULK_FLOOR) {
            return BULK;
        }
        return amountFe >= FE_DENSE_FLOOR ? DENSE : STANDARD;
    }

    /** The tier {@code amountMb} falls in. */
    public static ParcelTier forMb(int amountMb) {
        if (amountMb >= MB_BULK_FLOOR) {
            return BULK;
        }
        return amountMb >= MB_DENSE_FLOOR ? DENSE : STANDARD;
    }

    /** Short label for the goggles and the network command. */
    public String label() {
        return switch (this) {
            case STANDARD -> "T1";
            case DENSE -> "T2";
            case BULK -> "T3";
        };
    }

    /**
     * 1, 2 or 3, for the parcel sync payload.
     *
     * <p>Sent as a small number rather than the enum so the payload stays a plain byte and
     * item and drift entries can use 0 for "no tier".
     */
    public int wireId() {
        return ordinal() + 1;
    }
}
