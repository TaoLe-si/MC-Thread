package com.taolesi.threadtearer.ie;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * The addon's mixin plugin. No-op when Immersive Engineering is absent, so
 * the addon can ship alongside packs that don't use IE. The {@code ifPresent}
 * probe checks for IE's {@code @Mod} class — its mere presence is enough
 * to know the rest of the classpath is reachable.
 */
public class IeMixinPlugin implements IMixinConfigPlugin {

    /** True iff Immersive Engineering is on the classpath. */
    public static final boolean ifPresent = IeMixinPlugin.class
            .getClassLoader()
            .getResource("blusunrize/immersiveengineering/ImmersiveEngineering.class") != null;

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return ifPresent;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return List.of();
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}