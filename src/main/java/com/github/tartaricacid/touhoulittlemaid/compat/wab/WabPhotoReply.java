package com.github.tartaricacid.touhoulittlemaid.compat.wab;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import static com.github.tartaricacid.touhoulittlemaid.util.ResourceLocationUtil.getResourceLocation;

/**
 * 客户端把画面交回服务器，由 {@link WabBridge#onPhoto} 转成一帧 photo 交给 WAB。
 * 字节在这里就钳一次：包超过上限会被 vanilla 直接拒掉，那比拍不到更难看。
 */
public record WabPhotoReply(int request, byte[] png, int width, int height, String error) implements CustomPacketPayload {
    private static final int MAX_BYTES = 1_200_000;

    public static final CustomPacketPayload.Type<WabPhotoReply> TYPE =
            new CustomPacketPayload.Type<>(getResourceLocation("wab_photo_reply"));

    /** 手写编解码：readByteArray 的上限、writeUtf 的长度钳制都得留在 FriendlyByteBuf 那套签名上。 */
    public static final StreamCodec<FriendlyByteBuf, WabPhotoReply> STREAM_CODEC = StreamCodec.of((buf, message) -> {
        boolean tooBig = message.png().length > MAX_BYTES;
        buf.writeInt(message.request());
        buf.writeInt(tooBig ? 0 : message.width());
        buf.writeInt(tooBig ? 0 : message.height());
        buf.writeUtf(tooBig ? "这张图压完还有 " + (message.png().length / 1024) + " KB，太大了，没往上传。"
                : message.error(), 200);
        buf.writeByteArray(tooBig ? new byte[0] : message.png());
    }, buf -> {
        int request = buf.readInt();
        int width = buf.readInt();
        int height = buf.readInt();
        String error = buf.readUtf(200);
        byte[] png = buf.readByteArray(1_000_000);
        return new WabPhotoReply(request, png, width, height, error);
    });

    public WabPhotoReply {
        png = png == null ? new byte[0] : png;
        error = error == null ? "" : error;
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(WabPhotoReply message, IPayloadContext context) {
        if (context.flow().isServerbound()) {
            context.enqueueWork(() -> {
                WabBridge bridge = WabBridge.get();
                if (bridge != null) {
                    bridge.onPhoto(message.request(), message.png(), message.width(), message.height(), message.error());
                }
            });
        }
    }
}
