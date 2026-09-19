package com.taolesi.threadtearer.enderio;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * The addon's mixin plugin. No-op when EnderIO (and with it EnderCore) is
 * absent, so the addon can ship alongside packs that don't use EnderIO.
 * Both mixins target EnderIO/EnderCore classes — the {@code ifPresent} probe
 * checks the EnderIO mod first, then the EnderCore base, since either
 * alone is enough to confirm the classpath.
 */
public class EnderIOMixinPlugin implements IMixinConfigPlugin {

    /** True iff EnderIO is on the classpath (EnderCore necessarily with it on 9.x). */
    public static final boolean ifPresent = EnderIOMixinPlugin.class
            .getClassLoader()
            .getResource("com/enderio/enderio/EnderIO.class") != null;

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