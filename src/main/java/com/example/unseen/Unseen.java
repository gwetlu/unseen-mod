package com.example.unseen;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Unseen implements ModInitializer {
    public static final String MOD_ID = "unseen";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ModEntities.register();
        HorrorManager.init();
        HorrorCommand.register();
        LOGGER.info("Unseen loaded. Something is watching.");
    }
}
