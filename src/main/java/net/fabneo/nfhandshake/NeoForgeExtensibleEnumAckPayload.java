package net.fabneo.nfhandshake;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 复刻 NeoForge {@code ExtensibleEnumAcknowledgePayload}
 * （通道 {@code neoforge:extensible_enum_ack}）。
 *
 * <h2>为什么需要它</h2>
 * NeoForge 配置阶段第三个**请求/应答**（前两个是 FrozenRegistry 与 known_data_maps）：
 * <pre>
 * 服务端 → neoforge:extensible_enum_data    （下发可扩展枚举清单，让客户端校验）
 * 客户端 → neoforge:extensible_enum_ack     （★ 必须回这个，否则配置阶段停住）
 * </pre>
 * 实测：服务端发出 {@code extensible_enum_data} 后**静默不动**，即在等本应答。
 *
 * <h2>线格式（反汇编确认）</h2>
 * 无字段单例：
 * <pre>
 * STREAM_CODEC = StreamCodec.unit(INSTANCE)    // 线上零字节
 * </pre>
 */
public final class NeoForgeExtensibleEnumAckPayload implements CustomPacketPayload {

	/** {@code neoforge:extensible_enum_ack} */
	public static final Identifier ID =
			Identifier.fromNamespaceAndPath("neoforge", "extensible_enum_ack");

	public static final Type<NeoForgeExtensibleEnumAckPayload> TYPE = new Type<>(ID);

	public static final NeoForgeExtensibleEnumAckPayload INSTANCE =
			new NeoForgeExtensibleEnumAckPayload();

	public static final StreamCodec<FriendlyByteBuf, NeoForgeExtensibleEnumAckPayload> STREAM_CODEC =
			StreamCodec.unit(INSTANCE);

	private NeoForgeExtensibleEnumAckPayload() {
	}

	@Override
	public Type<NeoForgeExtensibleEnumAckPayload> type() {
		return TYPE;
	}
}
