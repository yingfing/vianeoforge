package net.fabneo.nfhandshake;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 复刻 NeoForge {@code ModdedNetworkQueryPayload} 的线格式（neoforge:register）。
 *
 * <p>依据 NeoForge 21.1.248 源码（{@code ModdedNetworkQueryPayload.java}）：
 * <pre>
 * ModdedNetworkQueryPayload(Map&lt;ConnectionProtocol, Set&lt;ModdedNetworkQueryComponent&gt;&gt; queries)
 * STREAM_CODEC = composite(
 *     ByteBufCodecs.map(IdentityHashMap::new,
 *         ByteBufCodecs.idMapper(b -&gt; ConnectionProtocol.values()[b], ConnectionProtocol::ordinal),
 *         ByteBufCodecs.collection(HashSet::new, ModdedNetworkQueryComponent.STREAM_CODEC)), ...)
 * </pre>
 *
 * <p><b>重要</b>：服务器的查询可以包含<b>多个协议段</b>（如 PLAY 与 CONFIGURATION 各一段）。
 * 因此本类保留完整 map，并在回复时逐段回等量通道，不能只保留一段
 * （早期实现只存最后一个 protocol，导致协商协议错位、被服务器判为 Incompatible）。
 *
 * <p>线格式：
 * <pre>
 * VAR_INT mapSize
 *   重复 mapSize 次:
 *     VAR_INT protocolOrdinal   // 0=HANDSHAKING 1=PLAY 2=STATUS 3=LOGIN 4=CONFIGURATION
 *     VAR_INT componentCount
 *       重复 componentCount 次:
 *         UTF8 id / UTF8 version / BOOL hasFlow / [VAR_INT flowOrdinal] / BOOL optional
 * </pre>
 */
public record NeoForgeRegisterPayload(Map<Integer, List<Component>> queries)
		implements CustomPacketPayload {

	public static final Identifier ID = Identifier.fromNamespaceAndPath("neoforge", "register");

	public static final Type<NeoForgeRegisterPayload> TYPE = new Type<>(ID);

	/**
	 * 单个通道描述，对应 NeoForge {@code ModdedNetworkQueryComponent}。
	 *
	 * @param flow {@link Optional}，0 = SERVERBOUND，1 = CLIENTBOUND
	 */
	public record Component(String id, String version, Optional<Integer> flow, boolean optional) {
	}

	public static final StreamCodec<FriendlyByteBuf, NeoForgeRegisterPayload> STREAM_CODEC = StreamCodec.of(
			(FriendlyByteBuf buf, NeoForgeRegisterPayload payload) -> encode(buf, payload),
			(FriendlyByteBuf buf) -> decode(buf));

	/** 构造只含单个协议段的负载（用于主动声明）。 */
	public static NeoForgeRegisterPayload ofSingle(int protocol, List<Component> components) {
		Map<Integer, List<Component>> m = new LinkedHashMap<>();
		m.put(protocol, components);
		return new NeoForgeRegisterPayload(m);
	}

	static NeoForgeRegisterPayload decode(FriendlyByteBuf buf) {
		int mapSize = buf.readVarInt();
		Map<Integer, List<Component>> map = new LinkedHashMap<>();
		for (int m = 0; m < mapSize; m++) {
			int protocol = buf.readVarInt();
			int count = buf.readVarInt();
			List<Component> list = new ArrayList<>(Math.max(0, Math.min(count, 4096)));
			for (int i = 0; i < count; i++) {
				String id = buf.readUtf();
				String ver = buf.readUtf();
				Optional<Integer> flow = Optional.empty();
				if (buf.readBoolean()) {
					flow = Optional.of(buf.readVarInt());
				}
				boolean optional = buf.readBoolean();
				list.add(new Component(id, ver, flow, optional));
			}
			map.put(protocol, list);
		}
		// 【探测依据】把服务端查询包的实际内容记下来。
		//
		// 服务器查询包由 ModdedNetworkQueryPayload.fromRegistry() 生成，它会把服务端
		// registry 里**每一个**通道都放进去（含 id / version / flow）。
		// 也就是说：服务端主动把"它有哪些通道"告诉了我们 ——
		// 这正是"自动探测通道表"的依据，不需要事先手抄。
		{
			StringBuilder sb = new StringBuilder();
			map.forEach((proto, list) -> sb.append(proto).append(':').append(list.size()).append(' '));
			NfHandshakeClient.LOGGER.info(
					"[PROBE] 收到服务端通道查询：{} 段（{}），共 {} 个通道",
					map.size(), sb.toString().trim(),
					map.values().stream().mapToInt(List::size).sum());
			// 记录前若干个通道名 + flow，作为探测结果的可核对样本
			int shown = 0;
			for (Map.Entry<Integer, List<Component>> e : map.entrySet()) {
				for (Component c : e.getValue()) {
					if (shown++ >= 8) {
						break;
					}
					NfHandshakeClient.LOGGER.info("[PROBE]   段{} {} version={} flow={} optional={}",
							e.getKey(), c.id(), c.version(),
							c.flow().map(String::valueOf).orElse("-"), c.optional());
				}
			}
		}
		// 兜底：服务器下发的查询包可能比我们解析的结构更长（早期实测多 8991 字节）。
		// 必须把剩余字节消费掉，否则 Netty 判为 "larger than I expected" 并断连。
		int remaining = buf.readableBytes();
		if (remaining > 0) {
			byte[] rest = new byte[Math.min(remaining, 4096)];
			int idx = buf.readerIndex();
			buf.getBytes(idx, rest);
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < Math.min(rest.length, 64); i++) {
				sb.append(String.format("%02x", rest[i]));
			}
			NfHandshakeClient.LOGGER.info(
					"[TAIL] neoforge:register 解析后剩余 {} 字节（前 64 字节 hex: {}），已跳过",
					remaining, sb);
			buf.skipBytes(remaining);
		}
		return new NeoForgeRegisterPayload(map);
	}

	static void encode(FriendlyByteBuf buf, NeoForgeRegisterPayload payload) {
		// 【关键订正】必须把**全部协议段**都写回去。
		//
		// 旧的实现只写 map 的第一段（findFirst），理由是与机器人样本 iterate3.js 对齐；
		// 但服务器的查询包来自 ModdedNetworkQueryPayload.fromRegistry()，它会把
		// registry 里**每个** ConnectionProtocol 段都放进去（PLAY=1、CONFIGURATION=4 等）。
		// 而服务端 NetworkRegistry.initializeNeoForgeConnection 是**逐段协商**的：
		//
		//   for (ConnectionProtocol protocol : PAYLOAD_REGISTRATIONS.keySet()) {
		//       negotiate(server.get(protocol), client.getOrDefault(protocol, emptySet()));
		//   }
		//
		// 只回一段 → 其余段被判为空集 → missing.server.client → 协商失败断连。
		// 实测：390 个通道全部报 missing.server.client，正是这个原因。
		Map<Integer, List<Component>> map = payload.queries;
		buf.writeVarInt(map.size());
		for (Map.Entry<Integer, List<Component>> e : map.entrySet()) {
			buf.writeVarInt(e.getKey());
			List<Component> list = e.getValue();
			buf.writeVarInt(list.size());
			for (Component c : list) {
				buf.writeUtf(c.id);
				buf.writeUtf(c.version == null ? "" : c.version);
				if (c.flow.isPresent()) {
					buf.writeBoolean(true);
					buf.writeVarInt(c.flow.get());
				} else {
					buf.writeBoolean(false);
				}
				buf.writeBoolean(c.optional);
			}
		}
	}

	/** 本次负载包含的协议段（用于日志核对）。 */
	public String describeSegments() {
		StringBuilder sb = new StringBuilder();
		queries.forEach((proto, list) ->
				sb.append(proto).append(':').append(list.size()).append(' '));
		return sb.toString().trim();
	}

	@Override
	public Type<NeoForgeRegisterPayload> type() {
		return TYPE;
	}
}
