package net.runelite.client.plugins.microbot.bookcasetinderbox;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import javax.inject.Inject;
import java.awt.AWTException;

@PluginDescriptor(
        name = PluginDescriptor.Default + "Bookcase Tinderbox",
        description = "Searches a nearby bookcase, dropping a tinderbox after each search until 27 are on the floor, then loots and banks them all",
        tags = {"bookcase", "tinderbox", "microbot"},
        enabledByDefault = false
)
@Slf4j
public class BookcaseTinderboxPlugin extends Plugin {

    @Inject
    private BookcaseTinderboxScript script;

    @Override
    protected void startUp() throws AWTException {
        log.info("Bookcase Tinderbox plugin started");
        script.run();
    }

    @Override
    protected void shutDown() {
        log.info("Bookcase Tinderbox plugin stopped");
        script.shutdown();
    }
}
