package com.github.tartaricacid.touhoulittlemaid.compat.wab;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 模组侧配置。端口与允许的动作由 WAB 插件决定；这里只放“这台游戏愿不愿意参与”的开关。
 */
public final class WabConfig {
    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.BooleanValue ENABLED;
    private static final ForgeConfigSpec.BooleanValue SHOW_HUD;
    private static final ForgeConfigSpec.BooleanValue DEBUG;
    private static final ForgeConfigSpec.IntValue CHAT_RANGE;
    private static final ForgeConfigSpec.IntValue DISCOVERY_PORT;
    private static final ForgeConfigSpec.IntValue BEACON_INTERVAL_SECONDS;
    private static final ForgeConfigSpec.BooleanValue AUTONOMY_ENABLED;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("WarmAsBefore maid bridge. WAB controls a real maid body through this channel.");
        builder.push("bridge");
        ENABLED = builder.comment("Broadcast to WAB and accept its commands.")
                .define("enabled", true);
        DISCOVERY_PORT = builder.comment("UDP discovery port. Keep 27321 unless WAB is configured otherwise.")
                .defineInRange("discoveryPort", 27321, 1, 65535);
        BEACON_INTERVAL_SECONDS = builder.comment("Beacon interval while disconnected.")
                .defineInRange("beaconIntervalSeconds", 3, 1, 60);
        CHAT_RANGE = builder.comment("A plain chat line is sent to the nearest maid within this range.")
                .defineInRange("chatRange", 8, 1, 64);
        SHOW_HUD = builder.comment("Show WAB affection/trust on the action bar.")
                .define("showHud", true);
        AUTONOMY_ENABLED = builder.comment("Let scheduled and random autonomy run on the mod side.")
                .define("autonomyEnabled", true);
        DEBUG = builder.comment("Log every bridge frame.")
                .define("debug", false);
        builder.pop();
        SPEC = builder.build();
    }

    private WabConfig() {
    }

    private static boolean bool(ForgeConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return value.get();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int integer(ForgeConfigSpec.IntValue value, int fallback) {
        try {
            return value.get();
        } catch (Exception e) {
            return fallback;
        }
    }

    public static boolean enabled() {
        return bool(ENABLED, true);
    }

    public static boolean showHud() {
        return bool(SHOW_HUD, true);
    }

    public static boolean debug() {
        return bool(DEBUG, false);
    }

    public static boolean autonomyEnabled() {
        return bool(AUTONOMY_ENABLED, true);
    }

    public static int chatRange() {
        return integer(CHAT_RANGE, 8);
    }

    public static int discoveryPort() {
        return integer(DISCOVERY_PORT, 27321);
    }

    public static int beaconIntervalSeconds() {
        return integer(BEACON_INTERVAL_SECONDS, 3);
    }
}
