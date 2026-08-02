package com.bobby.bobbypipes.block;

/**
 * A pipe that requests items from the network and delivers them into the inventories
 * touching it.
 *
 * <p>The screen for driving it comes with the GUI work. Until then requests are placed
 * through the debug command, which targets a request pipe by position.
 */
public class RequestPipeBlock extends PipeBlock {

    public RequestPipeBlock(Properties properties) {
        super(properties);
    }
}
