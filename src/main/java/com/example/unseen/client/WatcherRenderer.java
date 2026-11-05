package com.example.unseen.client;

import com.example.unseen.Unseen;
import com.example.unseen.WatcherEntity;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.util.Identifier;

public class WatcherRenderer extends BipedEntityRenderer<WatcherEntity, BipedEntityModel<WatcherEntity>> {
    private static final Identifier TEXTURE = Identifier.of(Unseen.MOD_ID, "textures/entity/watcher.png");

    public WatcherRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new BipedEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER)), 0.0f);
    }

    @Override
    public Identifier getTexture(WatcherEntity entity) {
        return TEXTURE;
    }
}
