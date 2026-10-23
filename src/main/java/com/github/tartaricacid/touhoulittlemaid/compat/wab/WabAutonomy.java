package com.github.tartaricacid.touhoulittlemaid.compat.wab;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 游戏侧的日程与随机自发行为。
 * 日程（WabPlan）和自发的设置（WabAutonomy）都写进女仆的持久化数据，跟着存档走，
 * 不需要 WAB 每分钟都盯着；WAB 只在想要介入时用 `maid_plan` 改写或清空。
 */
public final class WabAutonomy {
    private final WabBridge bridge;
    private final Random random = new Random();
    private final Map<UUID, Map<String, Long>> lastStepRun = new HashMap<>();
    private final Map<UUID, Long> lastIdleAt = new HashMap<>();
    private int tickCounter;

    public WabAutonomy(WabBridge bridge) {
        this.bridge = bridge;
    }

    public void notePlanChanged(EntityMaid maid) {
        lastStepRun.remove(maid.getUUID());
    }

    public void tick() {
        if (!WabConfig.autonomyEnabled()) return;
        tickCounter++;
        if (tickCounter % 40 != 0) return;
        for (EntityMaid maid : bridge.ownedMaids()) {
            if (bridge.dispatcher().hasJob(maid) || !maid.level().isLoaded(maid.blockPosition())) continue;
            runPlan(maid);
            maybeIdle(maid);
        }
    }

    private void runPlan(EntityMaid maid) {
        String raw = maid.getPersistentData().getString("WabPlan");
        if (raw.isEmpty()) return;
        JsonArray steps;
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (parsed.isJsonArray()) {
                steps = parsed.getAsJsonArray();
            } else if (parsed.isJsonObject()) {
                JsonObject obj = parsed.getAsJsonObject();
                steps = obj.has("steps") ? obj.getAsJsonArray("steps") : new JsonArray();
            } else {
                return;
            }
        } catch (Exception e) {
            return;
        }
        if (steps.isEmpty()) return;
        String now = WabSensors.clock(maid.level().getDayTime());
        long day = maid.level().getDayTime() / 24000L;
        Map<String, Long> stepDays = lastStepRun.computeIfAbsent(maid.getUUID(), k -> new HashMap<>());

        for (int i = 0; i < steps.size(); i++) {
            if (!steps.get(i).isJsonObject()) continue;
            JsonObject step = steps.get(i).getAsJsonObject();
            String at = WabJson.str(step, "at");
            double probability = WabJson.number(step, "random", 1.0);
            if (!at.equals(now)) continue;
            Long lastDay = stepDays.get(String.valueOf(i));
            if (lastDay != null && lastDay == day) continue;
            if (random.nextDouble() > probability) continue;
            stepDays.put(String.valueOf(i), day);
            executeStep(maid, step);
        }
    }

    private void executeStep(EntityMaid maid, JsonObject step) {
        String doWhat = WabJson.str(step, "do").toLowerCase(Locale.ROOT);
        JsonObject params = new JsonObject();
        params.addProperty("maid", maid.getUUID().toString());
        switch (doWhat) {
            case "wander", "home", "follow" -> {
                params.addProperty("mode", doWhat);
                WabActions.go(bridge, maid, params);
            }
            case "mine", "chop", "harvest", "scoop" -> {
                params.addProperty("action", doWhat);
                params.addProperty("filter", WabJson.str(step, "filter"));
                params.addProperty("count", String.valueOf(WabJson.integer(step, "count", 16)));
                WabActions.Job job = WabActions.startGather(bridge, maid, params, 1200, true, null);
                bridge.dispatcher().noteJob(job);
            }
            case "collect" -> {
                params.addProperty("filter", WabJson.str(step, "filter"));
                params.addProperty("count", String.valueOf(WabJson.integer(step, "count", 16)));
                WabActions.collect(bridge, maid, params);
            }
            case "gesture", "say", "sit", "stand", "sleep", "look" -> {
                params.addProperty("action", doWhat);
                params.addProperty("arg", WabJson.str(step, "arg"));
                params.addProperty("text", WabJson.str(step, "text"));
                params.addProperty("target", WabJson.str(step, "target"));
                params.addProperty("duration", String.valueOf(WabJson.number(step, "duration", 2.0)));
                WabActions.act(bridge, maid, params);
            }
            case "feed_player" -> {
                params.addProperty("action", "feed_player");
                params.addProperty("item", WabJson.str(step, "item"));
                WabActions.hold(bridge, maid, params);
            }
            default -> {
            }
        }
    }

    private void maybeIdle(EntityMaid maid) {
        if (bridge.dispatcher().hasJob(maid)) return;
        long now = System.currentTimeMillis();
        Long last = lastIdleAt.get(maid.getUUID());
        if (last != null && now - last < 300000) return;
        if (random.nextBoolean()) {
            lastIdleAt.put(maid.getUUID(), now);
            String action = switch (random.nextInt(4)) {
                case 0 -> "wander";
                case 1 -> "gesture";
                case 2 -> "look";
                default -> "home";
            };
            JsonObject params = new JsonObject();
            params.addProperty("maid", maid.getUUID().toString());
            switch (action) {
                case "wander" -> WabActions.go(bridge, maid, params);
                case "gesture" -> {
                    params.addProperty("action", "gesture");
                    params.addProperty("arg", random.nextBoolean() ? "wave" : "hug");
                    WabActions.act(bridge, maid, params);
                }
                case "look" -> {
                    params.addProperty("action", "look");
                    WabActions.act(bridge, maid, params);
                }
                default -> WabActions.go(bridge, maid, params);
            }
        }
    }

    public void flush() {
        lastStepRun.clear();
        lastIdleAt.clear();
    }
}
