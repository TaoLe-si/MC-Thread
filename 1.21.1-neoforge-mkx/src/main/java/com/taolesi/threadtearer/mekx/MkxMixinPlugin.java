package com.taolesi.threadtearer.mekx;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * The addon's mixin plugin. No-op when Mekanism (and with it Mekanism Extras)
 * is absent, so the addon can ship alongside packs that don't run either
 * mod. The {@code ifPresent} probe checks the mek main {@code @Mod} class —
 * Mekanism Extras is hard-required to depend on Mekanism, so mek being on
 * the classpath is enough to know the rest is too.
 */
public class MkxMixinPlugin implements IMixinConfigPlugin {

    /** True iff Mekanism is on the classpath (Mekanism Extras necessarily with it). */
    public static final boolean ifPresent = MkxMixinPlugin.class
            .getClassLoader()
            .getResource("mekanism/common/Mekanism.class") != null;

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