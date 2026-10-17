package net.fabneo.nfhandshake;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * 【诊断模式】原始字节抓取器 —— 挂在 Minecraft 的 PacketDecoder **之前**，
 * 打印服务器发来的裸字节（hex），用于定位 "larger than I expected, found N bytes extra"。
 *
 * <p>这是唯一能看到"Inbound 原始流"层真相的手段：包长度错位发生在
 * {@code PacketDecoder} 内部，payload codec 根本没机会被调用。
 *
 * <p>只读不改：抓取后原样 fireChannelRead。
 */
@ChannelHandler.Sharable
public class RawByteSniffer extends ChannelInboundHandlerAdapter {

	public static final String NAME = "nfhandshake-raw-sniff";
	public static final RawByteSniffer INSTANCE = new RawByteSniffer();

	private static int count = 0;
	private static final int MAX_DUMPS = 40;

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		try {
			if (msg instanceof ByteBuf buf && count < MAX_DUMPS) {
				int n = buf.readableBytes();
				// 只记录"大包"（可能是错位源），小包跳过避免刷屏
				if (n > 200) {
					count++;
					byte[] bytes = new byte[Math.min(n, 256)];
					buf.getBytes(buf.readerIndex(), bytes);
					StringBuilder sb = new StringBuilder();
					for (byte b : bytes) {
						sb.append(String.format("%02x", b));
					}
					NfHandshakeClient.LOGGER.info("[RAW-IN #{}] readable={} 前256字节hex={}",
							count, n, sb);
				}
			}
		} catch (Throwable ignored) {
		}
		super.channelRead(ctx, msg);
	}
}
