package com.example.unseen;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.world.World;

/** Silent, harmless, invulnerable black figure. Fully controlled by HorrorManager. */
public class WatcherEntity extends MobEntity {
    /** Set by HorrorManager when it spawns the entity; leftovers loaded from disk are removed. */
    public boolean managed = false;

    public WatcherEntity(EntityType<? extends MobEntity> type, World world) {
        super(type, world);
        this.setSilent(true);
        this.setPersistent();
        this.setInvulnerable(true);
        this.setAiDisabled(true);
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.getWorld().isClient && !this.managed) {
            this.discard();
        }
    }

    @Override
    public boolean isPushable() {
        return false;
    }
}
