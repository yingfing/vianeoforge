package net.fabneo.nfhandshake.mixin;

import net.fabneo.nfhandshake.NfHandshakeClient;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 配置阶段的负载入口。
 *
 * <p>方法描述符取自 1.21.11 官方映射：
 * <pre>
 * net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl -> hib:
 *     70:71:void handleCustomPayload(net.minecraft.network.protocol.common.custom.CustomPacketPayload) -> a
 * </pre>
 *
 * <p>配置阶段的数据包分发走的是这个重载（参数是已经解码后的 {@code CustomPacketPayload}），
 * 所以必须与父类的那一个分开注入。
 */
@Mixin(value = ClientConfigurationPacketListenerImpl.class, priority = 1)
public abstract class ClientConfigurationPacketListenerImplMixin {

	@Inject(
			method = "handleCustomPayload(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V",
			at = @At("HEAD"),
			cancellable = true)
	private void nfhandshake$dropModdedPayload(CustomPacketPayload payload, CallbackInfo ci) {
		if (NfHandshakeClient.shouldDrop(payload)) {
			ci.cancel();
		}
	}
}
