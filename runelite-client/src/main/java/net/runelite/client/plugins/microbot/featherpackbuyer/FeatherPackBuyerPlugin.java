package net.runelite.client.plugins.microbot.featherpackbuyer;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import javax.inject.Inject;

@PluginDescriptor(
        name = PluginDescriptor.Default + "Feather Pack Buyer",
        description = "Buys feather packs from Gerrant in Port Sarim when price is at the target, opens them, then loops",
        tags = {"feather", "pack", "fishing", "shop", "microbot"},
        enabledByDefault = false
)
@Slf4j
public class FeatherPackBuyerPlugin extends Plugin {

    @Inject
    private FeatherPackBuyerScript script;

    @Inject
    private FeatherPackBuyerConfig config;

    @Provides
    FeatherPackBuyerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(FeatherPackBuyerConfig.class);
    }

    @Override
    protected void startUp() {
        log.info("[FeatherPackBuyer] Plugin started");
        script.run(config);
    }

    @Override
    protected void shutDown() {
        log.info("[FeatherPackBuyer] Plugin stopped");
        script.shutdown();
    }
}
