package net.fabneo.nfhandshake;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 复刻 NeoForge {@code ModdedNetworkPayload}（通道 {@code neoforge:network}）的线格式。
 *
 * <p><b>这是客户端对 {@code neoforge:register} 空查询的正确应答类型</b>。
 * 依据 NeoForge 21.1.248 产物反汇编：
 * <pre>
 * ModdedNetworkPayload(NetworkPayloadSetup setup)
 *   STREAM_CODEC = StreamCodec.composite(NetworkPayloadSetup.STREAM_CODEC, ModdedNetworkPayload::setup, ...)
 *
 * NetworkPayloadSetup(Map&lt;ConnectionProtocol, Map&lt;Identifier, NetworkChannel&gt;&gt; channels)
 * NetworkChannel(Identifier id, String chosenVersion)
 * </pre>
 *
 * <p>线格式：
 * <pre>
 * VAR_INT mapSize                       // 协议段数
 *   重复 mapSize 次:
 *     VAR_INT protocolOrdinal           // 1=PLAY, 4=CONFIGURATION
 *     VAR_INT channelCount
 *       重复 channelCount 次:
 *         UTF8 id                       // 通道全名
 *         UTF8 chosenVersion            // 协商选中的版本
 * </pre>
 */
public record NeoForgeNetworkPayload(Map<Integer, Map<String, String>> setup)
		implements CustomPacketPayload {

	/** {@code neoforge:network} */
	public static final Identifier ID = Identifier.fromNamespaceAndPath("neoforge", "network");

	public static final Type<NeoForgeNetworkPayload> TYPE = new Type<>(ID);

	public static final StreamCodec<FriendlyByteBuf, NeoForgeNetworkPayload> STREAM_CODEC = StreamCodec.of(
			(FriendlyByteBuf buf, NeoForgeNetworkPayload payload) -> encode(buf, payload),
			(FriendlyByteBuf buf) -> decode(buf));

	static NeoForgeNetworkPayload decode(FriendlyByteBuf buf) {
		int mapSize = buf.readVarInt();
		Map<Integer, Map<String, String>> map = new LinkedHashMap<>();
		for (int m = 0; m < mapSize; m++) {
			int protocol = buf.readVarInt();
			int count = buf.readVarInt();
			Map<String, String> channels = new LinkedHashMap<>();
			for (int i = 0; i < count; i++) {
				String id = buf.readUtf();
				String ver = buf.readUtf();
				channels.put(id, ver);
			}
			map.put(protocol, channels);
		}
		int remaining = buf.readableBytes();
		if (remaining > 0) {
			byte[] rest = new byte[Math.min(remaining, 128)];
			buf.getBytes(buf.readerIndex(), rest);
			StringBuilder sb = new StringBuilder();
			for (byte b : rest) {
				sb.append(String.format("%02x", b));
			}
			NfHandshakeClient.LOGGER.info("[TAIL-NET] neoforge:network 解析后剩余 {} 字节 hex={}，已跳过",
					remaining, sb);
			buf.skipBytes(remaining);
		}
		return new NeoForgeNetworkPayload(map);
	}

	static void encode(FriendlyByteBuf buf, NeoForgeNetworkPayload payload) {
		buf.writeVarInt(payload.setup.size());
		for (Map.Entry<Integer, Map<String, String>> e : payload.setup.entrySet()) {
			buf.writeVarInt(e.getKey());
			buf.writeVarInt(e.getValue().size());
			for (Map.Entry<String, String> c : e.getValue().entrySet()) {
				buf.writeUtf(c.getKey());
				buf.writeUtf(c.getValue() == null ? "" : c.getValue());
			}
		}
	}

	@Override
	public Type<NeoForgeNetworkPayload> type() {
		return TYPE;
	}
}
