package com.github.tartaricacid.touhoulittlemaid.compat.wab;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.network.NetworkHandler;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.Locale;

/**
 * 把 WAB 发来的命令路由到具体执行器。
 * 一次性动作（status/scan/act/hold/bind 语义之外的即时动作）当帧做完；
 * 长时间的采集任务（gather）开一个 {@link WabActions.Job}，由 {@link #tick()} 每帧驱动。
 * 每个女仆同一时间只挂一个 job，新任务会先取消旧任务。
 */
public final class WabDispatcher {
    private final WabBridge bridge;
    private final Map<java.util.UUID, WabActions.Job> jobs = new HashMap<>();

    public WabDispatcher(WabBridge bridge) {
        this.bridge = bridge;
    }

    public void dispatch(WabSession from, String id, String method, JsonObject params) {
        EntityMaid maid = bridge.resolveMaid(WabJson.str(params, "maid"));
        if (method.equals("status") || method.equals("scan")) {
            if (method.equals("status") && maid != null) {
                JsonObject data = WabSensors.status(bridge, maid);
                from.sendResult(id, true, data, null, null);
            } else {
                EntityMaid scanMaid = maid == null ? bridge.ownedMaids().stream().findFirst().orElse(null) : maid;
                if (scanMaid == null) {
                    from.sendResult(id, false, null, "no_maid", "没有可以观察的女仆。");
                } else {
                    JsonObject data = WabSensors.scan(bridge, scanMaid, params);
                    from.sendResult(id, true, data, null, null);
                }
            }
            return;
        }

        if (method.equals("backup")) {
            if (maid == null) {
                from.sendResult(id, false, null, "maid_not_found", "找不到这个女仆。");
                return;
            }
            from.sendResult(id, true, WabStorage.snapshot(maid), null, null);
            return;
        }

        if (method.equals("bind")) {
            // 绑定关系由 WAB 侧维护，游戏这边只需要收到 bind 帧时更新（WabBridge.onBind），
            // 主动的 bind 命令直接回一个 ok，表示游戏端不需要额外操作。
            from.sendResult(id, true, new JsonObject(), null, null);
            return;
        }

        if (method.equals("plan")) {
            if (maid == null) {
                from.sendResult(id, false, null, "maid_not_found", "找不到这个女仆。");
                return;
            }
            JsonObject result = WabActions.plan(bridge, maid, params);
            if (result == null) {
                from.sendResult(id, true, new JsonObject(), null, null);
            } else {
                boolean okResult = result.get("ok") != null && result.get("ok").getAsBoolean();
                from.sendResult(id, okResult,
                        result.get("data") != null ? result.getAsJsonObject("data") : null,
                        result.get("code") != null ? result.get("code").getAsString() : null,
                        result.get("error") != null ? result.get("error").getAsString() : null);
            }
            return;
        }

        if (method.equals("chat")) {
            // 聊天由 WAB 侧驱动，游戏这边只负责把话递过去（WabBridge.onChat），不处理这里的命令。
            from.sendResult(id, true, new JsonObject(), null, null);
            return;
        }

        if (method.equals("go") || method.equals("act") || method.equals("attack")
                || method.equals("gather") || method.equals("hold")) {
            if (!bridge.allowWorldActions() && method.equals("gather")) {
                from.sendResult(id, false, null, "world_actions_disabled", "游戏端没有开放世界操作权限。");
                return;
            }
            if (maid == null) {
                from.sendResult(id, false, null, "maid_not_found", "找不到这个女仆。");
                return;
            }
            switch (method) {
                case "go" -> {
                    JsonObject result = WabActions.go(bridge, maid, params);
                    if (result == null) from.sendResult(id, true, new JsonObject(), null, null);
                    else from.sendResult(id, false, null, result.get("code").getAsString(),
                            result.get("error").getAsString());
                }
                case "act" -> {
                    JsonObject result = WabActions.act(bridge, maid, params);
                    if (result == null) from.sendResult(id, true, new JsonObject(), null, null);
                    else from.sendResult(id, false, null, result.get("code").getAsString(),
                            result.get("error").getAsString());
                }
                case "attack" -> {
                    JsonObject result = WabActions.attack(bridge, maid, params);
                    if (result == null) from.sendResult(id, true, new JsonObject(), null, null);
                    else from.sendResult(id, false, null, result.get("code").getAsString(),
                            result.get("error").getAsString());
                }
                case "gather" -> {
                    String gatherAction = WabJson.str(params, "action").toLowerCase(Locale.ROOT);
                    if ("collect".equals(gatherAction)) {
                        JsonObject result = WabActions.collect(bridge, maid, params);
                        if (result == null) from.sendResult(id, true, new JsonObject(), null, null);
                        else from.sendResult(id, false, null, result.get("code").getAsString(),
                                result.get("error").getAsString());
                        return;
                    }
                    if ("fill".equals(gatherAction)) {
                        JsonObject result = WabActions.fill(bridge, maid, params);
                        if (result == null) from.sendResult(id, true, new JsonObject(), null, null);
                        else from.sendResult(id, false, null, result.get("code").getAsString(),
                                result.get("error").getAsString());
                        return;
                    }
                    cancelJob(maid, "被新的采集任务取代");
                    WabActions.Responder responder = new WabActions.Responder() {
                        @Override
                        public void success(JsonObject data) {
                            from.sendResult(id, true, data, null, null);
                        }

                        @Override
                        public void failure(String code, String message) {
                            from.sendResult(id, false, null, code, message);
                        }
                    };
                    WabActions.Job job = WabActions.startGather(bridge, maid, params, 500, false, responder);
                    jobs.put(maid.getUUID(), job);
                }
                case "hold" -> {
                    JsonObject result = WabActions.hold(bridge, maid, params);
                    if (result == null) from.sendResult(id, true, new JsonObject(), null, null);
                    else from.sendResult(id, false, null, result.get("code").getAsString(),
                            result.get("error").getAsString());
                }
                default -> from.sendResult(id, false, null, "unknown_method", "不认识的方法：" + method);
            }
            return;
        }

        from.sendResult(id, false, null, "unknown_method", "不认识的方法：" + method);
    }

    public boolean hasJob(EntityMaid maid) {
        WabActions.Job job = jobs.get(maid.getUUID());
        return job != null && job.isActive();
    }

    public void noteJob(WabActions.Job job) {
        jobs.put(job.maid.getUUID(), job);
    }

    public void cancelJob(EntityMaid maid, String reason) {
        WabActions.Job job = jobs.remove(maid.getUUID());
        if (job != null) job.cancel(reason);
    }

    public void cancelAll(String reason) {
        for (java.util.UUID key : new java.util.ArrayList<>(jobs.keySet())) {
            EntityMaid maid = bridge.ownedMaids().stream()
                    .filter(m -> m.getUUID().equals(key)).findFirst().orElse(null);
            if (maid != null) cancelJob(maid, reason);
            jobs.remove(key);
        }
    }

    public void tick() {
        for (java.util.UUID key : new java.util.ArrayList<>(jobs.keySet())) {
            WabActions.Job job = jobs.get(key);
            if (job == null || !job.isActive()) {
                jobs.remove(key);
                continue;
            }
            job.tick();
            if (!job.isActive()) {
                jobs.remove(key);
            }
        }
    }
}
