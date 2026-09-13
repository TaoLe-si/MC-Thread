package com.taolesi.mcthread.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Skips mixins whose target class is absent so GTL-only hooks do not
 * break a vanilla-only runtime.
 */
public final class MCTMixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith("EntitySpeedTickerPartMixin")
                || mixinClassName.endsWith("MetaMachineMixin")
                || mixinClassName.endsWith("TileWirelessConnectorMixin")
                || mixinClassName.endsWith("Ae2GridMixin")
                || mixinClassName.endsWith("Ae2GridConnectionMixin")
                || mixinClassName.endsWith("Ae2NetworkCraftingProvidersMixin")
                || mixinClassName.endsWith("Ae2TickManagerServiceMixin")) {
            return classPresent(targetClassName);
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    private static boolean classPresent(String name) {
        String path = name.replace('.', '/') + ".class";
        return MCTMixinPlugin.class.getClassLoader().getResource(path) != null;
    }
}
