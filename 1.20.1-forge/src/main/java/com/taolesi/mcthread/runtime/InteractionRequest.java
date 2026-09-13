package com.taolesi.mcthread.runtime;

/**
 * A named unit of work on the interaction thread. Action logging happens
 * inside the relocated vanilla method, not here.
 */
public record InteractionRequest(String name, Runnable body) implements Runnable {

    @Override
    public void run() {
        body.run();
    }
}
