package com.github.tartaricacid.touhoulittlemaid.compat.wab;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import static com.github.tartaricacid.touhoulittlemaid.util.ResourceLocationUtil.getResourceLocation;

/**
 * 由 WAB 主动触发的预设动画。与捡雪球那种固定 ID 不同，这里允许传动画名，
 * 模型没有这个动画时由 GeckoLib 自己忽略，不会中断其他动作。
 */
public record WabAnimationMessage(int maidId, String animation, int durationTicks) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<WabAnimationMessage> TYPE =
            new CustomPacketPayload.Type<>(getResourceLocation("wab_animation"));

    public static final StreamCodec<ByteBuf, WabAnimationMessage> STREAM_CODEC = StreamCodec.of((buf, message) -> {
        buf.writeInt(message.maidId());
        ByteBufCodecs.STRING_UTF8.encode(buf, safe(message.animation()));
        buf.writeInt(message.durationTicks());
    }, buf -> new WabAnimationMessage(buf.readInt(), buf.readUtf(128), buf.readInt()));

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 动画名可能来自 WAB 传的手势参数。编码超长会直接抛，
     * 落单的代理项也编码不了——这里兜一层，别让一个包把会话拖倒。
     */
    private static String safe(String raw) {
        if (raw == null) return "idle";
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), 120));
        for (int i = 0; i < raw.length() && sb.length() < 120; i++) {
            char c = raw.charAt(i);
            if (!Character.isSurrogate(c)) {
                sb.append(c);
            }
        }
        return sb.isEmpty() ? "idle" : sb.toString();
    }

    public static void handle(WabAnimationMessage message, IPayloadContext context) {
        if (context.flow().isClientbound()) {
            context.enqueueWork(() -> handleClient(message));
        }
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(WabAnimationMessage message) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        if (level.getEntity(message.maidId()) instanceof EntityMaid maid) {
            maid.wabAnimation = message.animation();
            maid.wabAnimationUntil = System.currentTimeMillis() + Math.max(1, message.durationTicks()) * 50L;
        }
    }
}
