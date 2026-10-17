package net.fabneo.nfhandshake;

import java.util.Arrays;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 复刻 NeoForge {@code SplitPacketPayload}（通道 {@code neoforge:split}）。
 *
 * <p>NeoForge 的 {@code GenericPacketSplitter} 会把超过 MTU 的包拆成若干分片发送，
 * 分片用本 payload 承载。Fabric 客户端不认识它时，会走 {@code DiscardedPayload} 回退，
 * <b>只跳过声明的长度</b>，而分片实际携带的字节更多，于是产生
 * "packet was larger than I expected, found N bytes extra" 的流错位，
 * 后续所有包（如 code_of_conduct）连带错位、连接被断开。
 *
 * <p>因此必须显式注册本类型，让 Minecraft 按真实长度（{@code byte[]} 的 varint 长度前缀）
 * 完整读完分片，从而保持字节流对齐。
 *
 * <p>线格式（{@code byte[]}，即 {@code ByteBufCodecs.BYTE_ARRAY}）：
 * <pre>
 * VAR_INT length
 * BYTE[length]
 * </pre>
 */
public record NeoForgeSplitPayload(byte[] data) implements CustomPacketPayload {

	/** {@code neoforge:split} */
	public static final Identifier ID = Identifier.fromNamespaceAndPath("neoforge", "split");

	public static final Type<NeoForgeSplitPayload> TYPE = new Type<>(ID);

	public static final StreamCodec<FriendlyByteBuf, NeoForgeSplitPayload> STREAM_CODEC = StreamCodec.of(
			(FriendlyByteBuf buf, NeoForgeSplitPayload payload) -> buf.writeByteArray(payload.data),
			(FriendlyByteBuf buf) -> new NeoForgeSplitPayload(buf.readByteArray()));

	@Override
	public Type<NeoForgeSplitPayload> type() {
		return TYPE;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof NeoForgeSplitPayload other && Arrays.equals(data, other.data);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(data);
	}
}
