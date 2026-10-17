package net.fabneo.nfhandshake.mixin;

import net.fabneo.nfhandshake.NfHandshakeClient;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 {@code ClientCommonPacketListenerImpl.handleCustomPayload(ClientboundCustomPayloadPacket)}
 * 的 HEAD 处拦截，丢弃非 {@code minecraft} 命名空间的负载。
 *
 * <p>方法描述符取自 1.21.11 官方映射（{@code ref/client-1.21.11-mojmap.txt}）：
 * <pre>
 * net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl -> hia:
 *     180:192:void handleCustomPayload(net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket) -> a
 * </pre>
 *
 * <p>{@code priority = 1} 与 ViaFabricPlus 一致，确保先于 Fabric API 的负载分发逻辑执行。
 */
@Mixin(value = ClientCommonPacketListenerImpl.class, priority = 1)
public abstract class ClientCommonPacketListenerImplMixin {

	@Inject(
			method = "handleCustomPayload(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V",
			at = @At("HEAD"),
			cancellable = true)
	private void nfhandshake$dropModdedPayload(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
		if (NfHandshakeClient.shouldDrop(packet.payload())) {
			ci.cancel();
		}
	}
}
