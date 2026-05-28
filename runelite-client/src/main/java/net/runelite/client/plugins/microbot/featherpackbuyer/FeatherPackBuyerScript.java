package net.runelite.client.plugins.microbot.featherpackbuyer;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.statemachine.StateMachineScript;
import net.runelite.client.plugins.microbot.statemachine.Transition;
import net.runelite.client.plugins.microbot.util.grandexchange.Rs2GrandExchange;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.shop.Rs2Shop;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.world.Rs2WorldUtil;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Feather Pack Buyer script.
 *
 * <p>Cycle:
 * <ol>
 *   <li>OPEN_SHOP    — walk to Gerrant and open his shop (sells feathers at GE first if low on coins)</li>
 *   <li>CHECK_PRICE  — check current feather pack stock; if ≥ minStock go to BUY_PACKS,
 *                      otherwise go to WAIT_RETRY</li>
 *   <li>BUY_PACKS    — buy N feather packs</li>
 *   <li>OPEN_PACKS   — open all feather packs in inventory</li>
 *   <li>WAIT_RETRY   — close shop, wait retryDelaySecs, then restart from OPEN_SHOP</li>
 *   <li>SELL_FEATHERS — go to GE, sell feathers @ 3 gp, collect coins, return</li>
 * </ol>
 *
 * <p>Prerequisites: character must be in or near Port Sarim with enough coins
 * in inventory to afford the packs.
 */
@Slf4j
public class FeatherPackBuyerScript extends StateMachineScript<FeatherPackBuyerScript.State> {

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final int    GERRANT_NPC_ID      = 1302;
    private static final String GERRANT_NAME        = "Gerrant";
    private static final String FEATHER_PACK_NAME   = "Feather pack";
    private static final int    FEATHER_PACK_ITEM_ID = ItemID.PACK_FEATHER; // 11881
    private static final String FEATHER_NAME         = "Feather";
    private static final String COINS_NAME           = "Coins";
    /** Minimum coins required to attempt a buy cycle; below this we sell feathers first. */
    private static final int    MIN_COINS            = 200;

    /** WorldPoint for Gerrant's shop entrance in Port Sarim. */
    private static final WorldPoint GERRANT_LOCATION = new WorldPoint(3011, 3225, 0);

    // ── State ─────────────────────────────────────────────────────────────────
    enum State {
        OPEN_SHOP,
        CHECK_PRICE,
        BUY_PACKS,
        OPEN_PACKS,
        HOP_WORLD,
        SELL_FEATHERS
    }

    // ── Flags ─────────────────────────────────────────────────────────────────
    private boolean shopOpened       = false;
    private boolean priceOk          = false;
    private boolean priceTooHigh     = false;
    private boolean buyDone          = false;
    private boolean openDone         = false;
    private boolean hopDone          = false;
    private boolean lowOnCoins       = false;
    private boolean sellFeathersDone = false;

    /** Internal phase for SELL_FEATHERS: 0 = place offer, 1 = wait, 2 = collect. */
    private int  sellPhase        = 0;
    /** Timestamp (ms) when the sell offer was placed. */
    private long sellWaitStartMs    = 0;
    /** Randomised maximum wait duration (ms) for the current phase-1 wait. */
    private long sellWaitDurationMs = 30_000;

    private FeatherPackBuyerConfig config;

    // ── StateMachineScript contract ───────────────────────────────────────────

    @Override
    protected State initialState() {
        return State.OPEN_SHOP;
    }

    @Override
    protected List<Transition<State>> defineTransitions() {
        return List.of(

                // Not enough coins → sell feathers at GE before trying to buy
                Transition.<State>from(State.OPEN_SHOP)
                        .when(() -> lowOnCoins, "lowOnCoins")
                        .because("Fewer than " + MIN_COINS + " coins — going to GE to sell feathers")
                        .goTo(State.SELL_FEATHERS),

                // Shop opened → check price
                Transition.<State>from(State.OPEN_SHOP)
                        .when(() -> shopOpened, "shopOpened")
                        .because("Shop is open — checking feather pack price")
                        .goTo(State.CHECK_PRICE),

                // Price is acceptable → buy
                Transition.<State>from(State.CHECK_PRICE)
                        .when(() -> priceOk, "priceOk")
                        .because("Price is at or below target — buying packs")
                        .goTo(State.BUY_PACKS),

                // Stock too low → hop to another world
                Transition.<State>from(State.CHECK_PRICE)
                        .when(() -> priceTooHigh, "priceTooHigh")
                        .because("Stock below minimum — hopping to next world")
                        .goTo(State.HOP_WORLD),

                // Buying complete → open packs
                Transition.<State>from(State.BUY_PACKS)
                        .when(() -> buyDone, "buyDone")
                        .because("Packs purchased — opening them")
                        .goTo(State.OPEN_PACKS),

                // All packs opened → loop back
                Transition.<State>from(State.OPEN_PACKS)
                        .when(() -> openDone, "openDone")
                        .because("All feather packs opened — starting next cycle")
                        .goTo(State.OPEN_SHOP),

                // World hop complete → re-open shop on new world
                Transition.<State>from(State.HOP_WORLD)
                        .when(() -> hopDone, "hopDone")
                        .because("World hop complete — re-checking shop on new world")
                        .goTo(State.OPEN_SHOP),

                // Feathers sold and coins collected → resume buying
                Transition.<State>from(State.SELL_FEATHERS)
                        .when(() -> sellFeathersDone, "sellFeathersDone")
                        .because("Feathers sold and coins collected — returning to shop")
                        .goTo(State.OPEN_SHOP)
        );
    }

    @Override
    protected void onState(State state) {
        switch (state) {
            case OPEN_SHOP:     doOpenShop();     break;
            case CHECK_PRICE:   doCheckPrice();   break;
            case BUY_PACKS:     doBuyPacks();     break;
            case OPEN_PACKS:    doOpenPacks();    break;
            case HOP_WORLD:     doHopWorld();     break;
            case SELL_FEATHERS: doSellFeathers(); break;
        }
    }

    @Override
    protected void onTransition(State from, State to, String reason) {
        super.onTransition(from, to, reason);
        shopOpened       = false;
        priceOk          = false;
        priceTooHigh     = false;
        buyDone          = false;
        openDone         = false;
        hopDone          = false;
        lowOnCoins       = false;
        sellFeathersDone = false;
        sellPhase        = 0;
        sellWaitStartMs  = 0;
    }

    @Override
    protected State onError(State state, Exception e) {
        log.error("[FeatherPackBuyer] Error in state {}: {}", state, e.getMessage(), e);
        return null; // stay and retry
    }

    // ── State actions ─────────────────────────────────────────────────────────

    /**
     * Walks to Gerrant (if needed) and opens his shop via the "Trade" action.
     */
    private void doOpenShop() {
        if (!Microbot.isLoggedIn()) return;

        // Not enough coins to buy even one feather pack — go sell feathers first
        int coins = Rs2Inventory.itemQuantity(COINS_NAME);
        if (coins < MIN_COINS) {
            log.info("[FeatherPackBuyer] Only {} coins (need {}+) — going to GE to sell feathers", coins, MIN_COINS);
            lowOnCoins = true;
            return;
        }

        Microbot.status = "Opening Gerrant's shop";

        if (Rs2Shop.isOpen()) {
            log.info("[FeatherPackBuyer] Shop already open");
            shopOpened = true;
            return;
        }

        // Only walk if we're genuinely far from his spawn — Gerrant wanders a few
        // tiles so we might be standing right next to him even if the NPC cache
        // hasn't resolved him yet.
        int distToSpawn = Rs2Player.getWorldLocation().distanceTo(GERRANT_LOCATION);
        if (distToSpawn > 8) {
            log.info("[FeatherPackBuyer] Too far from Gerrant ({} tiles) — walking to spawn", distToSpawn);
            Rs2Walker.walkTo(GERRANT_LOCATION);
            sleepUntil(() -> Rs2Player.getWorldLocation().distanceTo(GERRANT_LOCATION) <= 8, Rs2Random.between(13_000, 18_000));
        }

        boolean opened = Rs2Shop.openShop(GERRANT_NAME, true);
        if (!opened) {
            log.warn("[FeatherPackBuyer] Could not open shop — will retry");
            return;
        }

        // Give the item container a tick to populate shopItems
        sleepUntil(() -> !Rs2Shop.shopItems.isEmpty(), Rs2Random.between(1_700, 2_600));
        log.info("[FeatherPackBuyer] Shop opened, {} item(s) loaded", Rs2Shop.shopItems.size());
        shopOpened = true;
    }

    /**
     * Checks the current feather pack stock in Gerrant's shop.
     * Buys when stock ≥ {@code config.minStock()}, otherwise waits for restock.
     */
    private void doCheckPrice() {
        if (!Microbot.isLoggedIn()) return;
        Microbot.status = "Checking feather pack stock";

        Rs2ItemModel shopItem = Rs2Shop.shopItems.stream()
                .filter(i -> FEATHER_PACK_NAME.equalsIgnoreCase(i.getName()))
                .findFirst()
                .orElse(null);

        if (shopItem == null) {
            log.warn("[FeatherPackBuyer] Feather pack not found in shop items — re-opening shop");
            shopOpened = false;
            priceTooHigh = true;
            return;
        }

        int stock    = shopItem.getQuantity();
        int minStock = config.minStock();
        log.info("[FeatherPackBuyer] Feather pack stock: {} (need {}+)", stock, minStock);

        if (stock >= minStock) {
            priceOk = true;
        } else {
            priceTooHigh = true;
        }
    }

    /**
     * Buys the configured number of feather packs from the open shop.
     * Expects the shop to already be open.
     */
    private void doBuyPacks() {
        if (!Microbot.isLoggedIn()) return;
        Microbot.status = "Buying feather packs";

        if (!Rs2Shop.isOpen()) {
            log.warn("[FeatherPackBuyer] Shop closed unexpectedly — re-opening");
            shopOpened = false;
            return;
        }

        if (!Rs2Shop.hasMinimumStock(FEATHER_PACK_NAME, config.buyQuantity())) {
            log.warn("[FeatherPackBuyer] Not enough feather packs in stock ({} needed)", config.buyQuantity());
            // Wait for restock
            priceTooHigh = false;
            buyDone = true; // skip to open packs (inventory might have some from earlier)
            return;
        }

        log.info("[FeatherPackBuyer] Buying {} feather pack(s)", config.buyQuantity());

        int qty = config.buyQuantity();
        int packsBefore = Rs2Inventory.count(FEATHER_PACK_NAME);

        // Buy in the largest available denominations (10 → 5 → 1)
        int[] denominations = {10, 5, 1};
        for (int denom : denominations) {
            while (qty >= denom) {
                Rs2Shop.buyItem(FEATHER_PACK_NAME, String.valueOf(denom));
                sleep(400, 700);
                qty -= denom;
            }
        }

        // Wait for inventory to reflect the purchase
        final int expectedTotal = packsBefore + config.buyQuantity();
        sleepUntil(() -> Rs2Inventory.count(FEATHER_PACK_NAME) >= Math.min(expectedTotal, 28), Rs2Random.between(4_200, 6_500));

        log.info("[FeatherPackBuyer] Inventory now has {} feather pack(s)", Rs2Inventory.count(FEATHER_PACK_NAME));
        buyDone = true;
    }

    /**
     * Opens all feather packs by clicking the first one once and letting the
     * game handle the rest automatically, then sets {@code openDone}.
     */
    private void doOpenPacks() {
        if (!Microbot.isLoggedIn()) return;
        Microbot.status = "Opening feather packs";

        // Close the shop once before touching inventory
        if (Rs2Shop.isOpen()) {
            Rs2Shop.closeShop();
            sleepUntil(() -> !Rs2Shop.isOpen(), Rs2Random.between(1_600, 2_700));
        }

        if (!Rs2Inventory.hasItem(FEATHER_PACK_NAME)) {
            log.info("[FeatherPackBuyer] All feather packs opened");
            openDone = true;
            return;
        }

        // One click is enough — the game auto-opens the remaining packs
        if (!Rs2Inventory.interact(FEATHER_PACK_NAME, "Open")) {
            log.warn("[FeatherPackBuyer] Could not open feather pack — will retry next tick");
            return;
        }

        // 10 packs × ~600 ms/pack + randomised buffer
        sleepUntil(() -> !Rs2Inventory.hasItem(FEATHER_PACK_NAME), Rs2Random.between(11_000, 13_000));

        // Randomised pause before checking — feels more human and avoids
        // reacting instantly if the last pack opens right at the timeout edge.
        sleep(300, 900);

        // If any packs are still present after the wait (e.g. the click was eaten
        // or the game didn't auto-continue), don't mark done — the scheduler will
        // call us again on the next tick and we'll click once more.
        if (Rs2Inventory.hasItem(FEATHER_PACK_NAME)) {
            log.warn("[FeatherPackBuyer] {} feather pack(s) still in inventory — clicking again",
                    Rs2Inventory.count(FEATHER_PACK_NAME));
        }
    }

    /**
     * Closes the shop if open, then hops to a random accessible world so the
     * shop restocks on the new world. Sets {@code hopDone} once the hop is
     * confirmed (or falls back gracefully if no world can be found).
     */
    private void doHopWorld() {
        if (!Microbot.isLoggedIn()) return;

        // Make sure the shop is closed before we leave
        if (Rs2Shop.isOpen()) {
            Rs2Shop.closeShop();
            sleepUntil(() -> !Rs2Shop.isOpen(), Rs2Random.between(1_600, 2_700));
        }

        int targetWorld = Rs2WorldUtil.getRandomAccessibleWorld();
        if (targetWorld == -1) {
            log.warn("[FeatherPackBuyer] Could not find an accessible world to hop to — continuing on current world");
            hopDone = true;
            return;
        }

        log.info("[FeatherPackBuyer] Stock below minimum — hopping to world {}", targetWorld);
        Microbot.status = "Stock below minimum — hopping to world " + targetWorld;

        sleepUntil(() -> Microbot.hopToWorld(targetWorld), 6_000);
        sleepUntil(() -> !Microbot.isHopping(), 10_000);
        sleepUntil(() -> Microbot.isLoggedIn(), 15_000);

        int landed = Rs2Player.getWorld();
        if (landed == targetWorld) {
            log.info("[FeatherPackBuyer] Successfully hopped to world {}", targetWorld);
        } else {
            log.warn("[FeatherPackBuyer] Hop may have failed — landed on world {}", landed);
        }

        // Brief pause after login so the client finishes loading the scene
        sleep(Rs2Random.between(1_500, 2_500), Rs2Random.between(2_500, 4_000));
        hopDone = true;
    }

    /**
     * Sells all feathers in the inventory at the Grand Exchange for 3 gp each,
     * waits up to 30 seconds for the offer to fill, collects the coins, then
     * sets {@code sellFeathersDone} to transition back to {@code OPEN_SHOP}.
     *
     * <p>Uses a three-phase approach (place → wait → collect) so each scheduler
     * tick does one small step rather than blocking for 30 seconds.
     */
    private void doSellFeathers() {
        if (!Microbot.isLoggedIn()) return;

        switch (sellPhase) {
            case 0: { // ── Walk to GE and place sell offer ──────────────────────
                int featherCount = Rs2Inventory.itemQuantity(FEATHER_NAME);
                if (featherCount == 0) {
                    // No feathers in inventory — maybe a previous sell offer completed and
                    // the coins are sitting uncollected in the GE. Try to collect them before
                    // giving up, so we don't loop back to OPEN_SHOP still coin-starved.
                    log.info("[FeatherPackBuyer] No feathers in inventory — checking GE for uncollected coins");
                    Microbot.status = "No feathers — collecting GE proceeds";

                    if (!Rs2GrandExchange.isOpen()) {
                        if (!Rs2GrandExchange.openExchange()) {
                            log.info("[FeatherPackBuyer] Could not open GE to collect — walking closer");
                            Rs2GrandExchange.walkToGrandExchange();
                            return;
                        }
                        sleepUntil(Rs2GrandExchange::isOpen, Rs2Random.between(4_000, 6_500));
                        if (!Rs2GrandExchange.isOpen()) {
                            log.warn("[FeatherPackBuyer] GE still not open — retrying next tick");
                            return;
                        }
                    }

                    Rs2GrandExchange.collectAllToInventory();
                    sleepUntil(() -> Rs2Inventory.itemQuantity(COINS_NAME) >= config.minCoinsToResume(), Rs2Random.between(2_500, 4_000));
                    Rs2GrandExchange.closeExchange();

                    int coinsAfter = Rs2Inventory.itemQuantity(COINS_NAME);
                    if (coinsAfter >= config.minCoinsToResume()) {
                        log.info("[FeatherPackBuyer] Collected {} coins from GE — returning to shop", coinsAfter);
                        sellFeathersDone = true;
                    } else if (Rs2GrandExchange.hasActiveSellOffer()) {
                        // An offer is still filling — keep waiting
                        log.info("[FeatherPackBuyer] Only {} coins (need {}), active sell offer — waiting for it to fill",
                                coinsAfter, config.minCoinsToResume());
                        sellWaitStartMs = System.currentTimeMillis();
                        sellPhase = 1;
                    } else {
                        log.warn("[FeatherPackBuyer] Only {} coins (need {}) and nothing more in GE — returning to shop",
                                coinsAfter, config.minCoinsToResume());
                        sellFeathersDone = true;
                    }
                    return;
                }
                Microbot.status = "Going to GE — selling " + featherCount + " feathers @ 3 gp";

                // Confirm the GE main menu is visible before attempting any offer logic.
                // sellItem() calls useGrandExchange() internally, but that can fail quietly
                // while the player is still walking — guard here so we return cleanly and
                // retry next tick rather than firing the offer screen prematurely.
                if (!Rs2GrandExchange.isOpen()) {
                    if (!Rs2GrandExchange.openExchange()) {
                        log.info("[FeatherPackBuyer] Could not open GE — walking closer");
                        Rs2GrandExchange.walkToGrandExchange();
                        return;
                    }
                    sleepUntil(Rs2GrandExchange::isOpen, 5_000);
                    if (!Rs2GrandExchange.isOpen()) {
                        log.warn("[FeatherPackBuyer] GE still not open after wait — retrying next tick");
                        return;
                    }
                }

                boolean listed = Rs2GrandExchange.sellItem(FEATHER_NAME, featherCount, 3);
                if (!listed) {
                    log.warn("[FeatherPackBuyer] Sell offer not placed — retrying next tick");
                    return;
                }
                log.info("[FeatherPackBuyer] Feather sell offer placed ({} @ 3 gp each)", featherCount);
                sellWaitStartMs = System.currentTimeMillis();
                sellWaitDurationMs = Rs2Random.between(25_000, 40_000);
                sellPhase = 1;
                break;
            }
            case 1: { // ── Wait up to 30 seconds or until the offer has sold ────
                long elapsed = System.currentTimeMillis() - sellWaitStartMs;
                long remainingSecs = Math.max(0, (sellWaitDurationMs - elapsed) / 1000);
                if (elapsed >= sellWaitDurationMs || Rs2GrandExchange.hasFinishedSellingOffers()) {
                    log.info("[FeatherPackBuyer] Feather sale done ({}s elapsed) — collecting", elapsed / 1000);
                    sellPhase = 2;
                } else {
                    Microbot.status = "Waiting for feathers to sell (" + remainingSecs + "s left)";
                }
                break;
            }
            case 2: { // ── Collect coins to inventory and return to buying ───────
                Microbot.status = "Collecting feather sale proceeds";
                Rs2GrandExchange.collectAllToInventory();
                sleepUntil(() -> Rs2Inventory.itemQuantity(COINS_NAME) >= config.minCoinsToResume(), 3000);
                Rs2GrandExchange.closeExchange();

                int coins = Rs2Inventory.itemQuantity(COINS_NAME);
                if (coins >= config.minCoinsToResume()) {
                    log.info("[FeatherPackBuyer] {} coins collected — returning to Gerrant", coins);
                    sellPhase = 0;
                    sellFeathersDone = true;
                } else if (Rs2GrandExchange.hasActiveSellOffer()) {
                    // Offer is still partially filling — loop back and wait for more
                    log.info("[FeatherPackBuyer] Only {} coins (need {}), active sell offer — waiting for it to fill",
                            coins, config.minCoinsToResume());
                    sellWaitStartMs = System.currentTimeMillis();
                    sellWaitDurationMs = Rs2Random.between(25_000, 40_000);
                    sellPhase = 1;
                } else {
                    log.warn("[FeatherPackBuyer] Only {} coins (need {}) and no more active offers — returning to Gerrant anyway",
                            coins, config.minCoinsToResume());
                    sellPhase = 0;
                    sellFeathersDone = true;
                }
                break;
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Starts the script. Called from {@link FeatherPackBuyerPlugin#startUp()}.
     *
     * @param config plugin configuration
     * @return {@code true} if the scheduler started successfully
     */
    public boolean run(FeatherPackBuyerConfig config) {
        this.config = config;
        log.info("[FeatherPackBuyer] Starting (qty={}, minStock={}, retryDelay={}s)",
                config.buyQuantity(), config.minStock(), config.retryDelaySecs());

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                step();
            } catch (Exception ex) {
                log.error("[FeatherPackBuyer] Unexpected error in loop", ex);
            }
        }, 0, 300, TimeUnit.MILLISECONDS);

        return true;
    }
}
