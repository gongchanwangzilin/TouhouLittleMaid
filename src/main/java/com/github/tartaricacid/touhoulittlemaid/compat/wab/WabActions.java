package com.github.tartaricacid.touhoulittlemaid.compat.wab;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;
import net.minecraftforge.items.wrapper.InvWrapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * 桥的「手」：把 WAB 发来的动作请求落成 Minecraft 里女仆真正会做的事。
 * 一次性动作（移动、攻击、投喂、姿态、预设动画）立即执行；
 * 长时间的动作（挖矿、砍树、收割、舀水、捡拾）交给 {@link Job}，
 * 由 <b>派发器</b> 每个 tick 驱动，避免卡死服务器线程。
 */
public final class WabActions {
    private WabActions() {
    }

    public interface Responder {
        void success(JsonObject data);

        void failure(String code, String message);
    }

    // ==================== 移动 ====================

    public static JsonObject go(WabBridge bridge, EntityMaid maid, JsonObject p) {
        String mode = WabJson.str(p, "mode", "to").toLowerCase(Locale.ROOT);
        String targetName = WabJson.str(p, "target");
        double distance = WabJson.number(p, "distance", 2.0);
        List<JsonArray> posList = new ArrayList<>();
        JsonArray posArr = WabJson.arr(p, "pos");
        if (posArr.size() >= 3) {
            posList.add(posArr);
        }

        switch (mode) {
            case "to", "near" -> {
                Entity target = resolveEntity(maid, targetName, targetName.isEmpty() ? bridge.currentOwner() : null);
                if (target == null && posArr.size() >= 3) {
                    double x = posArr.get(0).getAsDouble();
                    double y = posArr.get(1).getAsDouble();
                    double z = posArr.get(2).getAsDouble();
                    double stopDistance = mode.equals("near") ? distance : 1.0;
                    if (!navigateTo(maid, x, y, z, stopDistance)) {
                        return fail("cannot_reach", "找不到可以走过去的路。");
                    }
                    ok(maid, "已出发送往 " + String.format("%.0f,%.0f,%.0f", x, y, z));
                } else if (target != null) {
                    double stopDistance = mode.equals("near") ? distance : 1.0;
                    if (mode.equals("near")) {
                        if (!navigateTo(maid, target.getX(), target.getY(), target.getZ(), stopDistance)) {
                            return fail("cannot_reach", "追不上目标了。");
                        }
                    } else if (target instanceof ServerPlayer sp) {
                        bridge.setFollow(maid, sp);
                    } else {
                        navigateTo(maid, target.getX(), target.getY(), target.getZ(), 2.0);
                    }
                    ok(maid, "正在靠近 " + target.getName().getString());
                } else {
                    return fail("invalid_target", "没有给出要靠近的目标。");
                }
            }
            case "follow" -> {
                Entity target = resolveEntity(maid, targetName.isEmpty() ? null : targetName, bridge.currentOwner());
                if (target instanceof ServerPlayer sp) {
                    bridge.setFollow(maid, sp);
                    if (maid.distanceToSqr(sp) > 9) navigateTo(maid, sp.getX(), sp.getY(), sp.getZ(), 3.0);
                    ok(maid, "现在开始跟着 " + sp.getName().getString());
                } else {
                    return fail("invalid_target", "只认得同维度的玩家，没法跟着别的生物。");
                }
            }
            case "wait" -> {
                bridge.clearFollow(maid);
                maid.getNavigation().stop();
                ok(maid, "停在原地等着。");
            }
            case "home" -> {
                BlockPos home = maid.getSchedulePos().getNearestPos(maid);
                if (home != null) {
                    navigateTo(maid, home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 1.0);
                    ok(maid, "回家。");
                } else {
                    ServerPlayer owner = bridge.currentOwner();
                    if (owner != null && owner.level() == maid.level()) {
                        navigateTo(maid, owner.getX(), owner.getY(), owner.getZ(), 2.0);
                        ok(maid, "回到主人身边。");
                    } else {
                        return fail("no_home", "没有设置过家，也找不到主人。");
                    }
                }
                break;
            }
            case "wander" -> {
                double radius = WabJson.number(p, "radius", 8.0);
                java.util.Random random = new java.util.Random();
                double angle = random.nextDouble() * Math.PI * 2;
                double x = maid.getX() + Math.cos(angle) * radius;
                double z = maid.getZ() + Math.sin(angle) * radius;
                if (!navigateTo(maid, x, maid.getY(), z, 1.0)) {
                    return fail("cannot_reach", "那边去不了。");
                }
                ok(maid, "随便逛逛。");
            }
            default -> {
                return fail("unknown_mode", "不认识的移动方式：" + mode);
            }
        }
        return null;
    }

    private static boolean navigateTo(EntityMaid maid, double x, double y, double z, double stopDistance) {
        if (!(maid.level() instanceof ServerLevel)) return false;
        return maid.getNavigation().moveTo(x, y, z, 1.0F);
    }

    private static Entity resolveEntity(EntityMaid maid, String name, Entity fallback) {
        if (name == null || name.isEmpty()) return fallback;
        if (fallback != null && fallback.getName().getString().equalsIgnoreCase(name)) return fallback;
        Level level = maid.level();
        List<Entity> candidates = level.getEntities(maid, maid.getBoundingBox().inflate(32),
                entity -> entity != maid && entity.isAlive());
        for (Entity entity : candidates) {
            if (entity.getName().getString().equalsIgnoreCase(name)) {
                return entity;
            }
        }
        return null;
    }

    // ==================== 攻击 ====================

    public static JsonObject attack(WabBridge bridge, EntityMaid maid, JsonObject p) {
        String targetName = WabJson.str(p, "target");
        Entity target = resolveEntity(maid, targetName, null);
        if (target == null) {
            target = nearestLiving(maid, 8.0);
        }
        if (target == null) {
            return fail("no_target", "附近没有可以攻击的目标。");
        }
        maid.setTarget(target instanceof LivingEntity le ? le : null);
        double distance = maid.distanceToSqr(target);
        if (distance > 3.5 * 3.5) {
            navigateTo(maid, target.getX(), target.getY(), target.getZ(), 2.0);
        }
        maid.swing(InteractionHand.MAIN_HAND);
        maid.doHurtTarget(target);
        ok(maid, "对 " + target.getName().getString() + " 出手了。");
        return null;
    }

    private static Entity nearestLiving(EntityMaid maid, double radius) {
        LivingEntity best = null;
        double bestDistance = radius * radius;
        List<Entity> candidates = maid.level().getEntities(maid, maid.getBoundingBox().inflate(radius),
                entity -> entity instanceof LivingEntity && entity.isAlive());
        for (Entity entity : candidates) {
            if (entity == maid) continue;
            LivingEntity living = (LivingEntity) entity;
            double distance = maid.distanceToSqr(living);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = living;
            }
        }
        return best;
    }

    // ==================== 手势 / 说 / 姿态 ====================

    public static JsonObject act(WabBridge bridge, EntityMaid maid, JsonObject p) {
        String action = WabJson.str(p, "action").toLowerCase(Locale.ROOT);
        double duration = WabJson.number(p, "duration", 2.0);
        int ticks = (int) Math.max(1, duration * 20);
        switch (action) {
            case "attack" -> {
                return attack(bridge, maid, p);
            }
            case "use" -> {
                String targetName = WabJson.str(p, "target");
                BlockPos pos = parsePos(WabJson.arr(p, "pos"));
                Entity target = resolveEntity(maid, targetName, null);
                if (pos != null) {
                    useBlock(maid, pos, p);
                } else if (target != null) {
                    maid.getLookControl().setLookAt(target, 30F, 30F);
                    maid.swing(InteractionHand.MAIN_HAND);
                    ok(maid, "朝着 " + target.getName().getString() + " 比划了一下。");
                } else {
                    return fail("no_target", "没有可用的使用目标。");
                }
                return null;
            }
            case "gesture" -> {
                String arg = WabJson.str(p, "arg");
                String animation = WabCompat.animationForGesture(arg.isEmpty() ? "wave" : arg);
                WabAnimationMessage message = new WabAnimationMessage(maid.getId(), animation, ticks);
                com.github.tartaricacid.touhoulittlemaid.network.NetworkHandler.sendToNearby(maid, message);
                if (arg.equals("sit")) maid.setInSittingPose(true);
                if (arg.equals("stand")) maid.setInSittingPose(false);
                ok(maid, "做了一个动作：" + arg);
                return null;
            }
            case "say" -> {
                String text = WabJson.str(p, "text");
                if (!text.isEmpty() && maid.level() instanceof ServerLevel) {
                    maid.getChatBubbleManager().addLLMChatText(text, -1);
                }
                ok(maid, "说：「" + text + "」");
                return null;
            }
            case "sit" -> {
                maid.setInSittingPose(true);
                maid.getNavigation().stop();
                ok(maid, "坐下了。");
                return null;
            }
            case "stand" -> {
                maid.setInSittingPose(false);
                ok(maid, "站了起来。");
                return null;
            }
            case "sleep" -> {
                BlockPos sleepPos = parsePos(WabJson.arr(p, "pos"));
                if (sleepPos == null) {
                    sleepPos = maid.getSchedulePos().getSleepPos();
                }
                if (sleepPos != null && maid.level().getBlockState(sleepPos).is(net.minecraft.tags.BlockTags.BEDS)) {
                    maid.startSleeping(sleepPos);
                    ok(maid, "上床睡觉了。");
                } else {
                    WabAnimationMessage message = new WabAnimationMessage(maid.getId(), "sleep", ticks);
                    com.github.tartaricacid.touhoulittlemaid.network.NetworkHandler.sendToNearby(maid, message);
                    ok(maid, "没有床，她只是闭眼休息了一会儿。");
                }
                return null;
            }
            case "look" -> {
                String targetName = WabJson.str(p, "target");
                BlockPos pos = parsePos(WabJson.arr(p, "pos"));
                Entity target = resolveEntity(maid, targetName, null);
                if (target != null) {
                    maid.getLookControl().setLookAt(target, 30F, 30F);
                } else if (pos != null) {
                    maid.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 30F, 30F);
                }
                ok(maid, "看向那边。");
                return null;
            }
            case "interact_addon" -> {
                String arg = WabJson.str(p, "arg");
                String result = WabCompat.invokeAddonAction(bridge, maid, arg);
                ok(maid, result);
                return null;
            }
            default -> {
                return fail("unknown_action", "不认识的动作：" + action);
            }
        }
    }

    private static void useBlock(EntityMaid maid, BlockPos pos, JsonObject p) {
        String targetName = WabJson.str(p, "target");
        String itemHint = WabJson.str(p, "item");
        ItemStack mainHand = maid.getMainHandItem();
        if (mainHand.getItem() instanceof BlockItem && (itemHint.isEmpty() || itemHint.equals("block"))) {
            maid.placeItemBlock(pos, mainHand);
        } else if (mainHand.is(Items.BUCKET) && isFluid(maid.level(), pos)) {
            scoopFluid(maid, pos, mainHand);
        } else {
            maid.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 30F, 30F);
            maid.swing(InteractionHand.MAIN_HAND);
        }
    }

    private static boolean isFluid(Level level, BlockPos pos) {
        return !level.getFluidState(pos).isEmpty();
    }

    private static void scoopFluid(EntityMaid maid, BlockPos pos, ItemStack bucket) {
        ServerLevel level = (ServerLevel) maid.level();
        BlockState state = level.getBlockState(pos);
        boolean lava = state.is(Blocks.LAVA);
        ItemStack filled = new ItemStack(lava ? Items.LAVA_BUCKET : Items.WATER_BUCKET, bucket.getCount());
        maid.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, filled);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
    }

    // ==================== 物品 ====================

    public static JsonObject hold(WabBridge bridge, EntityMaid maid, JsonObject p) {
        String action = WabJson.str(p, "action").toLowerCase(Locale.ROOT);
        int count = WabJson.integer(p, "count", 1);
        String item = WabJson.str(p, "item");
        String target = WabJson.str(p, "target");
        switch (action) {
            case "give" -> {
                ServerPlayer owner = bridge.currentOwner();
                if (owner == null) return fail("no_owner", "主人不在线。");
                if (owner.level() != maid.level() || owner.distanceToSqr(maid) > 8 * 8) {
                    if (owner.level() == maid.level()) navigateTo(maid, owner.getX(), owner.getY(), owner.getZ(), 2.0);
                    return fail("too_far", "离得有点远，没法递过去。");
                }
                ItemStack stack = takeFromMaid(maid, item, count);
                if (stack.isEmpty()) {
                    return fail("not_held", "手上没有要找的东西。");
                }
                InvWrapper playerInv = new InvWrapper(owner.getInventory());
                ItemStack leftover = ItemHandlerHelper.insertItemStacked(playerInv, stack, false);
                int handed = stack.getCount() - leftover.getCount();
                if (handed > 0 && !leftover.isEmpty()) {
                    ItemEntity dropped = new ItemEntity(maid.level(), maid.getX(), maid.getY(), maid.getZ(), leftover);
                    maid.level().addFreshEntity(dropped);
                }
                ok(maid, "递给了 " + owner.getName().getString() + "（" + Math.max(0, handed) + " 个）。");
                return null;
            }
            case "drop" -> {
                ItemStack stack = takeFromMaid(maid, item, count);
                if (stack.isEmpty()) {
                    return fail("not_held", "手上没有要找的东西。");
                }
                ItemEntity dropped = new ItemEntity(maid.level(), maid.getX(), maid.getY(), maid.getZ(), stack);
                maid.level().addFreshEntity(dropped);
                ok(maid, "丢在了地上。");
                return null;
            }
            case "take" -> {
                return collectNearby(maid, WabJson.str(p, "item"), count, WabJson.number(p, "radius", 8.0));
            }
            case "feed_player" -> {
                ServerPlayer owner = bridge.currentOwner();
                if (owner == null) return fail("no_owner", "主人不在线。");
                if (owner.level() != maid.level() || owner.distanceToSqr(maid) > 4 * 4) {
                    if (owner.level() == maid.level()) navigateTo(maid, owner.getX(), owner.getY(), owner.getZ(), 2.0);
                    return fail("too_far", "隔得太远，喂不到。");
                }
                ItemStack food = takeFromMaid(maid, item.isEmpty() ? "food" : item, 1);
                if (food.isEmpty() || food.getItem().getFoodProperties(food, owner) == null) {
                    return fail("no_food", "手上没有能喂的食物。");
                }
                if (food.getUseAnimation() == net.minecraft.world.item.UseAnim.DRINK) {
                    owner.level().playSound(null, owner, food.getDrinkingSound(),
                            net.minecraft.sounds.SoundSource.NEUTRAL, 0.5F, 0.9F);
                }
                food.getItem().finishUsingItem(food, owner.level(), owner);
                WabAnimationMessage message = new WabAnimationMessage(maid.getId(), "use_mainhand", 40);
                com.github.tartaricacid.touhoulittlemaid.network.NetworkHandler.sendToNearby(maid, message);
                ok(maid, "直接把食物喂给了 " + owner.getName().getString() + "。");
                return null;
            }
            case "place" -> {
                BlockPos pos = parsePos(WabJson.arr(p, "pos"));
                if (pos == null) return fail("no_pos", "没有给放置的位置。");
                ItemStack stack = takeFromMaid(maid, item.isEmpty() ? "block" : item, 1);
                if (stack.isEmpty()) return fail("not_held", "手上没有可放置的东西。");
                if (stack.getItem() instanceof BlockItem && maid.placeItemBlock(pos, stack)) {
                    ok(maid, "放了上去。");
                } else {
                    ok(maid, "不确定能不能放这儿。");
                }
                return null;
            }
            case "equip" -> {
                ItemStack stack = takeFromMaid(maid, item, 1);
                if (stack.isEmpty()) return fail("not_held", "手上没有要找的东西。");
                String slot = target.isEmpty() ? "mainhand" : target.toLowerCase(Locale.ROOT);
                net.minecraft.world.entity.EquipmentSlot equipmentSlot;
                switch (slot) {
                    case "offhand" -> equipmentSlot = net.minecraft.world.entity.EquipmentSlot.OFFHAND;
                    case "head" -> equipmentSlot = net.minecraft.world.entity.EquipmentSlot.HEAD;
                    case "chest" -> equipmentSlot = net.minecraft.world.entity.EquipmentSlot.CHEST;
                    case "legs" -> equipmentSlot = net.minecraft.world.entity.EquipmentSlot.LEGS;
                    case "feet" -> equipmentSlot = net.minecraft.world.entity.EquipmentSlot.FEET;
                    default -> equipmentSlot = net.minecraft.world.entity.EquipmentSlot.MAINHAND;
                }
                maid.setItemSlot(equipmentSlot, stack);
                ok(maid, "装备好了。");
                return null;
            }
            default -> {
                return fail("unknown_action", "不认识的动作：" + action);
            }
        }
    }

    public static JsonObject collectNearby(EntityMaid maid, String item, int count, double radius) {
        List<ItemEntity> picked = new ArrayList<>();
        double maxDistance = radius * radius;
        List<Entity> candidates = maid.level().getEntities(maid, maid.getBoundingBox().inflate(radius),
                entity -> entity instanceof ItemEntity);
        for (Entity entity : candidates) {
            if (!(entity instanceof ItemEntity itemEntity) || !itemEntity.isAlive()) continue;
            if (maid.distanceToSqr(itemEntity) > maxDistance) continue;
            if (!itemEntity.getItem().isEmpty() && (item.isEmpty() || item.equals(WabSensors.itemId(itemEntity.getItem())))) {
                picked.add(itemEntity);
            }
            if (picked.size() >= count) break;
        }
        if (picked.isEmpty()) {
            return fail("nothing_nearby", "附近没有要找的东西。");
        }
        int got = 0;
        for (ItemEntity entity : picked) {
            if (maid.pickupItem(entity, false)) got++;
        }
        ok(maid, "捡起了 " + got + " 件东西。");
        return null;
    }

    // ==================== 长时间任务（Job）====================

    public static final class Job {
        private final WabBridge bridge;
        final EntityMaid maid;
        private final String action;
        private final List<BlockPos> targets = new ArrayList<>();
        private int index;
        private final int want;
        private int got;
        private final int budgetTicks;
        private int tickCount;
        private final boolean autonomous;
        private final Responder responder;
        private int stuckTicks;
        private double lastDistance = -1;
        private boolean guiWasOpening;
        private boolean done;
        private String cancelReason = "";
        private Map<String, Integer> beforeInventory = new HashMap<>();

        private Job(WabBridge bridge, EntityMaid maid, String action, int want, int budgetTicks,
                    boolean autonomous, Responder responder) {
            this.bridge = bridge;
            this.maid = maid;
            this.action = action;
            this.want = want;
            this.budgetTicks = budgetTicks;
            this.autonomous = autonomous;
            this.responder = responder;
        }

        void begin() {
            guiWasOpening = maid.guiOpening;
            maid.guiOpening = true;
            maid.getNavigation().stop();
            beforeInventory = snapshotInventory(maid);
        }

        private static Map<String, Integer> snapshotInventory(EntityMaid maid) {
            Map<String, Integer> result = new HashMap<>();
            CombinedInvWrapper inv = maid.getAvailableInv(true);
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (!stack.isEmpty()) {
                    result.merge(WabSensors.itemId(stack), stack.getCount(), Integer::sum);
                }
            }
            return result;
        }

        public void tick() {
            if (done) return;
            tickCount++;
            if (!maid.isAlive() || !maid.level().isLoaded(maid.blockPosition())) {
                finish(false, "她倒下了，任务中止。");
                return;
            }
            if (tickCount >= budgetTicks) {
                finish(false, "时间到了，先停下来。");
                return;
            }
            if (index >= targets.size()) {
                finish(true, "干完了手头的事。");
                return;
            }
            BlockPos current = targets.get(index);
            if (!blockStillValid(current)) {
                index++;
                stuckTicks = 0;
                lastDistance = -1;
                if (index >= targets.size()) finish(true, "剩下的都去不了。");
                return;
            }
            double distance = maid.distanceToSqr(current.getX() + 0.5, current.getY(), current.getZ() + 0.5);
            if (distance > 20) { // 大约 4.5 格射程
                if (distance - lastDistance > 0.05) stuckTicks++;
                else stuckTicks = 0;
                lastDistance = distance;
                if (stuckTicks > 60 || !maid.getNavigation().moveTo(current.getX() + 0.5, current.getY(),
                        current.getZ() + 0.5, 1.0F)) {
                    if (stuckTicks > 60 || !maid.canPathReach(current)) {
                        index++;
                        stuckTicks = 0;
                        lastDistance = -1;
                        if (index >= targets.size()) finish(true, "剩下的都去不了。");
                    }
                    return;
                }
                return;
            }
            // 到射程内，执行动作
            executeAction(current);
            index++;
            stuckTicks = 0;
            lastDistance = -1;
            if (index >= targets.size() || got >= want) {
                finish(true, "完成了能做的部分。");
            }
        }

        private void executeAction(BlockPos pos) {
            switch (action) {
                case "mine", "chop", "harvest" -> {
                    boolean destroyed = maid.destroyBlock(pos, true);
                    got++;
                }
                case "collect" -> {
                    List<ItemEntity> nearby = new ArrayList<>();
                    for (Entity entity : maid.level().getEntities(maid, maid.getBoundingBox().inflate(3.0),
                            entity -> entity instanceof ItemEntity)) {
                        if (entity instanceof ItemEntity itemEntity && maid.distanceToSqr(itemEntity) < 3.0 * 3.0) {
                            nearby.add(itemEntity);
                        }
                    }
                    for (ItemEntity entity : nearby) {
                        if (maid.pickupItem(entity, false)) got++;
                    }
                }
                case "scoop" -> {
                    ItemStack bucket = takeFromMaid(maid, "bucket", 1);
                    if (!bucket.isEmpty()) {
                        scoopFluid(maid, pos, bucket);
                        got++;
                    }
                }
                case "fill" -> {
                    ItemStack block = takeFromMaid(maid, "block", 1);
                    if (!block.isEmpty() && block.getItem() instanceof BlockItem && maid.placeItemBlock(pos, block)) {
                        got++;
                    }
                }
                default -> {
                }
            }
        }

        private boolean blockStillValid(BlockPos pos) {
            if (!maid.level().isLoaded(pos)) return false;
            BlockState state = maid.level().getBlockState(pos);
            switch (action) {
                case "mine":
                    return WabSensors.isOre(state);
                case "chop":
                    return state.is(BlockTags.LOGS);
                case "harvest":
                    return WabSensors.isMatureCrop(state);
                case "collect", "scoop", "fill":
                    return true;
                default:
                    return true;
            }
        }

        public void cancel(String reason) {
            if (done) return;
            cancelReason = reason;
            done = true;
            maid.guiOpening = guiWasOpening;
        }

        public void finish(boolean complete, String message) {
            if (done) return;
            done = true;
            maid.guiOpening = guiWasOpening;
            JsonObject data = new JsonObject();
            data.addProperty("action", action);
            data.addProperty("complete", complete);
            data.addProperty("message", message);
            data.addProperty("got", got);
            data.addProperty("wanted", want);
            JsonArray loot = new JsonArray();
            Map<String, Integer> after = snapshotInventory(maid);
            for (Map.Entry<String, Integer> entry : after.entrySet()) {
                int before = beforeInventory.getOrDefault(entry.getKey(), 0);
                int delta = entry.getValue() - before;
                if (delta > 0) {
                    JsonObject item = new JsonObject();
                    item.addProperty("id", entry.getKey());
                    item.addProperty("count", delta);
                    loot.add(item);
                }
            }
            data.add("loot", loot);

            if (autonomous) {
                bridge.sendEvent("job_" + action, maid, message, complete ? 0.4 : 0.25, 0, "自主任务结束");
            } else if (responder != null) {
                if (complete || got > 0) {
                    JsonObject result = new JsonObject();
                    result.addProperty("ok", true);
                    result.add("data", data);
                    responder.success(data);
                } else {
                    responder.failure("nothing_done", message);
                }
            }
        }

        public boolean isActive() {
            return !done;
        }
    }

    public static Job startGather(WabBridge bridge, EntityMaid maid, JsonObject params, int budgetTicks,
                                  boolean autonomous, Responder responder) {
        String action = WabJson.str(params, "action", "mine").toLowerCase(Locale.ROOT);
        int want = WabJson.integer(params, "count", 16);
        double radius = WabJson.number(params, "radius", 16.0);
        radius = Math.max(4, Math.min(24, radius));
        String filter = WabJson.str(params, "filter");

        Job job = new Job(bridge, maid, action, want, budgetTicks, autonomous, responder);
        List<BlockPos> found = new ArrayList<>();
        BlockPos center = maid.blockPosition();
        int r = (int) radius;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, 0, -r), center.offset(r, 2, r))) {
            if (!maid.level().isLoaded(pos)) continue;
            if (pos.distSqr(center) > radius * radius) continue;
            BlockState state = maid.level().getBlockState(pos);
            boolean matches = switch (action) {
                case "mine" -> filter.isEmpty() || filter.equals("ores")
                        ? WabSensors.isOre(state)
                        : WabSensors.blockId((ServerLevel) maid.level(), pos).equals(filter);
                case "chop" -> state.is(BlockTags.LOGS);
                case "harvest" -> WabSensors.isMatureCrop(state);
                case "scoop" -> state.getFluidState().isSource()
                        && (state.is(Blocks.WATER) || state.is(Blocks.LAVA));
                default -> false;
            };
            if (matches) found.add(pos.immutable());
        }
        found.sort(Comparator.comparingDouble((BlockPos a) -> a.distSqr(center)));
        if (found.size() > want) found.subList(want, found.size()).clear();
        job.targets.addAll(found);
        job.begin();
        return job;
    }

    public static JsonObject collect(WabBridge bridge, EntityMaid maid, JsonObject params) {
        String item = WabJson.str(params, "filter");
        int count = WabJson.integer(params, "count", 16);
        double radius = WabJson.number(params, "radius", 8.0);
        return collectNearby(maid, item.isEmpty() ? WabJson.str(params, "item") : item, count, radius);
    }

    public static JsonObject fill(WabBridge bridge, EntityMaid maid, JsonObject params) {
        BlockPos pos = parsePos(WabJson.arr(params, "pos"));
        if (pos == null) {
            pos = maid.blockPosition().above();
        }
        if (!maid.level().getBlockState(pos).canBeReplaced()) {
            return fail("occupied", "那个位置已经是固体方块了，塞不进去。");
        }
        ItemStack block = takeFromMaid(maid, WabJson.str(params, "item"), 1);
        if (block.isEmpty() || !(block.getItem() instanceof BlockItem)) {
            return fail("not_held", "手上没有可以填进去的方块。");
        }
        if (maid.placeItemBlock(pos, block)) {
            ok(maid, "填上了。");
            return null;
        }
        return fail("cannot_place", "放不上去，可能位置不对。");
    }

    // ==================== 日程 / 计划 ====================

    public static JsonObject plan(WabBridge bridge, EntityMaid maid, JsonObject params) {
        String action = WabJson.str(params, "action", "get").toLowerCase(Locale.ROOT);
        switch (action) {
            case "get" -> {
                JsonObject data = new JsonObject();
                data.addProperty("plan", String.valueOf(maid.getScheduleDetail()));
                data.addProperty("homeMode", String.valueOf(maid.isHomeModeEnable()));
                data.addProperty("work", String.valueOf(maid.getSchedulePos().getWorkPos()));
                data.addProperty("idle", String.valueOf(maid.getSchedulePos().getIdlePos()));
                data.addProperty("sleep", String.valueOf(maid.getSchedulePos().getSleepPos()));
                return data;
            }
            case "clear" -> {
                ok(maid, "日程已清空。");
                return null;
            }
            case "set" -> {
                // 简化：记录到持久化数据里，交给 WabAutonomy 执行
                String steps = WabJson.arr(params, "steps").toString();
                maid.getPersistentData().putString("WabPlan", steps);
                JsonObject autonomy = WabJson.obj(params, "autonomy");
                if (autonomy.size() > 0) {
                    maid.getPersistentData().putString("WabAutonomy", autonomy.toString());
                }
                bridge.autonomy().notePlanChanged(maid);
                ok(maid, "日程写好了，她知道了今天要做什么。");
                return null;
            }
            default -> {
                return fail("unknown_action", "不认识的日程操作。");
            }
    }
    }

    // ==================== 工具 ====================

    private static ItemStack takeFromMaid(EntityMaid maid, String hint, int count) {
        if (hint.isEmpty() || hint.equals("food") || hint.equals("block") || hint.equals("bucket")) {
            CombinedInvWrapper inv = maid.getAvailableInv(true);
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (stack.isEmpty()) continue;
                boolean match = switch (hint) {
                    case "food" -> stack.getItem().getFoodProperties(stack, maid) != null;
                    case "block" -> stack.getItem() instanceof BlockItem;
                    case "bucket" -> stack.is(Items.BUCKET);
                    default -> true;
                };
                if (match) {
                    ItemStack take = inv.extractItem(i, count, false);
                    if (!take.isEmpty()) return take;
                }
            }
            return ItemStack.EMPTY;
        }
        String wanted = hint.toLowerCase(Locale.ROOT);
        CombinedInvWrapper inv = maid.getAvailableInv(true);
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            String id = WabSensors.itemId(stack);
            if (id.equals(wanted) || id.toLowerCase(Locale.ROOT).contains(wanted)) {
                return inv.extractItem(i, count, false);
            }
        }
        return ItemStack.EMPTY;
    }

    private static BlockPos parsePos(JsonArray arr) {
        if (arr.size() < 3) return null;
        try {
            int x = arr.get(0).getAsInt();
            int y = arr.get(1).getAsInt();
            int z = arr.get(2).getAsInt();
            return new BlockPos(x, y, z);
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonObject fail(String code, String message) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", false);
        out.addProperty("code", code);
        out.addProperty("error", message);
        return out;
    }

    private static void ok(EntityMaid maid, String message) {
        JsonObject placeholder = new JsonObject();
        placeholder.addProperty("maid", maid.getUUID().toString());
        placeholder.addProperty("message", message);
    }
}
