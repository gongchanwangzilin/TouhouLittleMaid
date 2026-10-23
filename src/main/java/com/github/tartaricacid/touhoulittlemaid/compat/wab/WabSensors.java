package com.github.tartaricacid.touhoulittlemaid.compat.wab;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 只读感知：世界、女仆、周围实体、矿石、流体、亮度、容器。
 * 所有结果都是 JSON，方便直接放进 status / scan 的 result。
 */
public final class WabSensors {
    private WabSensors() {
    }

    public static JsonObject world(WabBridge bridge) {
        ServerLevel level = bridge.server().overworld();
        JsonObject out = new JsonObject();
        out.addProperty("name", bridge.worldName());
        out.addProperty("dimension", level.dimension().location().toString());
        out.addProperty("weather", weather(level));
        long dayTime = level.getDayTime();
        out.addProperty("dayTime", dayTime);
        out.addProperty("time", clock(dayTime));
        out.addProperty("day", dayTime / 24000L);
        out.addProperty("ownerOnline", bridge.currentOwner() != null);
        out.addProperty("players", bridge.server().getPlayerList().getPlayerCount());
        out.addProperty("raining", level.isRaining());
        out.addProperty("thundering", level.isThundering());
        return out;
    }

    public static String weather(ServerLevel level) {
        if (level.isThundering()) return "thunder";
        if (level.isRaining()) return "rain";
        return "clear";
    }

    /** dayTime 0 是 06:00；1000 tick 等于游戏内一小时。 */
    public static String clock(long dayTime) {
        long normalized = ((dayTime + 6000L) % 24000L + 24000L) % 24000L;
        int hour = (int) (normalized / 1000L);
        int minute = (int) ((normalized % 1000L) * 60L / 1000L);
        return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
    }

    public static JsonObject snapshot(WabBridge bridge, EntityMaid maid) {
        JsonObject out = new JsonObject();
        out.addProperty("id", maid.getUUID().toString());
        out.addProperty("name", WabBridge.cleanName(maid));
        out.addProperty("model", maid.getModelId());
        out.addProperty("health", round(maid.getHealth()));
        out.addProperty("maxHealth", round(maid.getMaxHealth()));
        JsonObject love = loveLoathe(maid);
        out.addProperty("food", love != null ? round(love.get("hunger").getAsDouble() / 5.0) : maid.getHunger());
        JsonArray pos = new JsonArray();
        pos.add(round(maid.getX()));
        pos.add(round(maid.getY()));
        pos.add(round(maid.getZ()));
        out.add("pos", pos);
        out.addProperty("dimension", maid.level().dimension().location().toString());
        out.addProperty("task", maid.getTask().getUid().toString());
        out.addProperty("activity", maid.getScheduleDetail().getName());
        out.addProperty("owner", ownerName(maid));
        Player owner = maid.getOwner() instanceof Player player ? player : null;
        out.addProperty("distanceToOwner", owner != null && owner.level() == maid.level()
                ? round(Math.sqrt(maid.distanceToSqr(owner))) : -1);
        if (love != null) out.add("loveLoathe", love);
        out.addProperty("sitting", maid.isInSittingPose());
        out.addProperty("sleeping", maid.isSleeping());
        out.addProperty("onGround", maid.onGround());
        out.addProperty("inWater", maid.isInWater());
        out.addProperty("pickup", maid.isPickup());
        return out;
    }

    public static JsonObject status(WabBridge bridge, EntityMaid maid) {
        JsonObject out = new JsonObject();
        out.add("world", world(bridge));
        out.add("maid", snapshot(bridge, maid));
        out.addProperty("uuid", maid.getUUID().toString());
        out.addProperty("healthDetail", round(maid.getHealth()) + "/" + round(maid.getMaxHealth()));
        out.addProperty("favorability", maid.getFavorability());
        out.addProperty("experience", maid.getExperience());
        out.addProperty("hunger", maid.getHunger());
        out.addProperty("schedule", maid.getSchedule().name());
        out.addProperty("homeMode", maid.isHomeModeEnable());
        out.addProperty("restrictRadius", round(maid.getRestrictRadius()));
        BlockPos center = maid.getRestrictCenter();
        out.add("restrictCenter", blockPos(center));
        out.addProperty("taskName", taskName(maid));
        out.add("mainHand", item(maid.getMainHandItem()));
        out.add("offHand", item(maid.getOffhandItem()));
        out.add("inventory", inventory(maid, 24));
        out.add("armor", armor(maid));
        out.add("nearbyPlayers", players(maid, 16));
        out.add("schedulePos", schedulePos(maid));
        return out;
    }

    public static JsonObject scan(WabBridge bridge, EntityMaid maid, JsonObject params) {
        int radius = Math.max(4, Math.min(64, WabJson.integer(params, "radius", 16)));
        boolean wantEntities = WabJson.bool(params, "entities", true);
        boolean wantBlocks = WabJson.bool(params, "blocks", true);
        boolean wantFluids = WabJson.bool(params, "fluids", true);
        boolean wantLight = WabJson.bool(params, "light", true);
        ServerLevel level = (ServerLevel) maid.level();

        JsonObject out = new JsonObject();
        out.add("world", world(bridge));
        out.add("from", snapshot(bridge, maid));
        out.addProperty("radius", radius);

        if (wantEntities) out.add("entities", entities(maid, radius));
        if (wantBlocks) {
            JsonObject blocks = blocks(level, maid, radius);
            out.add("ores", blocks.get("ores"));
            out.add("containers", blocks.get("containers"));
            out.add("crops", blocks.get("crops"));
        }
        if (wantFluids) out.add("fluids", fluids(level, maid, radius));
        if (wantLight) out.add("light", light(level, maid));
        out.add("drops", itemEntities(maid, Math.min(radius, 12)));
        return out;
    }

    public static JsonArray entities(EntityMaid maid, int radius) {
        ServerLevel level = (ServerLevel) maid.level();
        AABB box = maid.getBoundingBox().inflate(radius);
        List<Entity> found = new ArrayList<>(level.getEntities(maid, box,
                entity -> entity != maid && entity.isAlive() && entity instanceof LivingEntity));
        found.sort(Comparator.comparingDouble(maid::distanceToSqr));
        JsonArray out = new JsonArray();
        for (Entity entity : found) {
            if (out.size() >= 48) break;
            LivingEntity living = (LivingEntity) entity;
            JsonObject item = new JsonObject();
            item.addProperty("id", entity.getUUID().toString());
            item.addProperty("type", entityType(entity));
            item.addProperty("kind", kind(entity));
            item.addProperty("name", entity.getName().getString());
            item.addProperty("distance", round(Math.sqrt(maid.distanceToSqr(entity))));
            item.add("pos", vecPos(entity));
            item.addProperty("health", round(living.getHealth()));
            item.addProperty("maxHealth", round(living.getMaxHealth()));
            if (entity instanceof ServerPlayer player) {
                item.addProperty("player", player.getGameProfile().getName());
                item.addProperty("creative", player.isCreative());
            }
            out.add(item);
        }
        return out;
    }

    private static JsonObject blocks(ServerLevel level, EntityMaid maid, int radius) {
        JsonObject out = new JsonObject();
        JsonArray ores = new JsonArray();
        JsonArray containers = new JsonArray();
        JsonArray crops = new JsonArray();
        BlockPos origin = maid.blockPosition();
        List<FoundBlock> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-radius, -radius, -radius),
                origin.offset(radius, radius, radius))) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            boolean ore = isOre(state);
            boolean container = state.hasBlockEntity() && level.getBlockEntity(pos) instanceof Container;
            boolean crop = isMatureCrop(state);
            if (!ore && !container && !crop) continue;
            found.add(new FoundBlock(pos.immutable(), maid.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5),
                    ore, container, crop));
        }
        found.sort(Comparator.comparingDouble(FoundBlock::distance));
        for (FoundBlock block : found) {
            if (block.ore && ores.size() < 48) {
                JsonObject item = blockJson(block.pos, maid);
                item.addProperty("block", blockId(level, block.pos));
                ores.add(item);
            }
            if (block.container && containers.size() < 24) {
                JsonObject item = blockJson(block.pos, maid);
                item.addProperty("block", blockId(level, block.pos));
                containers.add(item);
            }
            if (block.crop && crops.size() < 32) {
                JsonObject item = blockJson(block.pos, maid);
                item.addProperty("block", blockId(level, block.pos));
                crops.add(item);
            }
        }
        out.add("ores", ores);
        out.add("containers", containers);
        out.add("crops", crops);
        return out;
    }

    private static JsonArray fluids(ServerLevel level, EntityMaid maid, int radius) {
        JsonArray out = new JsonArray();
        BlockPos origin = maid.blockPosition();
        List<FoundBlock> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-radius, -radius, -radius),
                origin.offset(radius, radius, radius))) {
            BlockState state = level.getBlockState(pos);
            if (!state.getFluidState().isSource()) continue;
            if (!state.getFluidState().is(FluidTags.WATER) && !state.getFluidState().is(FluidTags.LAVA)) continue;
            found.add(new FoundBlock(pos.immutable(),
                    maid.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5), false, false, false));
        }
        found.sort(Comparator.comparingDouble(FoundBlock::distance));
        for (FoundBlock block : found) {
            if (out.size() >= 32) break;
            BlockState state = level.getBlockState(block.pos);
            JsonObject item = blockJson(block.pos, maid);
            item.addProperty("fluid", state.getFluidState().is(FluidTags.LAVA) ? "lava" : "water");
            out.add(item);
        }
        return out;
    }

    private static JsonObject light(ServerLevel level, EntityMaid maid) {
        BlockPos pos = maid.blockPosition();
        JsonObject out = new JsonObject();
        out.addProperty("block", level.getBrightness(LightLayer.BLOCK, pos));
        out.addProperty("sky", level.getBrightness(LightLayer.SKY, pos));
        out.addProperty("maxLocal", level.getMaxLocalRawBrightness(pos));
        out.addProperty("canSeeSky", level.canSeeSky(pos));
        return out;
    }

    private static JsonArray itemEntities(EntityMaid maid, int radius) {
        JsonArray out = new JsonArray();
        List<ItemEntity> items = new ArrayList<>(maid.level().getEntitiesOfClass(ItemEntity.class,
                maid.getBoundingBox().inflate(radius), ItemEntity::isAlive));
        items.sort(Comparator.comparingDouble(maid::distanceToSqr));
        for (ItemEntity entity : items) {
            if (out.size() >= 24) break;
            JsonObject item = new JsonObject();
            item.addProperty("id", entity.getUUID().toString());
            item.addProperty("item", itemId(entity.getItem()));
            item.addProperty("name", entity.getItem().getHoverName().getString());
            item.addProperty("count", entity.getItem().getCount());
            item.addProperty("distance", round(Math.sqrt(maid.distanceToSqr(entity))));
            item.add("pos", vecPos(entity));
            out.add(item);
        }
        return out;
    }

    public static JsonArray inventory(EntityMaid maid, int limit) {
        JsonArray out = new JsonArray();
        var inv = maid.getAvailableInv(true);
        for (int i = 0; i < inv.getSlots() && out.size() < limit; i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            JsonObject item = item(stack);
            item.addProperty("slot", i);
            out.add(item);
        }
        return out;
    }

    private static JsonArray armor(EntityMaid maid) {
        JsonArray out = new JsonArray();
        for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            if (slot.getType() != net.minecraft.world.entity.EquipmentSlot.Type.ARMOR) continue;
            ItemStack stack = maid.getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            JsonObject item = item(stack);
            item.addProperty("slot", slot.getName());
            out.add(item);
        }
        return out;
    }

    private static JsonArray players(EntityMaid maid, int radius) {
        JsonArray out = new JsonArray();
        for (Player player : maid.level().getEntitiesOfClass(Player.class, maid.getBoundingBox().inflate(radius))) {
            JsonObject item = new JsonObject();
            item.addProperty("name", player.getName().getString());
            item.addProperty("distance", round(Math.sqrt(maid.distanceToSqr(player))));
            item.addProperty("health", round(player.getHealth()));
            item.addProperty("food", player.getFoodData().getFoodLevel());
            item.addProperty("creative", player.isCreative());
            out.add(item);
        }
        return out;
    }

    private static JsonObject schedulePos(EntityMaid maid) {
        JsonObject out = new JsonObject();
        out.add("work", blockPos(maid.getSchedulePos().getWorkPos()));
        out.add("idle", blockPos(maid.getSchedulePos().getIdlePos()));
        out.add("sleep", blockPos(maid.getSchedulePos().getSleepPos()));
        out.addProperty("configured", maid.getSchedulePos().isConfigured());
        out.addProperty("dimension", maid.getSchedulePos().getDimension().toString());
        return out;
    }

    public static JsonObject loveLoathe(EntityMaid maid) {
        CompoundTag root = maid.getPersistentData();
        if (!root.contains("MaidEmotions") && !root.contains("MaidHunger")) return null;
        CompoundTag emotions = root.getCompound("MaidEmotions");
        UUID ownerId = maid.getOwnerUUID();
        CompoundTag mine = ownerId == null ? new CompoundTag() : emotions.getCompound(ownerId.toString());
        int trust = mine.contains("trust") ? mine.getInt("trust") : 40;
        int fear = mine.contains("fear") ? mine.getInt("fear") : 10;
        float hunger = root.contains("MaidHunger") ? root.getFloat("MaidHunger") : 50.0f;
        JsonObject out = new JsonObject();
        out.addProperty("trust", trust);
        out.addProperty("fear", fear);
        out.addProperty("hunger", round(hunger));
        out.addProperty("tendency", tendency(trust, fear));
        return out;
    }

    public static String tendency(int trust, int fear) {
        if (trust >= 70 && fear <= 20) return "BOND";
        if (trust >= 40 && fear <= 30) return "FRIENDLY";
        if (trust <= 20 && fear >= 70) return "TERRIFIED";
        if (trust <= 30 && fear <= 20) return "STRANGER";
        if (trust <= 40 && fear >= 50) return "FEARFUL";
        if (trust >= 60 && fear >= 60) return "CONFLICTED";
        return "NEUTRAL";
    }

    public static JsonObject item(ItemStack stack) {
        JsonObject out = new JsonObject();
        if (stack.isEmpty()) return out;
        out.addProperty("item", itemId(stack));
        out.addProperty("name", stack.getHoverName().getString());
        out.addProperty("count", stack.getCount());
        if (stack.isDamageableItem()) {
            out.addProperty("damage", stack.getDamageValue());
            out.addProperty("maxDamage", stack.getMaxDamage());
        }
        if (stack.getItem() instanceof BlockItem blockItem) {
            out.addProperty("block", ForgeRegistries.BLOCKS.getKey(blockItem.getBlock()).toString());
        }
        return out;
    }

    public static String itemId(ItemStack stack) {
        return ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
    }

    public static String blockId(ServerLevel level, BlockPos pos) {
        return ForgeRegistries.BLOCKS.getKey(level.getBlockState(pos).getBlock()).toString();
    }

    public static String entityType(Entity entity) {
        return ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()).toString();
    }

    public static String kind(Entity entity) {
        if (entity instanceof Player) return "player";
        if (entity instanceof Monster) return "hostile";
        if (entity instanceof Animal) return "animal";
        return "other";
    }

    public static boolean isOre(BlockState state) {
        return state.is(BlockTags.COAL_ORES) || state.is(BlockTags.IRON_ORES) || state.is(BlockTags.COPPER_ORES)
                || state.is(BlockTags.GOLD_ORES) || state.is(BlockTags.REDSTONE_ORES) || state.is(BlockTags.LAPIS_ORES)
                || state.is(BlockTags.DIAMOND_ORES) || state.is(BlockTags.EMERALD_ORES)
                || state.is(Blocks.ANCIENT_DEBRIS) || state.is(Blocks.NETHER_QUARTZ_ORE);
    }

    public static boolean isMatureCrop(BlockState state) {
        if (state.getBlock() instanceof CropBlock crop) return crop.isMaxAge(state);
        if (state.is(Blocks.NETHER_WART)) return age(state) >= 3;
        if (state.is(Blocks.SWEET_BERRY_BUSH)) return age(state) >= 3;
        return false;
    }

    private static int age(BlockState state) {
        for (var property : state.getProperties()) {
            if (property instanceof IntegerProperty integer && property.getName().equals("age")) {
                return state.getValue(integer);
            }
        }
        return -1;
    }

    public static JsonArray blockPos(BlockPos pos) {
        JsonArray out = new JsonArray();
        out.add(pos.getX());
        out.add(pos.getY());
        out.add(pos.getZ());
        return out;
    }

    public static JsonArray vecPos(Entity entity) {
        JsonArray out = new JsonArray();
        out.add(round(entity.getX()));
        out.add(round(entity.getY()));
        out.add(round(entity.getZ()));
        return out;
    }

    public static JsonObject blockJson(BlockPos pos, EntityMaid maid) {
        JsonObject out = new JsonObject();
        out.add("pos", blockPos(pos));
        out.addProperty("distance", round(Math.sqrt(maid.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5))));
        return out;
    }

    public static String ownerName(EntityMaid maid) {
        if (maid.getOwner() != null) return maid.getOwner().getName().getString();
        return "";
    }

    private static String taskName(EntityMaid maid) {
        String id = maid.getTask().getUid().toString();
        Component name = Component.translatable("task." + maid.getTask().getUid().getNamespace() + "." + maid.getTask().getUid().getPath());
        return id + "|" + name.getString();
    }

    public static double round(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0;
        return Math.round(value * 10.0) / 10.0;
    }

    private record FoundBlock(BlockPos pos, double distance, boolean ore, boolean container, boolean crop) {
    }
}
