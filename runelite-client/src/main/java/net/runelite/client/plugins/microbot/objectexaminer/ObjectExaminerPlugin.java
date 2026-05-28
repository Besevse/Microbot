package net.runelite.client.plugins.microbot.objectexaminer;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Utility plugin — right-click Examine any object, NPC, or ground item and its
 * name, ID, world coordinates, and available actions are printed to the log.
 */
@PluginDescriptor(
        name = PluginDescriptor.Default + "Object Examiner",
        description = "Examine any object/NPC/item to print its name, ID, coordinates and actions to the log",
        tags = {"examine", "debug", "coordinates", "actions", "utility", "microbot"},
        enabledByDefault = false
)
@Slf4j
public class ObjectExaminerPlugin extends Plugin {

    @Inject
    private Client client;

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        if (!"Examine".equals(event.getMenuOption())) return;

        String name = Text.removeTags(event.getMenuTarget());
        int id      = event.getId();
        MenuAction action = event.getMenuAction();

        switch (action) {
            case EXAMINE_OBJECT: {
                int sceneX = event.getParam0();
                int sceneY = event.getParam1();
                WorldPoint wp = WorldPoint.fromScene(client, sceneX, sceneY, client.getTopLevelWorldView().getPlane());
                log.info("[ObjectExaminer] OBJECT | name=\"{}\" | id={} | WorldPoint({}, {}, {})",
                        name, id, wp.getX(), wp.getY(), wp.getPlane());
                ObjectComposition def = client.getObjectDefinition(id);
                if (def != null) {
                    log.info("[ObjectExaminer]   actions = {}", formatActions(def.getActions()));
                    // Also resolve imposter (e.g. open/closed door variants)
                    ObjectComposition imposter = def.getImpostor();
                    if (imposter != null && imposter.getId() != id) {
                        log.info("[ObjectExaminer]   imposter id={} actions = {}", imposter.getId(), formatActions(imposter.getActions()));
                    }
                }
                break;
            }
            case EXAMINE_NPC: {
                NPC npc = event.getMenuEntry().getNpc();
                String coordPart = "";
                if (npc != null && npc.getWorldLocation() != null) {
                    WorldPoint wp = npc.getWorldLocation();
                    coordPart = String.format(" | WorldPoint(%d, %d, %d)", wp.getX(), wp.getY(), wp.getPlane());
                }
                log.info("[ObjectExaminer] NPC    | name=\"{}\" | id={}{}", name, id, coordPart);
                NPCComposition def = client.getNpcDefinition(id);
                if (def != null) {
                    log.info("[ObjectExaminer]   actions = {}", formatActions(def.getActions()));
                }
                break;
            }
            case EXAMINE_ITEM_GROUND: {
                int sceneX = event.getParam0();
                int sceneY = event.getParam1();
                WorldPoint wp = WorldPoint.fromScene(client, sceneX, sceneY, client.getTopLevelWorldView().getPlane());
                log.info("[ObjectExaminer] ITEM   | name=\"{}\" | id={} | WorldPoint({}, {}, {})",
                        name, id, wp.getX(), wp.getY(), wp.getPlane());
                break;
            }
            case EXAMINE_ITEM: {
                log.info("[ObjectExaminer] ITEM   | name=\"{}\" | id={} | (inventory — no tile coords)", name, id);
                break;
            }
            default:
                break;
        }
    }

    private static String formatActions(String[] actions) {
        if (actions == null || actions.length == 0) return "(none)";
        return Arrays.stream(actions)
                .map(a -> a == null ? "null" : "\"" + a + "\"")
                .collect(Collectors.joining(", "));
    }
}
