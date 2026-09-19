package com.taolesi.threadtearer.mek;

/**
 * Gates every Mekanism-targeted mixin on Mekanism class presence. When
 * Mekanism is missing the addon silently becomes a no-op (the core Thread
 * Tearer mod still runs).
 */
public final class MekMixinPlugin implements org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin {

    @Override public void onLoad(String mixinPackage) {
    }

    @Override public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return classPresent(targetClassName);
    }

    @Override public void acceptTargets(java.util.Set<String> myTargets, java.util.Set<String> otherTargets) {
    }

    @Override public java.util.List<String> getMixins() {
        return null;
    }

    @Override public void preApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass, String mixinClassName, org.spongepowered.asm.mixin.extensibility.IMixinInfo mixinInfo) {
    }

    @Override public void postApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass, String mixinClassName, org.spongepowered.asm.mixin.extensibility.IMixinInfo mixinInfo) {
    }

    private static boolean classPresent(String name) {
        String path = name.replace('.', '/') + ".class";
        return MekMixinPlugin.class.getClassLoader().getResource(path) != null;
    }
}