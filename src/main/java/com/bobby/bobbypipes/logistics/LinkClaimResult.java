package com.bobby.bobbypipes.logistics;

/**
 * Result of claiming or releasing a link-pipe channel slot.
 */
public enum LinkClaimResult {
    /** This endpoint now occupies the first or second slot on the channel. */
    OK,
    /** Channel already has two other endpoints; claim rejected. */
    CHANNEL_FULL,
    /** Channel id is not usable (e.g. non-positive). */
    INVALID_CHANNEL,
}
