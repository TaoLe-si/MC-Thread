package com.taolesi.threadtearer.experiment;

import java.util.ArrayList;
import java.util.List;

/**
 * While an interaction request is applying, outbound packets are queued.
 * After the vanilla+Forge+capability stack finishes, they are flushed in order.
 */
public final class InteractionDispatch {

    private static final ThreadLocal<List<Runnable>> PENDING = new ThreadLocal<>();

    private InteractionDispatch() {
    }

    static void begin() {
        PENDING.set(new ArrayList<>());
    }

    static void flush() {
        List<Runnable> queued = PENDING.get();
        PENDING.remove();
        if (queued == null || queued.isEmpty()) {
            return;
        }
        for (Runnable send : queued) {
            send.run();
        }
    }

    public static boolean defer(String packetName, Runnable send) {
        List<Runnable> queued = PENDING.get();
        if (queued == null) {
            return false;
        }
        queued.add(send);
        return true;
    }
}
