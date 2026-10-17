package net.fabneo.nfhandshake;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 复刻 NeoForge {@code FeatureFlagAcknowledgePayload}
 * （通道 {@code neoforge:feature_flags_ack}）。
 *
 * <h2>为什么需要它</h2>
 * NeoForge 配置阶段第四个**请求/应答**（前三个：FrozenRegistry、known_data_maps、extensible_enum）：
 * <pre>
 * 服务端 → neoforge:feature_flags      （下发特性开关，让客户端确认）
 * 客户端 → neoforge:feature_flags_ack  （★ 必须回，否则配置阶段停住）
 * </pre>
 * 实测：服务端发出 {@code feature_flags} 后静默等待。
 *
 * <h2>线格式（反汇编确认）</h2>
 * 无字段单例：{@code STREAM_CODEC = StreamCodec.unit(INSTANCE)}（线上零字节）。
 */
public final class NeoForgeFeatureFlagsAckPayload implements CustomPacketPayload {

	/** {@code neoforge:feature_flags_ack} */
	public static final Identifier ID =
			Identifier.fromNamespaceAndPath("neoforge", "feature_flags_ack");

	public static final Type<NeoForgeFeatureFlagsAckPayload> TYPE = new Type<>(ID);

	public static final NeoForgeFeatureFlagsAckPayload INSTANCE =
			new NeoForgeFeatureFlagsAckPayload();

	public static final StreamCodec<FriendlyByteBuf, NeoForgeFeatureFlagsAckPayload> STREAM_CODEC =
			StreamCodec.unit(INSTANCE);

	private NeoForgeFeatureFlagsAckPayload() {
	}

	@Override
	public Type<NeoForgeFeatureFlagsAckPayload> type() {
		return TYPE;
	}
}
