package net.runelite.client.plugins.microbot.featherpackbuyer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("featherpackbuyer")
public interface FeatherPackBuyerConfig extends Config {

    @ConfigItem(
            keyName = "buyQuantity",
            name = "Buy quantity",
            description = "How many feather packs to buy per cycle (max 10 — shop stock limit).",
            position = 0
    )
    @Range(min = 1, max = 10)
    default int buyQuantity() {
        return 10;
    }

    @ConfigItem(
            keyName = "minStock",
            name = "Minimum stock",
            description = "Only buy when Gerrant has at least this many feather packs in stock.",
            position = 1
    )
    @Range(min = 1, max = 300)
    default int minStock() {
        return 100;
    }

    @ConfigItem(
            keyName = "retryDelaySecs",
            name = "Restock wait (seconds)",
            description = "How long to wait at Gerrant's shop before re-checking stock when there are fewer packs than the minimum stock threshold.",
            position = 2
    )
    @Range(min = 1, max = 300)
    default int retryDelaySecs() {
        return 2;
    }

    @ConfigItem(
            keyName = "minCoinsToResume",
            name = "Minimum coins to resume",
            description = "Don't leave the Grand Exchange and return to Gerrant until at least this many coins are in inventory. Prevents cycling with too little gold.",
            position = 3
    )
    @Range(min = 0, max = 10_000_000)
    default int minCoinsToResume() {
        return 10_000;
    }

}
