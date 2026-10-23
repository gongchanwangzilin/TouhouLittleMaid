package com.github.tartaricacid.touhoulittlemaid.compat.wab;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;

/**
 * 对「车万女仆：爱憎分明（Love & Loathe）」的兼容层。
 * 那个模组是运行时可选的：这里全部走反射调用，不硬依赖它的类，
 * 没装的时候这些调用会安静地返回 null / 走原生逻辑，不会影响桥本身。
 */
public final class WabCompat {
    private static final String ACTIONS_PACKAGE = "com.github.tartaricacid.callresponse.compat.broadcast.actions.";
    private static final Map<String, String> GESTURE_ALIASES = Map.ofEntries(
            Map.entry("wave", "swing_hand"),
            Map.entry("jump", "jump"),
            Map.entry("cheer", "game_win"),
            Map.entry("lose", "game_lost"),
            Map.entry("hurt", "attacked"),
            Map.entry("sit", "sit"),
            Map.entry("sleep", "sleep"),
            Map.entry("eat", "use_mainhand"),
            Map.entry("walk", "walk"),
            Map.entry("swim", "swim"),
            Map.entry("idle", "idle"));

    private WabCompat() {
    }

    /**
     * 把一个抽象的手势名映射到 GeckoMaid 模型里真实存在的动画名。
     * 认不出的名字原样透传，交给 GeckoLib 自己去查——查不到就安静地停在当前姿态，不会崩。
     */
    public static String animationForGesture(String arg) {
        if (arg == null || arg.isEmpty()) return "swing_hand";
        String normalized = arg.toLowerCase(Locale.ROOT);
        return GESTURE_ALIASES.getOrDefault(normalized, normalized);
    }

    /**
     * 触发爱憎分明模组里的玩家发起动作（坐下、站起来、走向主人取食物……）。
     * 返回一行人类可读的结果，供上层拼进事件描述里。
     */
    public static String invokeAddonAction(WabBridge bridge, EntityMaid maid, String arg) {
        String name = arg == null ? "" : arg.toLowerCase(Locale.ROOT);
        String className;
        switch (name) {
            case "sit_down", "sit" -> className = "SitDownAction";
            case "stand_up", "stand" -> className = "StandUpAction";
            case "walk_to_owner_and_sit" -> className = "WalkToOwnerAndSitAction";
            case "walk_to_owner_and_take_food", "take_food" -> className = "WalkToOwnerAndTakeFoodAction";
            case "attack_other_maid" -> className = "AttackOtherMaidAction";
            case "stop_attack" -> className = "StopAttackAction";
            default -> {
                return "不认识的动作，可能是没有安装「爱憎分明」。";
            }
        }
        try {
            Class<?> clazz = Class.forName(ACTIONS_PACKAGE + className);
            Method execute = clazz.getMethod("execute", EntityMaid.class, ServerPlayer.class);
            ServerPlayer owner = bridge.currentOwner();
            execute.invoke(null, maid, owner);
            return "已经通知她：" + name;
        } catch (ClassNotFoundException e) {
            return "没找到「爱憎分明」的对应动作，可能模组没装。";
        } catch (Exception e) {
            return "动作执行失败：" + e.getMessage();
        }
    }
}
