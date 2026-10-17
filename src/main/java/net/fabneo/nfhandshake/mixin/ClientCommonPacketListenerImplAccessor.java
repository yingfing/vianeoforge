package net.fabneo.nfhandshake.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 {@code ClientCommonPacketListenerImpl.connection}。
 *
 * <p>映射依据（{@code ref/client-1.21.11-mojmap.txt}）：
 * <pre>
 * net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl -> hia:
 *     net.minecraft.network.Connection connection -&gt; b
 * </pre>
 *
 * <p>{@code ClientConfigurationPacketListenerImpl}(hib) 与 {@code ClientPacketListener}
 * 都继承自它，所以配置阶段与游戏阶段都能用这一个 accessor。
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public interface ClientCommonPacketListenerImplAccessor {

	@Accessor("connection")
	Connection nfhandshake$getConnection();
}
