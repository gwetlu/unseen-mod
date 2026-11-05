package com.example.unseen;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModEntities {
    public static final EntityType<WatcherEntity> WATCHER = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(Unseen.MOD_ID, "watcher"),
            EntityType.Builder.create(WatcherEntity::new, SpawnGroup.MISC)
                    .dimensions(0.6f, 1.8f)
                    .build("watcher"));

    private ModEntities() {}

    public static void register() {
        FabricDefaultAttributeRegistry.register(WATCHER, MobEntity.createMobAttributes());
    }
}
