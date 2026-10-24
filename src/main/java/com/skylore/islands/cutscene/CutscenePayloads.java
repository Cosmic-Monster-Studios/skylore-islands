package com.skylore.islands.cutscene;

import com.skylore.islands.SkyloreIslands;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** The two messages the arrival cutscene needs: server tells the client what to play, the client says where it is. */
public final class CutscenePayloads {
    /** OFFER: wait until no screen is open, then answer READY. BEGIN: play it for this species. */
    public static final int OFFER = 0;
    public static final int BEGIN = 1;

    /** READY: origin picked, start. SNAP: the sky change. IMPACT: the ship hits. END: done or skipped. */
    public static final int READY = 0;
    public static final int SNAP = 1;
    public static final int END = 2;
    public static final int IMPACT = 3;

    private CutscenePayloads() {}

    /** {@code start} is where the timeline begins, in seconds, and {@code hold} freezes it there; only the test command sets them. */
    public record ToClient(int kind, BlockPos stand, String species, boolean skippable, float start, boolean hold) implements CustomPacketPayload {
        public static final Type<ToClient> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SkyloreIslands.MOD_ID, "cutscene_to_client"));
        public static final StreamCodec<ByteBuf, ToClient> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ToClient::kind,
                BlockPos.STREAM_CODEC, ToClient::stand,
                ByteBufCodecs.STRING_UTF8, ToClient::species,
                ByteBufCodecs.BOOL, ToClient::skippable,
                ByteBufCodecs.FLOAT, ToClient::start,
                ByteBufCodecs.BOOL, ToClient::hold,
                ToClient::new);

        @Override
        public Type<ToClient> type() {
            return TYPE;
        }
    }

    public record ToServer(int kind) implements CustomPacketPayload {
        public static final Type<ToServer> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SkyloreIslands.MOD_ID, "cutscene_to_server"));
        public static final StreamCodec<ByteBuf, ToServer> CODEC = ByteBufCodecs.VAR_INT.map(ToServer::new, ToServer::kind);

        @Override
        public Type<ToServer> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToClient(ToClient.TYPE, ToClient.CODEC,
                (payload, context) -> context.enqueueWork(() -> com.skylore.islands.client.cutscene.CutscenePlayer.receive(payload)));
        registrar.playToServer(ToServer.TYPE, ToServer.CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        ArrivalCutscene.receive(player, payload.kind());
                    }
                }));
    }
}
