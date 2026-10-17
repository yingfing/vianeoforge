package net.fabneo.nfhandshake;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * 【诊断模式】入站处理器：记录服务器发来的自定义负载包，用于定位流错位。
 *
 * <p>历史：早期版本在这里"静默丢弃非 minecraft 命名空间负载"，方向是错的
 * （硬碰需要这些包通过）。现在改为<b>纯记录、不修改</b>：
 * 记录 {@code ClientboundCustomPayloadPacket} 的 payload 类型与字节长度，
 * 用于与服务器实际发送的长度比对，定位 "was larger than I expected, found N bytes extra"。
 *
 * <p>只记录，不丢弃、不修改 —— 保证不影响正常解码流程。
 */
@ChannelHandler.Sharable
public class NettyModdedPayloadFilter extends ChannelInboundHandlerAdapter {

	public static final String NAME = "nfhandshake-dump";

	public static final NettyModdedPayloadFilter INSTANCE = new NettyModdedPayloadFilter();

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		try {
			if (msg instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket pkt) {
				var payload = pkt.payload();
				String id = payload.type() != null && payload.type().id() != null
						? payload.type().id().toString() : "?";
				Class<?> cls = payload.getClass();
				int len = -1;
				try {
					var m = cls.getMethod("data");
					Object data = m.invoke(payload);
					if (data instanceof byte[] b) {
						len = b.length;
					}
				} catch (Throwable ignored) {
				}
				NfHandshakeClient.LOGGER.info("[NETTY-IN] clientbound custom_payload id={} impl={} dataLen={}",
						id, cls.getSimpleName(), len);
			}
		} catch (Throwable ignored) {
		}
		super.channelRead(ctx, msg);
	}
}
