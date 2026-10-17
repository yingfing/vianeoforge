package net.fabneo.nfhandshake.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code Connection} 的 {@code channel} 字段在 1.21.11 里是 <b>private</b>：
 * <pre>
 * ref\mc-classes\wu.class (Connection) ->
 *   private io.netty.channel.Channel k;
 * </pre>
 * 映射表对应 {@code io.netty.channel.Channel channel -> k}。这里用 accessor 打开它，
 * 以便拿到 {@code ChannelPipeline} 注入 Netty 处理器。
 */
@Mixin(Connection.class)
public interface ConnectionAccessor {

	@Accessor("channel")
	Channel nfhandshake$getChannel();
}
