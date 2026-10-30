package com.github.tartaricacid.touhoulittlemaid.compat.wab;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import static com.github.tartaricacid.touhoulittlemaid.util.ResourceLocationUtil.getResourceLocation;

/**
 * WAB 合成好的音频，按 512 KiB 一块转到主人客户端，客户端拼完整再播。
 * 不整个塞一帧：WAV 不压缩，长一点的回复会把单包顶到网络层的上限之外。
 */
public record WabVoiceAudio(int maid, int request, int part, int parts, byte[] data) implements CustomPacketPayload {
    private static final int MAX_BYTES = 1_000_000;

    public static final CustomPacketPayload.Type<WabVoiceAudio> TYPE =
            new CustomPacketPayload.Type<>(getResourceLocation("wab_voice_audio"));

    public static final StreamCodec<FriendlyByteBuf, WabVoiceAudio> STREAM_CODEC = StreamCodec.of((buf, message) -> {
        byte[] data = message.data();
        boolean tooBig = data.length > MAX_BYTES;
        buf.writeVarInt(message.maid());
        buf.writeVarInt(message.request());
        buf.writeVarInt(message.part());
        buf.writeVarInt(message.parts());
        buf.writeByteArray(tooBig ? new byte[0] : data);
    }, buf -> {
        int maid = buf.readVarInt();
        int request = buf.readVarInt();
        int part = buf.readVarInt();
        int parts = buf.readVarInt();
        byte[] data = buf.readByteArray(MAX_BYTES);
        return new WabVoiceAudio(maid, request, part, parts, data);
    });

    public WabVoiceAudio {
        data = data == null ? new byte[0] : data;
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(WabVoiceAudio message, IPayloadContext context) {
        if (context.flow().isClientbound()) {
            context.enqueueWork(() -> onHandle(message));
        }
    }

    @OnlyIn(Dist.CLIENT)
    private static void onHandle(WabVoiceAudio message) {
        WabVoiceClient.onAudio(message);
    }
}
