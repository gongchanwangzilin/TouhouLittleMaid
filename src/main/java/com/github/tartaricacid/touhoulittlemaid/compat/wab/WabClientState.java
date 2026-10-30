package com.github.tartaricacid.touhoulittlemaid.compat.wab;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/**
 * 客户端这边「哪些女仆归 WAB 管」的一份只读副本，由 {@link WabBindingSync} 推下来。
 * 按键那一下要立刻决定走哪条录音路线，不能先绕服务器问一圈——那点延迟会吃掉第一个字。
 */
public final class WabClientState {
    private static volatile Set<UUID> bound = Collections.emptySet();
    private static volatile boolean stt;

    private WabClientState() {
    }

    static void apply(WabBindingSync message) {
        bound = Set.copyOf(message.maids());
        stt = message.stt();
    }

    public static boolean sttFor(EntityMaid maid) {
        return stt && maid != null && bound.contains(maid.getUUID());
    }
}
