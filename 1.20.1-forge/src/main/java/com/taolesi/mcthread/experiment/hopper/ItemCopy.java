package com.taolesi.mcthread.experiment.hopper;

public record ItemCopy(String id, int count, int maxStack) {

    public static final ItemCopy EMPTY = new ItemCopy("minecraft:air", 0, 64);

    public boolean isEmpty() {
        return count <= 0 || id == null || id.isEmpty() || "minecraft:air".equals(id);
    }

    public static ItemCopy[] empty(int size) {
        ItemCopy[] out = new ItemCopy[size];
        java.util.Arrays.fill(out, EMPTY);
        return out;
    }
}
