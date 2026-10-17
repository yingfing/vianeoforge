package net.fabneo.nfhandshake;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.core.Registry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

/**
 * 复刻 NeoForge {@code KnownRegistryDataMapsReplyPayload}
 * （通道 {@code neoforge:known_registry_data_maps_reply}）。
 *
 * <h2>为什么需要它</h2>
 * 与 FrozenRegistry 同构的**请求/应答**流程。服务端在配置阶段下发
 * {@code neoforge:known_registry_data_maps}（问客户端知道哪些 registry data map），
 * 然后等客户端回本包。实测：服务端发完该请求后**静默不动**。
 *
 * <h2>我们回什么</h2>
 * 回一个**空 map** —— 我们（Fabric 客户端）没有安装任何 NeoForge 模组，
 * 自然不知道任何 data map。这是诚实且安全的答复：
 * 服务端据此跳过 data map 同步（后续这些 data map 的效果缺失，
 * 与"模组内容不可见"的既有取舍一致）。
 *
 * <h2>线格式（反汇编确认）</h2>
 * <pre>
 * record KnownRegistryDataMapsReplyPayload(
 *     Map&lt;ResourceKey&lt;? extends Registry&lt;?&gt;&gt;, Collection&lt;Identifier&gt;&gt; dataMaps)
 *
 * STREAM_CODEC = composite(
 *     ByteBufCodecs.map(LinkedHashMap::new,
 *         NeoForgeStreamCodecs.registryKey(),                 // 注册表 key
 *         ByteBufCodecs.collection(ArrayList::new,
 *             ResourceLocation.STREAM_CODEC)),                // Identifier 集合
 *     KnownRegistryDataMapsReplyPayload::dataMaps,
 *     KnownRegistryDataMapsReplyPayload::new)
 * </pre>
 * 即：
 * <pre>
 * VAR_INT mapSize
 *   重复 mapSize 次:
 *     ResourceKey registry      // namespace+path
 *     VAR_INT count ; Identifier × count
 * </pre>
 */
public record NeoForgeKnownDataMapsReplyPayload(
		Map<ResourceKey<? extends Registry<?>>, Collection<Identifier>> dataMaps)
		implements CustomPacketPayload {

	/** {@code neoforge:known_registry_data_maps_reply} */
	public static final Identifier ID =
			Identifier.fromNamespaceAndPath("neoforge", "known_registry_data_maps_reply");

	public static final Type<NeoForgeKnownDataMapsReplyPayload> TYPE = new Type<>(ID);

	/**
	 * 手写 codec，避免依赖 {@code ByteBufCodecs.map(...)} 的具体重载
	 * （不同版本参数形式有差异，手写最稳，且我们只需要能读能写）。
	 */
	public static final StreamCodec<FriendlyByteBuf, NeoForgeKnownDataMapsReplyPayload> STREAM_CODEC =
			StreamCodec.of(
					(buf, payload) -> {
						buf.writeVarInt(payload.dataMaps.size());
						payload.dataMaps.forEach((key, ids) -> {
							// 【关键】必须用 key.identifier()，与 NeoForge 的
							// NeoForgeStreamCodecs.registryKey() 逐字节一致。
							// 反汇编 NeoForgeStreamCodecs$4 确认：
							//   encode: buf.writeResourceLocation(key.identifier())
							//   decode: ResourceKey.createRegistryKey(buf.readResourceLocation())
							// 注意**不是** key.registry()（那个返回的是注册表标签自身的 key，
							// 用它会导致写出的 Identifier 不属于该资源，实际表现为编码失败）。
							buf.writeIdentifier(key.identifier());
							buf.writeVarInt(ids.size());
							for (Identifier id : ids) {
								buf.writeIdentifier(id);
							}
						});
					},
					buf -> {
						int n = buf.readVarInt();
						Map<ResourceKey<? extends Registry<?>>, Collection<Identifier>> map =
								new LinkedHashMap<>();
						for (int i = 0; i < n; i++) {
							ResourceKey<? extends Registry<?>> key =
									ResourceKey.createRegistryKey(buf.readIdentifier());
							int c = buf.readVarInt();
							java.util.List<Identifier> ids = new java.util.ArrayList<>(c);
							for (int j = 0; j < c; j++) {
								ids.add(buf.readIdentifier());
							}
							map.put(key, ids);
						}
						return new NeoForgeKnownDataMapsReplyPayload(map);
					});

	/** 空答复：我们没有任何 data map 信息。 */
	public static NeoForgeKnownDataMapsReplyPayload empty() {
		return new NeoForgeKnownDataMapsReplyPayload(Map.of());
	}

	@Override
	public Type<NeoForgeKnownDataMapsReplyPayload> type() {
		return TYPE;
	}
}
