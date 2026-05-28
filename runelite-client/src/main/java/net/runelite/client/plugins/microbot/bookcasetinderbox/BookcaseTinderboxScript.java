package net.runelite.client.plugins.microbot.bookcasetinderbox;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.tileitem.models.Rs2TileItemModel;
import net.runelite.client.plugins.microbot.statemachine.StateMachineScript;
import net.runelite.client.plugins.microbot.statemachine.Transition;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Bookcase Tinderbox script.
 *
 * <p>The "Old Bookshelf" gives one tinderbox per search, but ONLY when the
 * player has 0 tinderboxes in their inventory. So the cycle is:
 * <ol>
 *   <li>SEARCHING  — search the shelf; it gives 1 tinderbox (we must have 0)</li>
 *   <li>If floor already has {@value TARGET_FLOOR_COUNT} tinderboxes → jump straight to LOOTING
 *       (we hold the final 1 in inventory, don't drop it)</li>
 *   <li>Otherwise → DROPPING: drop the 1 tinderbox we just received</li>
 *   <li>Repeat DROPPING → SEARCHING until floor has 27 and we hold 1</li>
 *   <li>LOOTING   — pick up all 27 from the floor (28 total in inventory)</li>
 *   <li>BANKING   — bank all 28, then return for the next cycle</li>
 * </ol>
 */
@Slf4j
public class BookcaseTinderboxScript extends StateMachineScript<BookcaseTinderboxScript.State> {

    private static final String TINDERBOX_NAME = "Tinderbox";
    private static final String SEARCH_ACTION  = "Search";

    /** The exact tile of the Old Bookshelf that contains tinderboxes. */
    private static final WorldPoint BOOKSHELF_LOCATION = new WorldPoint(3091, 3255, 0);

    /** Door between the bookcase room and the bank. */
    private static final WorldPoint DOOR_LOCATION = new WorldPoint(3088, 3251, 0);
    private static final int DOOR_ID = 1535;
    /** Must be within this many tiles of DOOR_LOCATION before we do any door checks. */
    private static final int DOOR_APPROACH_DISTANCE = 10;

    /** How many tinderboxes to stack on the floor before banking. */
    private static final int TARGET_FLOOR_COUNT = 27;

    /** Search range in tiles for ground-item and object lookups. */
    private static final int SEARCH_RANGE = 8;

    // ── guard flags (mutated only on script thread; read by guards) ──────────
    private boolean searchDone   = false;
    private boolean dropDone     = false;
    private boolean lootDone     = false;
    private boolean bankingDone  = false;

    // ─────────────────────────────────────────────────────────────────────────

    enum State {
        SEARCHING,
        DROPPING,
        LOOTING,
        BANKING
    }

    // ── StateMachineScript contract ───────────────────────────────────────────

    @Override
    protected State initialState() {
        return State.SEARCHING;
    }

    @Override
    protected List<Transition<State>> defineTransitions() {
        return List.of(

                // Inventory full → bank everything before continuing
                Transition.<State>from(State.SEARCHING)
                        .when(Rs2Inventory::isFull, "inventoryFull")
                        .because("Inventory is full — banking before continuing")
                        .goTo(State.BANKING),

                // More than 1 tinderbox in inventory while searching → bank them first
                Transition.<State>from(State.SEARCHING)
                        .when(() -> Rs2Inventory.count(TINDERBOX_NAME) > 1, "invCount > 1")
                        .because("Too many tinderboxes in inventory — depositing before searching")
                        .goTo(State.BANKING),

                // Search gave us the final tinderbox (floor already full) → skip drop, go loot
                Transition.<State>from(State.SEARCHING)
                        .when(() -> searchDone && floorTinderboxCount() >= TARGET_FLOOR_COUNT,
                                "searchDone && floorCount >= 27")
                        .because("Floor target reached — holding last tinderbox, switching to loot")
                        .goTo(State.LOOTING),

                // Search gave us a tinderbox but floor still needs more → go drop it
                Transition.<State>from(State.SEARCHING)
                        .when(() -> searchDone && floorTinderboxCount() < TARGET_FLOOR_COUNT,
                                "searchDone && floorCount < 27")
                        .because("Tinderbox received — dropping it and searching again")
                        .goTo(State.DROPPING),

                // Dropped the tinderbox → search again to get the next one
                Transition.<State>from(State.DROPPING)
                        .when(() -> dropDone, "dropDone")
                        .because("Tinderbox dropped — searching for the next one")
                        .goTo(State.SEARCHING),

                // All floor tinderboxes picked up (or inventory full) → bank them
                Transition.<State>from(State.LOOTING)
                        .when(() -> lootDone && (floorTinderboxCount() == 0 || Rs2Inventory.isFull()),
                                "lootDone && (floorCount == 0 || inventoryFull)")
                        .because("Looting done — heading to bank")
                        .goTo(State.BANKING),

                // Banking complete → start a new cycle
                Transition.<State>from(State.BANKING)
                        .when(() -> bankingDone, "bankingDone")
                        .because("Banking done — starting new search cycle")
                        .goTo(State.SEARCHING)
        );
    }

    @Override
    protected void onState(State state) {
        switch (state) {
            case SEARCHING:
                doSearch();
                break;
            case DROPPING:
                doDrop();
                break;
            case LOOTING:
                doLoot();
                break;
            case BANKING:
                doBank();
                break;
        }
    }

    @Override
    protected void onTransition(State from, State to, String reason) {
        super.onTransition(from, to, reason);
        // Reset the flag that triggered this transition so onState can set it fresh
        searchDone  = false;
        dropDone    = false;
        lootDone    = false;
        bankingDone = false;
    }

    @Override
    protected State onError(State state, Exception e) {
        log.error("[BookcaseTinderbox] Error in state {}: {}", state, e.getMessage(), e);
        return null; // stay in current state and retry next tick
    }

    // ── State actions ─────────────────────────────────────────────────────────

    private void doSearch() {
        if (!Microbot.isLoggedIn()) return;

        // Drop the tinderbox if we're holding exactly 1 so the shelf can give us a fresh one
        if (Rs2Inventory.count(TINDERBOX_NAME) == 1) {
            log.info("[BookcaseTinderbox] Dropping 1 tinderbox before search");
            Rs2Inventory.drop(TINDERBOX_NAME);
            sleepUntil(() -> Rs2Inventory.count(TINDERBOX_NAME) == 0, 800);
        }

        log.info("[BookcaseTinderbox] Searching bookshelf at {} (floor tinderboxes: {})", BOOKSHELF_LOCATION, floorTinderboxCount());

        boolean interacted = Rs2GameObject.interact(BOOKSHELF_LOCATION, SEARCH_ACTION);
        if (!interacted) {
            // Could not reach the bookshelf — door may be closed; open it and retry next tick
            log.warn("[BookcaseTinderbox] Could not interact with bookshelf — checking door");
            openDoorIfNeeded();
            return;
        }

        searchDone = true;
    }

    private void doDrop() {
        if (!Microbot.isLoggedIn()) return;

        int invCount = Rs2Inventory.count(TINDERBOX_NAME);
        if (invCount == 0) {
            // Shelf didn't give a tinderbox — go back to SEARCHING which will check the door and retry
            log.warn("[BookcaseTinderbox] No tinderbox in inventory after search — checking door and retrying");
            dropDone = true;
            return;
        }

        log.info("[BookcaseTinderbox] Dropping tinderbox (inv: {}, floor: {})", invCount, floorTinderboxCount());

        boolean dropped = Rs2Inventory.drop(TINDERBOX_NAME);
        if (!dropped) {
            log.warn("[BookcaseTinderbox] Failed to drop tinderbox");
            return;
        }

        // Wait until inventory count decreases to confirm the drop registered
        int countBefore = invCount;
        sleepUntil(() -> Rs2Inventory.count(TINDERBOX_NAME) < countBefore, 800);

        dropDone = true;
    }

    private void doLoot() {
        if (!Microbot.isLoggedIn()) return;

        int floorCount = floorTinderboxCount();
        if (floorCount == 0) {
            log.info("[BookcaseTinderbox] No tinderboxes on floor — loot phase done");
            lootDone = true;
            return;
        }

        log.info("[BookcaseTinderbox] Looting {} tinderbox(es) from floor", floorCount);

        // Pick up every tinderbox in one rapid pass — bail early if inventory fills up
        while (floorTinderboxCount() > 0) {
            if (Rs2Inventory.isFull()) {
                log.warn("[BookcaseTinderbox] Inventory full — heading to bank early");
                break;
            }
            if (!Rs2GroundItem.loot(TINDERBOX_NAME, SEARCH_RANGE)) {
                log.warn("[BookcaseTinderbox] Could not loot tinderbox — stopping early");
                break;
            }
        }

        lootDone = true;
    }

    private void doBank() {
        if (!Microbot.isLoggedIn()) return;

        log.info("[BookcaseTinderbox] Opening bank to deposit items");

        // Try to open the bank; if it fails the door may be blocking us — open it and retry
        if (!Rs2Bank.openBank() || !sleepUntil(Rs2Bank::isOpen, 5000)) {
            log.warn("[BookcaseTinderbox] Could not open bank — checking door and retrying");
            openDoorIfNeeded();
            if (!Rs2Bank.openBank() || !sleepUntil(Rs2Bank::isOpen, 5000)) {
                log.warn("[BookcaseTinderbox] Still could not open bank — will retry next tick");
                return;
            }
        }

        // Deposit everything — clears tinderboxes and any other items that snuck in
        Rs2Bank.depositAll();
        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen(), 1500);

        log.info("[BookcaseTinderbox] Banking complete — inventory size now {}", Rs2Inventory.count());
        bankingDone = true;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Opens the door between the bookcase room and the bank if it is currently closed.
     * First guard: player must be within {@link #DOOR_APPROACH_DISTANCE} tiles of
     * {@link #DOOR_LOCATION}. If not, we walk there and return immediately — the caller
     * will retry next tick. This prevents matching a random nearby door with the same ID.
     */
    private void openDoorIfNeeded() {
        int distToDoor = Rs2Player.getWorldLocation().distanceTo(DOOR_LOCATION);
        if (distToDoor > DOOR_APPROACH_DISTANCE) {
            log.info("[Door] {} tile(s) from door — walking closer first", distToDoor);
            Rs2Walker.walkTo(DOOR_LOCATION);
            return; // retry next tick once we're actually close to the right door
        }

        TileObject door = Rs2GameObject.getTileObject(DOOR_ID);

        if (door == null) {
            log.info("[Door] Door id={} not found nearby — already open or out of range", DOOR_ID);
            return;
        }

        // Only act on the specific door tile — ignore any other object with the same ID
        if (!DOOR_LOCATION.equals(door.getWorldLocation())) {
            log.info("[Door] Found door id={} at {} — not our door (expected {}), skipping",
                    door.getId(), door.getWorldLocation(), DOOR_LOCATION);
            return;
        }

        if (!Rs2GameObject.hasAction(door, "Open")) {
            log.info("[Door] Door at {} is already open", DOOR_LOCATION);
            return;
        }

        log.info("[Door] Door is closed — opening it");
        boolean interacted = Rs2GameObject.interact(door, "Open");
        log.info("[Door] interact returned {}", interacted);

        // Wait for the character to walk up and the door to fully open (loses "Open" action)
        sleepUntil(() -> {
            TileObject d = Rs2GameObject.getTileObject(DOOR_ID);
            return d == null
                    || !DOOR_LOCATION.equals(d.getWorldLocation())
                    || !Rs2GameObject.hasAction(d, "Open");
        }, 5000);
    }

    /**
     * Count tinderboxes on the ground within {@link #SEARCH_RANGE} tiles using the
     * Queryable API (non-stackable, so entry count == item count).
     */
    private int floorTinderboxCount() {
        List<Rs2TileItemModel> items = Microbot.getRs2TileItemCache().query()
                .where(item -> TINDERBOX_NAME.equalsIgnoreCase(item.getName()))
                .within(SEARCH_RANGE)
                .toList();
        return items.size();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public boolean run() {
        log.info("[BookcaseTinderbox] Script starting");
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                step();
            } catch (Exception ex) {
                log.error("[BookcaseTinderbox] Unexpected error in scheduled loop", ex);
            }
        }, 0, 300, TimeUnit.MILLISECONDS);
        return true;
    }
}
