package net.fabneo.nfhandshake;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 复刻 NeoForge {@code ModdedNetworkSetupFailedPayload}
 * （通道 {@code neoforge:modded_network_setup_failed}）的线格式。
 *
 * <p><b>这是本项目最重要的诊断工具。</b>
 *
 * <p>NeoForge 服务端在 {@code NetworkRegistry.initializeNeoForgeConnection} 里协商失败时，
 * 会先发这个包（携带<b>逐通道的失败原因</b>）再断开连接：
 * <pre>
 * if (!negotiationResult.success()) {
 *     if (!negotiationResult.failureReasons().isEmpty()) {
 *         listener.send(new ModdedNetworkSetupFailedPayload(negotiationResult.failureReasons()));
 *     }
 *     listener.disconnect(...);
 * }
 * </pre>
 *
 * <p>而 Fabric 客户端没注册这个类型 → 它进了 {@code DiscardedPayload}
 * → <b>我们从来读不到失败原因</b>，只能靠猜
 * （历史上正因如此，把"协商被拒"误判成了"Netty 分包解码 bug"）。
 *
 * <p>注册本类型后，服务器会直接告诉我们：<b>哪个通道</b>、<b>违反了哪条规则</b>。
 * {@code NetworkComponentNegotiator} 会填的失败原因包括：
 * <ul>
 *   <li>{@code neoforge.network.negotiation.failure.missing.server.client}
 *       —— 服务端有该通道、我们没声明</li>
 *   <li>{@code ...failure.missing.client.server} —— 反向</li>
 *   <li>{@code ...failure.version.mismatch} —— version 不满足 String.equals</li>
 *   <li>{@code ...failure.flow.*.missing} / {@code .mismatch} —— flow 不一致</li>
 * </ul>
 *
 * <p>线格式（反汇编 {@code ModdedNetworkSetupFailedPayload.class} 确认）：
 * <pre>
 * ByteBufCodecs.map(LinkedHashMap::new,
 *     ResourceLocation.STREAM_CODEC,
 *     ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC)
 * </pre>
 * 即：
 * <pre>
 * VAR_INT mapSize
 *   重复 mapSize 次:
 *     Identifier id     + Component reason
 * </pre>
 */
public record NeoForgeSetupFailedPayload(Map<String, Component> failureReasons)
		implements CustomPacketPayload {

	/** {@code neoforge:modded_network_setup_failed} */
	public static final Identifier ID =
			Identifier.fromNamespaceAndPath("neoforge", "modded_network_setup_failed");

	public static final Type<NeoForgeSetupFailedPayload> TYPE = new Type<>(ID);

	public static final StreamCodec<FriendlyByteBuf, NeoForgeSetupFailedPayload> STREAM_CODEC =
			StreamCodec.of(
					(FriendlyByteBuf buf, NeoForgeSetupFailedPayload payload) -> encode(buf, payload),
					(FriendlyByteBuf buf) -> decode(buf));

	/** 客户端不会主动发这个包；encode 仅为满足 StreamCodec 契约。 */
	static void encode(FriendlyByteBuf buf, NeoForgeSetupFailedPayload payload) {
		Map<String, Component> map = payload.failureReasons;
		buf.writeVarInt(map.size());
		for (Map.Entry<String, Component> e : map.entrySet()) {
			buf.writeUtf(e.getKey());
			ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC.encode(buf, e.getValue());
		}
	}

	static NeoForgeSetupFailedPayload decode(FriendlyByteBuf buf) {
		int mapSize = buf.readVarInt();
		Map<String, Component> map = new LinkedHashMap<>();
		for (int i = 0; i < mapSize; i++) {
			String id = buf.readUtf();
			Component reason;
			try {
				reason = ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC.decode(buf);
			} catch (Throwable t) {
				// 解析失败也不应让连接崩掉：记下来，用占位文本继续
				reason = Component.literal("<decode failed: " + String.valueOf(t) + ">");
			}
			map.put(id, reason);
		}
		return new NeoForgeSetupFailedPayload(map);
	}

	@Override
	public Type<NeoForgeSetupFailedPayload> type() {
		return TYPE;
	}
}
