package com.github.tartaricacid.touhoulittlemaid.compat.wab;

import com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模组侧的桥接门面：持有服务器、会话、女仆缓存与主动行为。
 * 网络线程只读写 JSON；所有世界操作都回到服务器线程执行。
 */
@Mod.EventBusSubscriber(modid = TouhouLittleMaid.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WabBridge {
    private static final String PROTOCOL = "1";
    private static final String MAGIC = "WAB-MAID";
    private static WabBridge instance;

    private final MinecraftServer server;
    private final String instanceId;
    private final WabDiscovery discovery;
    private final WabDispatcher dispatcher;
    private final WabAutonomy autonomy;

    private volatile WabSession session;
    private ServerPlayer owner;
    private String ownerName = "";
    private String worldName = "";

    private final Map<UUID, EntityMaid> maids = new ConcurrentHashMap<>();
    private final Map<UUID, WabHud.Stats> stats = new ConcurrentHashMap<>();
    private final Map<UUID, String> boundCharacters = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastEventAt = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> followTargets = new HashMap<>();
    private volatile JsonArray beaconMaids = new JsonArray();
    private volatile boolean allowWorldActions = true;
    private volatile boolean allowGestures = true;

    private int tickCounter;
    private long lastStateAt;
    private String lastStateHash = "";

    private WabBridge(MinecraftServer server) {
        this.server = server;
        this.instanceId = makeInstanceId(server);
        this.discovery = new WabDiscovery(this);
        this.dispatcher = new WabDispatcher(this);
        this.autonomy = new WabAutonomy(this);
        refreshOwner();
        this.discovery.start();
        TouhouLittleMaid.LOGGER.info("[WAB] bridge ready, instance={}, world={}, owner={}", instanceId, worldName, ownerName);
    }

    public static WabBridge get() {
        return instance;
    }

    public MinecraftServer server() {
        return server;
    }

    public String instanceId() {
        return instanceId;
    }

    public WabSession session() {
        return session;
    }

    public boolean connected() {
        WabSession current = session;
        return current != null && !current.isClosed();
    }

    public ServerPlayer owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    public String worldName() {
        return worldName;
    }

    public boolean allowWorldActions() {
        return allowWorldActions;
    }

    public boolean allowGestures() {
        return allowGestures;
    }

    public WabDispatcher dispatcher() {
        return dispatcher;
    }

    public WabAutonomy autonomy() {
        return autonomy;
    }

    public Collection<EntityMaid> ownedMaids() {
        return maids.values();
    }

    public WabHud.Stats stats(UUID maidId) {
        return stats.get(maidId);
    }

    public String boundCharacter(UUID maidId) {
        return boundCharacters.getOrDefault(maidId, "");
    }

    public boolean isOwned(EntityMaid maid) {
        return owner != null && maid.isTame() && owner.getUUID().equals(maid.getOwnerUUID());
    }

    public EntityMaid resolveMaid(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return primaryMaid();
        try {
            EntityMaid byId = maids.get(UUID.fromString(text));
            if (byId != null && byId.isAlive()) return byId;
        } catch (IllegalArgumentException ignored) {
        }
        for (EntityMaid maid : maids.values()) {
            if (maid.isAlive() && cleanName(maid).equalsIgnoreCase(text)) return maid;
        }
        for (EntityMaid maid : maids.values()) {
            if (maid.isAlive() && maid.getName().getString().toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT))) {
                return maid;
            }
        }
        return null;
    }

    private EntityMaid primaryMaid() {
        return maids.values().stream()
                .filter(EntityMaid::isAlive)
                .filter(this::isOwned)
                .sorted(Comparator
                        .comparing((EntityMaid maid) -> !boundCharacters.containsKey(maid.getUUID()))
                        .thenComparing(WabBridge::cleanName, String.CASE_INSENSITIVE_ORDER))
                .findFirst().orElse(null);
    }

    public static String cleanName(EntityMaid maid) {
        String name = maid.getName().getString();
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '§') {
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    public void attach(WabSession incoming, JsonObject welcome) {
        WabSession old = session;
        if (old != null && old != incoming) old.close("replaced by a new session");
        session = incoming;
        server.execute(() -> {
            onWelcome(incoming, welcome);
            incoming.startReader();
        });
    }

    void detach(WabSession expected) {
        if (session == expected) {
            session = null;
            TouhouLittleMaid.LOGGER.info("[WAB] session closed, return to discovery");
        }
    }

    void handleFrame(WabSession from, JsonObject frame) {
        if (from != session) return;
        String type = WabJson.str(frame, "type").toLowerCase(Locale.ROOT);
        switch (type) {
            case "welcome" -> onWelcome(from, frame);
            case "bind" -> onBind(frame);
            case "stats" -> onStats(frame);
            case "chat_reply" -> onChatReply(frame);
            case "command" -> {
                String id = WabJson.str(frame, "id");
                String method = WabJson.str(frame, "method").toLowerCase(Locale.ROOT);
                JsonObject params = WabJson.obj(frame, "params");
                dispatcher.dispatch(from, id, method, params);
            }
            case "error" -> TouhouLittleMaid.LOGGER.warn("[WAB] WAB error {} {}",
                    WabJson.str(frame, "code"), WabJson.str(frame, "message"));
            default -> {
            }
        }
    }

    private void onWelcome(WabSession from, JsonObject welcome) {
        JsonObject settings = WabJson.obj(welcome, "settings");
        if (settings.has("allowWorldActions")) allowWorldActions = WabJson.bool(settings, "allowWorldActions", true);
        if (settings.has("allowGestures")) allowGestures = WabJson.bool(settings, "allowGestures", true);
        boundCharacters.clear();
        JsonObject bound = WabJson.obj(welcome, "bound");
        for (String maidId : bound.keySet()) {
            try {
                boundCharacters.put(UUID.fromString(maidId), WabJson.str(bound, maidId));
            } catch (IllegalArgumentException ignored) {
            }
        }
        TouhouLittleMaid.LOGGER.info("[WAB] handshake ok, save={}, character={}, bound={}",
                WabJson.str(welcome, "saveId"), WabJson.str(welcome, "characterId"), boundCharacters.size());
        updateBeaconMaids();
        sendState(true);
        if (owner != null) {
            owner.displayClientMessage(Component.literal("温暖如初已连接").withStyle(ChatFormatting.GRAY), true);
        }
    }

    private void onBind(JsonObject bind) {
        JsonObject bound = WabJson.obj(bind, "bindings");
        boundCharacters.clear();
        for (String maidId : bound.keySet()) {
            try {
                boundCharacters.put(UUID.fromString(maidId), WabJson.str(bound, maidId));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (owner != null && WabJson.arr(bind, "notes").size() > 0) {
            owner.displayClientMessage(Component.literal(WabJson.str(bind, "notes")).withStyle(ChatFormatting.GRAY), false);
        }
    }

    private void onStats(JsonObject frame) {
        UUID maidId = uuid(WabJson.str(frame, "maid"));
        if (maidId == null) return;
        WabHud.Stats value = new WabHud.Stats();
        value.character = WabJson.str(frame, "character");
        value.affection = WabJson.integer(frame, "affection", 0);
        value.affectionPoints = WabJson.integer(frame, "affectionPoints", 0);
        value.trust = WabJson.integer(frame, "trust", 0);
        value.energy = WabJson.integer(frame, "energy", 0);
        value.mood = WabJson.integer(frame, "mood", 0);
        value.receivedAt = System.currentTimeMillis();
        stats.put(maidId, value);
        if (value.character != null && !value.character.isEmpty()) boundCharacters.put(maidId, value.character);
    }

    private void onChatReply(JsonObject frame) {
        String maidId = WabJson.str(frame, "maid");
        String text = WabJson.str(frame, "text");
        EntityMaid maid = resolveMaid(maidId);
        if (!WabJson.bool(frame, "ok", true)) {
            String error = WabJson.str(frame, "error", "她没能回应。");
            ServerPlayer player = currentOwner();
            if (player != null) player.sendSystemMessage(Component.literal(error).withStyle(ChatFormatting.GRAY));
            return;
        }
        if (text.isEmpty()) return;
        if (maid != null && maid.level() instanceof ServerLevel) {
            maid.getChatBubbleManager().addLLMChatText(text, -1);
        } else {
            ServerPlayer player = currentOwner();
            if (player != null) {
                player.sendSystemMessage(Component.literal("<" + (maid == null ? "女仆" : cleanName(maid)) + "> ")
                        .append(Component.literal(text)).withStyle(ChatFormatting.GRAY));
            }
        }
    }

    private void tick() {
        tickCounter++;
        refreshOwner();
        rebuildMaids();

        WabSession current = session;
        if (current != null) {
            if (!current.isClosed()) current.tick();
            else session = null;
        }

        dispatcher.tick();
        tickFollow();
        autonomy.tick();

        if (tickCounter % 40 == 0) {
            updateBeaconMaids();
            if (connected()) WabHud.tick(this);
        }
        if (tickCounter % 100 == 0 && connected()) sendState(false);
    }

    private void tickFollow() {
        if (followTargets.isEmpty() || tickCounter % 20 != 0) return;
        followTargets.entrySet().removeIf(entry -> {
            EntityMaid maid = maids.get(entry.getKey());
            ServerPlayer target = server.getPlayerList().getPlayer(entry.getValue());
            if (maid == null || !maid.isAlive() || target == null || maid.level() != target.level()) return true;
            if (dispatcher.hasJob(maid)) return false;
            double distance = maid.distanceToSqr(target);
            if (distance > 9) maid.getNavigation().moveTo(target, 0.9);
            else if (distance < 4) maid.getNavigation().stop();
            return false;
        });
    }

    public void setFollow(EntityMaid maid, ServerPlayer target) {
        followTargets.put(maid.getUUID(), target.getUUID());
    }

    public void clearFollow(EntityMaid maid) {
        followTargets.remove(maid.getUUID());
    }

    private void refreshOwner() {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        ServerPlayer first = players.isEmpty() ? null : players.get(0);
        if (first != null) {
            ownerName = first.getGameProfile().getName();
            worldName = server.getWorldData().getLevelName();
            if (owner == null || !owner.getUUID().equals(first.getUUID())) {
                boolean hadOwner = owner != null;
                owner = first;
                WabSession current = session;
                if (hadOwner && current != null) current.close("owner changed");
                updateBeaconMaids();
            }
        } else {
            owner = null;
        }
    }

    public ServerPlayer currentOwner() {
        if (owner != null && !owner.isRemoved()) return owner;
        return ownerName.isEmpty() ? null : server.getPlayerList().getPlayerByName(ownerName);
    }

    private void rebuildMaids() {
        if (tickCounter % 20 != 0) return;
        Map<UUID, EntityMaid> fresh = new HashMap<>();
        UUID ownerId = owner == null ? null : owner.getUUID();
        for (ServerLevel level : server.getAllLevels()) {
            for (EntityMaid maid : level.getEntities(EntityMaid.TYPE, e -> e.isAlive() && e.isTame()
                    && (ownerId == null || ownerId.equals(e.getOwnerUUID())))) {
                fresh.put(maid.getUUID(), maid);
            }
        }
        maids.clear();
        maids.putAll(fresh);
    }

    private void updateBeaconMaids() {
        JsonArray array = new JsonArray();
        for (EntityMaid maid : maids.values()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", maid.getUUID().toString());
            item.addProperty("name", cleanName(maid));
            item.addProperty("model", maid.getModelId());
            item.addProperty("health", Math.round(maid.getHealth() * 10) / 10.0);
            item.addProperty("bound", boundCharacters.containsKey(maid.getUUID()));
            array.add(item);
        }
        beaconMaids = array;
    }

    public JsonArray beaconMaids() {
        return beaconMaids;
    }

    public JsonObject beacon(String nonce) {
        JsonObject out = new JsonObject();
        out.addProperty("magic", MAGIC);
        out.addProperty("v", Integer.parseInt(PROTOCOL));
        out.addProperty("type", "beacon");
        out.addProperty("nonce", nonce);
        out.addProperty("instance", instanceId);
        out.addProperty("mod", TouhouLittleMaid.MOD_ID);
        out.addProperty("modVersion", modVersion());
        out.addProperty("mc", "1.20.1");
        out.addProperty("world", worldName);
        out.addProperty("player", ownerName);
        out.add("maids", beaconMaids);
        return out;
    }

    public String hello(String nonce, String token) {
        JsonObject out = beacon(nonce);
        out.addProperty("type", "hello");
        out.addProperty("token", token);
        return out.toString();
    }

    private static String modVersion() {
        try {
            return ModList.get().getModContainerById(TouhouLittleMaid.MOD_ID)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("");
        } catch (Exception e) {
            return "";
        }
    }

    private static String makeInstanceId(MinecraftServer server) {
        try {
            String raw = server.getWorldData().getLevelName() + "|" + server.overworld().getSeed();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", hash[i]));
            return sb.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
    }

    public void sendState(boolean force) {
        WabSession current = session;
        if (current == null || current.isClosed()) return;
        JsonObject msg = new JsonObject();
        msg.addProperty("v", Integer.parseInt(PROTOCOL));
        msg.addProperty("type", "state");
        msg.add("world", WabSensors.world(this));
        JsonArray list = new JsonArray();
        for (EntityMaid maid : maids.values()) {
            list.add(WabSensors.snapshot(this, maid));
        }
        msg.add("maids", list);
        String hash = msg.toString();
        long now = System.currentTimeMillis();
        if (!force && hash.equals(lastStateHash) && now - lastStateAt < 30000) return;
        lastStateHash = hash;
        lastStateAt = now;
        current.send(msg);
    }

    public void sendEvent(String kind, EntityMaid maid, String text, double importance,
                          int affectionDelta, String reason) {
        WabSession current = session;
        if (current == null || current.isClosed()) return;
        JsonObject ev = new JsonObject();
        ev.addProperty("v", Integer.parseInt(PROTOCOL));
        ev.addProperty("type", "event");
        ev.addProperty("kind", kind);
        ev.addProperty("maid", maid.getUUID().toString());
        ev.addProperty("text", text);
        ev.addProperty("importance", importance);
        if (affectionDelta != 0) {
            ev.addProperty("affection_delta", affectionDelta);
            ev.addProperty("reason", reason == null ? text : reason);
        }
        ev.add("snapshot", WabSensors.snapshot(this, maid));
        current.send(ev);
    }

    public void sendChat(EntityMaid maid, String text) {
        WabSession current = session;
        if (current == null || current.isClosed()) return;
        JsonObject chat = new JsonObject();
        chat.addProperty("v", Integer.parseInt(PROTOCOL));
        chat.addProperty("type", "chat");
        chat.addProperty("maid", maid.getUUID().toString());
        chat.addProperty("name", cleanName(maid));
        chat.addProperty("text", text);
        current.send(chat);
    }

    private boolean eventCoolDown(EntityMaid maid, long millis) {
        long now = System.currentTimeMillis();
        Long last = lastEventAt.get(maid.getUUID());
        if (last != null && now - last < millis) return false;
        lastEventAt.put(maid.getUUID(), now);
        return true;
    }

    private EntityMaid nearestMaid(Player player, double range) {
        EntityMaid best = null;
        double bestDistance = range * range;
        for (EntityMaid maid : maids.values()) {
            if (!maid.isAlive() || maid.level() != player.level() || !isOwned(maid)) continue;
            double distance = maid.distanceToSqr(player);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = maid;
            }
        }
        return best;
    }

    private static UUID uuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (Exception e) {
            return null;
        }
    }

    public static void shutdown() {
        WabBridge old = instance;
        instance = null;
        if (old == null) return;
        old.autonomy.flush();
        old.discovery.stop();
        WabSession current = old.session;
        if (current != null) current.close("server stopping");
        old.dispatcher.cancelAll("server stopping");
        TouhouLittleMaid.LOGGER.info("[WAB] bridge stopped");
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        shutdown();
        if (WabConfig.enabled()) {
            instance = new WabBridge(event.getServer());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        shutdown();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && instance != null) instance.tick();
    }

    @SubscribeEvent
    public static void onChat(ServerChatEvent event) {
        WabBridge bridge = instance;
        if (bridge == null || !bridge.connected()) return;
        String text = event.getRawText().trim();
        if (text.isEmpty() || text.startsWith("/")) return;
        EntityMaid maid = bridge.nearestMaid(event.getPlayer(), WabConfig.chatRange());
        if (maid == null) return;
        event.setCanceled(true);
        bridge.sendChat(maid, text);
        event.getPlayer().displayClientMessage(
                Component.literal("对 " + cleanName(maid) + " 说：" + text).withStyle(ChatFormatting.GRAY), true);
    }

    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getSide() != LogicalSide.SERVER) return;
        if (!(event.getTarget() instanceof EntityMaid maid)) return;
        WabBridge bridge = instance;
        if (bridge == null || !bridge.isOwned(maid)) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!bridge.eventCoolDown(maid, 20000)) return;
        ItemStack stack = event.getItemStack();
        String what = stack.isEmpty() ? "空着手" : "拿着 " + stack.getHoverName().getString();
        bridge.sendEvent("interaction", maid,
                player.getName().getString() + " 走近她，" + what + " 和她互动了一下。",
                0.35, stack.isEdible() ? 1 : 0, "被主人温柔地回应");
    }

    @SubscribeEvent
    public static void onHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof EntityMaid maid)) return;
        WabBridge bridge = instance;
        if (bridge == null || !bridge.isOwned(maid)) return;
        if (!bridge.eventCoolDown(maid, 5000)) return;
        Entity source = event.getSource().getEntity();
        String by = source == null ? "不知从哪来的伤害" : "被 " + source.getName().getString() + " 打伤";
        boolean byOwner = source instanceof Player player && bridge.owner != null
                && player.getUUID().equals(bridge.owner.getUUID());
        bridge.sendEvent("hurt", maid,
                by + "，掉了 " + Math.round(event.getAmount() * 10) / 10.0 + " 点生命。",
                0.7, byOwner ? -1 : 0, byOwner ? "被主人弄疼了" : "在游戏里受伤");
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof EntityMaid maid)) return;
        WabBridge bridge = instance;
        if (bridge == null || !bridge.isOwned(maid)) return;
        bridge.sendEvent("death", maid,
                cleanName(maid) + " 在 Minecraft 里倒下了。",
                0.95, -2, "她在游戏里死亡");
    }
}
