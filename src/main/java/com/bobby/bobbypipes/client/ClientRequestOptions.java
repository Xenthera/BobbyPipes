package com.bobby.bobbypipes.client;

/**
 * Per-client request screen options, remembered across screen opens.
 *
 * <p>Request pipes have no block entity, so there is nowhere on the block to persist a
 * setting. These live on the client and ride along with each request packet instead, which
 * keeps the toggle a property of "how I am asking" rather than of the pipe. Two players may
 * therefore hold different settings for the same pipe, which is the intended trade: the
 * alternative is world save data on three block types.
 *
 * <p>Kept per medium rather than as one shared flag: wanting all-or-nothing on a big craft
 * says nothing about wanting it on a bucket of lava.
 */
public final class ClientRequestOptions {

    /**
     * Whether a request may ship less than asked for.
     *
     * <p>Defaults to true, which is the behaviour that existed before the toggle: take
     * whatever the network can source right now.
     */
    private static boolean itemAllowPartial = true;
    private static boolean energyAllowPartial = true;
    private static boolean fluidAllowPartial = true;

    private ClientRequestOptions() {
    }

    public static boolean itemAllowPartial() {
        return itemAllowPartial;
    }

    public static boolean toggleItemAllowPartial() {
        itemAllowPartial = !itemAllowPartial;
        return itemAllowPartial;
    }

    public static boolean energyAllowPartial() {
        return energyAllowPartial;
    }

    public static boolean toggleEnergyAllowPartial() {
        energyAllowPartial = !energyAllowPartial;
        return energyAllowPartial;
    }

    public static boolean fluidAllowPartial() {
        return fluidAllowPartial;
    }

    public static boolean toggleFluidAllowPartial() {
        fluidAllowPartial = !fluidAllowPartial;
        return fluidAllowPartial;
    }
}
