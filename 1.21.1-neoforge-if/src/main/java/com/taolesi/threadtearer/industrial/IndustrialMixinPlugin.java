package com.taolesi.threadtearer.industrial;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * The addon's mixin plugin. No-op when Industrial Foregoing (and with it
 * Titanium) is absent, so the addon can ship alongside packs that don't use
 * IF. Both mixins target Titanium classes, so Titanium alone would be enough
 * to apply them — but the policy only ever admits IF classes, so an unloaded
 * IF means nothing is offloaded anyway, and skipping the transforms keeps the
 * log clean.
 */
public class IndustrialMixinPlugin implements IMixinConfigPlugin {

    /** True iff IF is on the classpath (Titanium necessarily with it). */
    public static final boolean ifPresent = IndustrialMixinPlugin.class
            .getClassLoader()
            .getResource("com/buuz135/industrial/IndustrialForegoing.class") != null;

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