package com.github.tartaricacid.touhoulittlemaid.compat.wab;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.network.NetworkHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import static com.github.tartaricacid.touhoulittlemaid.util.ResourceLocationUtil.getResourceLocation;

/**
 * 客户端把一段录音（WAV）交给服务器，由 {@link WabVoice#requestStt} 转成一帧 stt 问 WAB。
 * 客户端不直接连 WAB：桥的 TCP 会话在服务端，专用服务器上客户端也碰不到那个回环地址。
 */
public record WabVoiceClip(int maid, byte[] wav) implements CustomPacketPayload {
    private static final int MAX_BYTES = 1_500_000;

    public static final CustomPacketPayload.Type<WabVoiceClip> TYPE =
            new CustomPacketPayload.Type<>(getResourceLocation("wab_voice_clip"));

    public static final StreamCodec<FriendlyByteBuf, WabVoiceClip> STREAM_CODEC = StreamCodec.of((buf, message) -> {
        byte[] wav = message.wav();
        boolean tooBig = wav.length > MAX_BYTES;
        buf.writeVarInt(message.maid());
        buf.writeByteArray(tooBig ? new byte[0] : wav);
    }, buf -> {
        int maid = buf.readVarInt();
        byte[] wav = buf.readByteArray(MAX_BYTES);
        return new WabVoiceClip(maid, wav);
    });

    public WabVoiceClip {
        wav = wav == null ? new byte[0] : wav;
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(WabVoiceClip message, IPayloadContext context) {
        if (!context.flow().isServerbound()) return;
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sender)) return;
            Entity entity = sender.level().getEntity(message.maid());
            if (!(entity instanceof EntityMaid maid) || !maid.isOwnedBy(sender) || !maid.isAlive()) return;
            if (message.wav().length == 0) {
                NetworkHandler.sendToClientPlayer(new WabVoiceText(message.maid(), false, "", "这段录音是空的。"), sender);
                return;
            }
            WabBridge bridge = WabBridge.get();
            if (bridge == null) {
                NetworkHandler.sendToClientPlayer(new WabVoiceText(message.maid(), false, "", "WAB 没有连接。"), sender);
                return;
            }
            WabVoice.requestStt(bridge, maid, sender, message.wav());
        });
    }
}
