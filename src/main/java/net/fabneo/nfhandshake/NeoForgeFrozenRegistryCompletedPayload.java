package net.fabneo.nfhandshake;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 复刻 NeoForge {@code FrozenRegistrySyncCompletedPayload}
 * （通道 {@code neoforge:frozen_registry_sync_completed}）。
 *
 * <h2>为什么需要它</h2>
 * NeoForge 的 FrozenRegistry 同步是**请求/应答**流程：
 * <pre>
 * 服务端 → frozen_registry_sync_start       （开始）
 * 服务端 → frozen_registry × N              （逐注册表内容，实测连续几十个）
 * 服务端 → frozen_registry_sync_completed   （发完，**等客户端确认**）
 * 客户端 → frozen_registry_sync_completed   （★ 必须回这个，否则服务端一直等）
 * </pre>
 *
 * <p>实测现象（{@code launch-out.log}）：服务端发完
 * {@code frozen_registry_sync_completed} 后就**停住不动**，
 * 因为客户端把该通道当 {@code DiscardedPayload} 收下即丢，**从不回应**。
 *
 * <h2>为什么我们会触发这个流程</h2>
 * {@code ConfigurationInitialization.configureEarlyTasks}
 * （{@code ref/neoforge-src/ConfigurationInitialization.java:38-43}）：
 * <pre>
 * if (listener.hasChannel(FrozenRegistrySyncStartPayload.TYPE) &&
 *     listener.hasChannel(FrozenRegistryPayload.TYPE) &&
 *     listener.hasChannel(FrozenRegistrySyncCompletedPayload.TYPE) &&
 *     !listener.getConnection().isMemoryConnection()) {
 *     tasks.accept(new SyncRegistries());
 * }
 * </pre>
 * 我们声明了这三个通道（{@code NeoForgeChannelTable.BUILTIN_CHANNELS}），
 * 于是服务端**确实**注册了 SyncRegistries 任务并开始下发。既然触发了，就必须应答。
 *
 * <h2>线格式（反汇编确认）</h2>
 * 该类是**无字段单例**，codec 为：
 * <pre>
 * STREAM_CODEC = StreamCodec.unit(INSTANCE)
 * </pre>
 * 即 <b>线上零字节</b> —— 只有通道 ID，没有任何负载内容。
 */
public final class NeoForgeFrozenRegistryCompletedPayload implements CustomPacketPayload {

	/** {@code neoforge:frozen_registry_sync_completed} */
	public static final Identifier ID =
			Identifier.fromNamespaceAndPath("neoforge", "frozen_registry_sync_completed");

	public static final Type<NeoForgeFrozenRegistryCompletedPayload> TYPE = new Type<>(ID);

	public static final NeoForgeFrozenRegistryCompletedPayload INSTANCE =
			new NeoForgeFrozenRegistryCompletedPayload();

	public static final StreamCodec<FriendlyByteBuf, NeoForgeFrozenRegistryCompletedPayload> STREAM_CODEC =
			StreamCodec.unit(INSTANCE);

	private NeoForgeFrozenRegistryCompletedPayload() {
	}

	@Override
	public Type<NeoForgeFrozenRegistryCompletedPayload> type() {
		return TYPE;
	}
}
