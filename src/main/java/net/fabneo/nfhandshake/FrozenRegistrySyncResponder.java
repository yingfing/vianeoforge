package net.fabneo.nfhandshake;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;

/**
 * 入站自定义负载观察器 + FrozenRegistry 同步应答。
 *
 * <h2>为什么必须在 Netty 层做，不能用 Fabric 的注册 API</h2>
 * NeoForge 在**配置阶段早期**就发送 {@code neoforge:frozen_registry_sync_start}
 * 与数十个 {@code neoforge:frozen_registry}，这**早于** vanilla 的
 * {@code MinecraftRegisterPayload} 把通道置为可接收。
 * 此时 Fabric 的 {@code PayloadTypeRegistry} 还没有这些通道的接收器，
 * 包会回退成 {@code DiscardedPayload}（日志里 {@code impl=class_8711}），
 * 于是 {@code ClientConfigurationNetworking.registerGlobalReceiver} **根本不会被调用**。
 *
 * <p>结论：必须直接在 Netty 管线里观察通道 ID，自行判断流程并回送应答。
 *
 * <h2>协议（反汇编 + 源码双证据）</h2>
 * 服务端 {@code SyncRegistries#start}（{@code ref/neoforge-net/.../SyncRegistries.class}）：
 * <pre>
 * sender.accept(new FrozenRegistrySyncStartPayload(RegistryManager.getRegistryNamesForSyncToClient()));
 * RegistryManager.generateRegistryPackets(false).forEach(sender);   // 实测 27 个
 * sender.accept(FrozenRegistrySyncCompletedPayload.INSTANCE);
 * </pre>
 * 客户端必须回送 {@code neoforge:frozen_registry_sync_completed}（**零字节负载**），
 * 服务端才会推进：
 * <pre>
 * // ref/neoforge-src/ServerPayloadHandler.java:17
 * public static void handle(FrozenRegistrySyncCompletedPayload payload, IPayloadContext context) {
 *     context.finishCurrentTask(SyncRegistries.TYPE);
 * }
 * </pre>
 * 不回应的实测后果：服务端静默 32 秒后 {@code SocketException: Connection reset}。
 *
 * <h2>策略</h2>
 * 采用"**静默计时 + 稳定即应答**"：收到 {@code frozen_registry} 后启动/重置一个短定时器；
 * 若一段时间内没有新分片到达（说明服务端发完了），就主动回送 ack。
 * 这样不依赖收到 {@code sync_completed}（它本身也可能因同样的注册时序问题被丢弃）。
 *
 * <p>只读不改：抓取后原样 {@code fireChannelRead}，不影响正常解码流程。
 */
@ChannelHandler.Sharable
public class FrozenRegistrySyncResponder extends ChannelInboundHandlerAdapter {

	public static final String NAME = "nfhandshake-frozen-registry";

	public static final FrozenRegistrySyncResponder INSTANCE = new FrozenRegistrySyncResponder();

	/** 分片间隔超过这个时间就认为"发完了"（实测分片间隔 < 1 秒）。 */
	private static final long QUIET_MILLIS = 1500L;

	private static final String CH_START = "neoforge:frozen_registry_sync_start";
	private static final String CH_DATA = "neoforge:frozen_registry";
	private static final String CH_DONE = "neoforge:frozen_registry_sync_completed";
	/** 服务端问"你知道哪些 registry data map" → 必须回 reply，否则配置阶段停住。 */
	private static final String CH_DATAMAP_QUERY = "neoforge:known_registry_data_maps";
	/** 可扩展枚举校验请求 → 必须回 ack。 */
	private static final String CH_ENUM_DATA = "neoforge:extensible_enum_data";
	/** 特性开关下发 → 必须回 ack。 */
	private static final String CH_FEATURE_FLAGS = "neoforge:feature_flags";

	/** 本轮同步中收到的分片数。 */
	private static volatile int fragmentCount = 0;
	private static volatile boolean syncActive = false;
	private static volatile long lastFragmentAt = 0L;
	private static volatile boolean ackSent = false;
	private static volatile Thread watchdog;

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		try {
			if (msg instanceof ClientboundCustomPayloadPacket pkt && pkt.payload() != null) {
				var type = pkt.payload().type();
				String id = (type != null && type.id() != null) ? type.id().toString() : "";
				handleChannel(id);
			}
		} catch (Throwable ignored) {
			// 诊断代码绝不影响主流程
		}
		super.channelRead(ctx, msg);
	}

	private static synchronized void handleChannel(String id) {
		if (CH_START.equals(id)) {
			syncActive = true;
			ackSent = false;
			fragmentCount = 0;
			lastFragmentAt = System.currentTimeMillis();
			NfHandshakeClient.LOGGER.info("[MILESTONE] M6 frozen_registry 同步开始（服务端下发中）");
			startWatchdog();
			return;
		}
		if (CH_DATA.equals(id)) {
			fragmentCount++;
			lastFragmentAt = System.currentTimeMillis();
			if (syncActive) {
				startWatchdog();
			}
			return;
		}
		if (CH_DONE.equals(id)) {
			// 最理想的情况：直接收到完成信号
			NfHandshakeClient.LOGGER.info(
					"[MILESTONE] M6b 收到 frozen_registry_sync_completed（共 {} 个分片）", fragmentCount);
			sendAck("收到 sync_completed");
			return;
		}
		if (CH_DATAMAP_QUERY.equals(id)) {
			// 同构的请求/应答：服务端问 data map，我们必须回一个（空）答复
			sendDataMapsReply();
			return;
		}
		if (CH_ENUM_DATA.equals(id)) {
			// 第三个请求/应答：可扩展枚举校验
			sendEnumAck();
			return;
		}
		if (CH_FEATURE_FLAGS.equals(id)) {
			// 第四个请求/应答：特性开关确认
			sendFeatureFlagsAck();
		}
	}

	/** 回送 feature_flags_ack（零字节负载）。 */
	private static void sendFeatureFlagsAck() {
		try {
			net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
			if (mc == null) {
				return;
			}
			mc.execute(() -> {
				try {
					net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking
							.send(NeoForgeFeatureFlagsAckPayload.INSTANCE);
					NfHandshakeClient.LOGGER.info("[MILESTONE] M9 已回 feature_flags_ack（特性开关确认）");
				} catch (Throwable t) {
					NfHandshakeClient.LOGGER.warn("[nfhandshake] 回 feature_flags_ack 失败", t);
				}
			});
		} catch (Throwable t) {
			NfHandshakeClient.LOGGER.warn("[nfhandshake] 调度 feature_flags_ack 失败", t);
		}
	}

	/** 回送 extensible_enum_ack（零字节负载）。 */
	private static void sendEnumAck() {
		try {
			net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
			if (mc == null) {
				return;
			}
			mc.execute(() -> {
				try {
					net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking
							.send(NeoForgeExtensibleEnumAckPayload.INSTANCE);
					NfHandshakeClient.LOGGER.info(
							"[MILESTONE] M8 已回 extensible_enum_ack（可扩展枚举校验应答）");
				} catch (Throwable t) {
					NfHandshakeClient.LOGGER.warn("[nfhandshake] 回 extensible_enum_ack 失败", t);
				}
			});
		} catch (Throwable t) {
			NfHandshakeClient.LOGGER.warn("[nfhandshake] 调度 extensible_enum_ack 失败", t);
		}
	}

	/** 回送 known_registry_data_maps_reply（空 map）。 */
	private static void sendDataMapsReply() {
		try {
			net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
			if (mc == null) {
				return;
			}
			mc.execute(() -> {
				try {
					net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking
							.send(NeoForgeKnownDataMapsReplyPayload.empty());
					NfHandshakeClient.LOGGER.info(
							"[MILESTONE] M7 已回 known_registry_data_maps_reply（空 map）");
				} catch (Throwable t) {
					NfHandshakeClient.LOGGER.warn("[nfhandshake] 回 data maps reply 失败", t);
				}
			});
		} catch (Throwable t) {
			NfHandshakeClient.LOGGER.warn("[nfhandshake] 调度 data maps reply 失败", t);
		}
	}

	/** 起一个守护线程，静默超时后认为服务端发完并回送 ack。 */
	private static void startWatchdog() {
		if (watchdog != null && watchdog.isAlive()) {
			return;
		}
		Thread t = new Thread(() -> {
			try {
				while (true) {
					Thread.sleep(300);
					if (!syncActive || ackSent) {
						return;
					}
					long idle = System.currentTimeMillis() - lastFragmentAt;
					if (idle >= QUIET_MILLIS && fragmentCount > 0) {
						NfHandshakeClient.LOGGER.info(
								"[MILESTONE] M6c frozen_registry 已静默 {}ms（共 {} 个分片）—— 判定发完，回送 ack",
								idle, fragmentCount);
						sendAck("分片静默超时");
						return;
					}
				}
			} catch (InterruptedException ignored) {
			} catch (Throwable t2) {
				NfHandshakeClient.LOGGER.warn("[nfhandshake] frozen_registry 看门狗异常", t2);
			}
		}, "nfhandshake-frozen-watch");
		t.setDaemon(true);
		t.start();
		watchdog = t;
	}

	private static void sendAck(String why) {
		if (ackSent) {
			return;
		}
		ackSent = true;
		syncActive = false;
		try {
			// 必须回到客户端主线程发送（网络发送有线程约束）
			net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
			if (mc == null) {
				NfHandshakeClient.LOGGER.warn("[nfhandshake] 无法回 ack：Minecraft 实例不可用");
				return;
			}
			mc.execute(() -> {
				try {
					net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking
							.send(NeoForgeFrozenRegistryCompletedPayload.INSTANCE);
					NfHandshakeClient.LOGGER.info(
							"[MILESTONE] M5 已回 frozen_registry_sync_completed 应答（{}）", why);
				} catch (Throwable t) {
					NfHandshakeClient.LOGGER.warn("[nfhandshake] 回 frozen_registry ack 失败（{}）", why, t);
				}
			});
		} catch (Throwable t) {
			NfHandshakeClient.LOGGER.warn("[nfhandshake] 调度 ack 失败（{}）", why, t);
		}
	}

	/** 诊断信息。 */
	public static String describe() {
		return "fragments=" + fragmentCount + " active=" + syncActive + " ackSent=" + ackSent;
	}
}
