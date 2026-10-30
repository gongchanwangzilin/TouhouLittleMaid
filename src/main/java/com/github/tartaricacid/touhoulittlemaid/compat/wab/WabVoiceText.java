package com.github.tartaricacid.touhoulittlemaid.compat.wab;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import static com.github.tartaricacid.touhoulittlemaid.util.ResourceLocationUtil.getResourceLocation;

/** WAB 对一段录音的听写结果。失败也回一帧，客户端才不会一直等着那句「正在识别」。 */
public record WabVoiceText(int maid, boolean ok, String text, String error) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<WabVoiceText> TYPE =
            new CustomPacketPayload.Type<>(getResourceLocation("wab_voice_text"));

    public static final StreamCodec<FriendlyByteBuf, WabVoiceText> STREAM_CODEC = StreamCodec.of((buf, message) -> {
        buf.writeVarInt(message.maid());
        buf.writeBoolean(message.ok());
        buf.writeUtf(message.text() == null ? "" : message.text(), 2000);
        buf.writeUtf(message.error() == null ? "" : message.error(), 400);
    }, buf -> new WabVoiceText(buf.readVarInt(), buf.readBoolean(), buf.readUtf(2000), buf.readUtf(400)));

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(WabVoiceText message, IPayloadContext context) {
        if (context.flow().isClientbound()) {
            context.enqueueWork(() -> onHandle(message));
        }
    }

    @OnlyIn(Dist.CLIENT)
    private static void onHandle(WabVoiceText message) {
        WabVoiceClient.onText(message);
    }
}
