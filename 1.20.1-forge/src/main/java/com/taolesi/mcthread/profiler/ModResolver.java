package com.taolesi.mcthread.profiler;

import net.minecraftforge.fml.ModList;

import java.security.CodeSource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Attributes stack frames to mod ids.
 *
 * <p>v0.1 approach: match a class's code source location against loaded mod
 * file names. Known limitation: in the dev environment classes come from
 * build output directories, so attribution falls back to "mcthread"/"unknown";
 * in production jars attribution is exact.
 */
public final class ModResolver {

    private final Map<String, String> classCache = new ConcurrentHashMap<>();
    private volatile Map<String, String> modFileIndex;

    public String modOf(String className) {
        String cached = classCache.get(className);
        if (cached != null) {
            return cached;
        }
        String resolved = resolve(className);
        classCache.put(className, resolved);
        return resolved;
    }

    private String resolve(String className) {
        if (className.startsWith("net.minecraft.")) {
            return "minecraft";
        }
        if (className.startsWith("com.taolesi.mcthread")) {
            return "mcthread";
        }
        if (className.startsWith("net.minecraftforge.")) {
            return "forge";
        }
        try {
            Class<?> c = Class.forName(className, false, ModResolver.class.getClassLoader());
            CodeSource cs = c.getProtectionDomain().getCodeSource();
            if (cs != null) {
                String location = cs.getLocation().toString();
                for (Map.Entry<String, String> entry : modIndex().entrySet()) {
                    if (location.contains(entry.getKey())) {
                        return entry.getValue();
                    }
                }
            }
        } catch (Throwable ignored) {
            // unloadable/generated frames -> unknown
        }
        return "unknown";
    }

    private Map<String, String> modIndex() {
        Map<String, String> index = modFileIndex;
        if (index == null) {
            synchronized (this) {
                index = modFileIndex;
                if (index == null) {
                    Map<String, String> built = new HashMap<>();
                    try {
                        ModList.get().getMods().forEach(modInfo -> {
                            String fileName = modInfo.getOwningFile()
                                    .getFile().getFilePath().getFileName().toString();
                            built.put(fileName, modInfo.getModId());
                        });
                    } catch (Throwable ignored) {
                        // ModList not yet available (early load)
                    }
                    modFileIndex = index = built;
                }
            }
        }
        return index;
    }
}
