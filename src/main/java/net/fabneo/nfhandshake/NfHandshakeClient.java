package net.fabneo.nfhandshake;

import java.util.List;
import java.util.Set;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import net.fabneo.nfhandshake.mixin.ClientCommonPacketListenerImplAccessor;
import net.fabneo.nfhandshake.mixin.ConnectionAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 配置阶段 NeoForge 握手适配器（"硬碰"方向）。
 *
 * <p>本类做三件事：
 * <ol>
 *   <li>在配置阶段注册 {@code neoforge:register}（ModdedNetworkQueryPayload）的 S2C/C2S 类型，
 *       监听服务器下发的查询，回一份<b>带 NeoForge 通道声明</b>的响应
 *       （通道清单由 {@link NeoForgeRegisterPayload} 的自动探测机制从查询包本身取得）。</li>
 *   <li>同时保留 {@code c:register}（CommonRegisterPayload）的 C2S 声明，满足 NeoForge
 *       {@code ConfigurationInitialization} 注册 Common* tasks 的通道条件。</li>
 *   <li>不再静默丢弃非 {@code minecraft} 命名空间的负载——1.21.11 的
 *       {@code DiscardedPayload} 回退机制已能安全忽略未知 ID 的包，主动丢弃反而会把
 *       {@code neoforge:network} 等握手关键包误伤。故 {@link #shouldDrop} 返回 false。</li>
 * </ol>
 *
 * <p><b>协议依据（均来自本机 NeoForge 21.1.248 产物逆向 + 实测）：</b>
 * <ul>
 *   <li>{@code neoforge:register} 的 ID 为 {@code ModdedNetworkQueryPayload.ID}
 *       —— NeoForge {@code ModdedNetworkQueryPayload.java:32}。</li>
 *   <li>线格式见 {@link NeoForgeRegisterPayload}。</li>
 *   <li>服务器在配置阶段下发 {@code neoforge:register} 查询，客户端回等价声明即把
 *       {@code connectionType} 设为 NEOFORGE 并通过协商。</li>
 * </ul>
 *
 * <p><b>隐私说明</b>：本类<b>不含</b>任何服务器地址、账号、密码等敏感信息。
 * 这些内容统一由 {@link DebugConfig} 从外部配置文件读取；
 * 服务器通道清单也不打包进 jar，而是由
 * {@link NeoForgeRegisterPayload} 在运行时向服务端探测获取。
 */
public class NfHandshakeClient implements ClientModInitializer {
	public static final String MOD_ID = "nfhandshake";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		// 调试开关先加载（后续 lambda 需要捕获）
		final DebugConfig cfg = DebugConfig.load(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().getParent());

		// neoforge:register（ModdedNetworkQueryPayload）—— NeoForge 在多个阶段都可能查询：
		// LOGIN / CONFIGURATION / PLAY。全部注册，否则任一阶段的查询都收不到/发不出。
		// 注意：c:register 由 Fabric API 原生处理，绝不能碰。
		PayloadTypeRegistry.configurationS2C()
				.register(NeoForgeRegisterPayload.TYPE, NeoForgeRegisterPayload.STREAM_CODEC);
		PayloadTypeRegistry.configurationC2S()
				.register(NeoForgeRegisterPayload.TYPE, NeoForgeRegisterPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C()
				.register(NeoForgeRegisterPayload.TYPE, NeoForgeRegisterPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S()
				.register(NeoForgeRegisterPayload.TYPE, NeoForgeRegisterPayload.STREAM_CODEC);
		// 应答类型 neoforge:network（ModdedNetworkPayload）
		PayloadTypeRegistry.configurationC2S()
				.register(NeoForgeNetworkPayload.TYPE, NeoForgeNetworkPayload.STREAM_CODEC);
		PayloadTypeRegistry.configurationS2C()
				.register(NeoForgeNetworkPayload.TYPE, NeoForgeNetworkPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S()
				.register(NeoForgeNetworkPayload.TYPE, NeoForgeNetworkPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C()
				.register(NeoForgeNetworkPayload.TYPE, NeoForgeNetworkPayload.STREAM_CODEC);
		// neoforge:split —— 必须注册，否则 NeoForge 分片包会因 DiscardedPayload 跳错长度导致流错位
		PayloadTypeRegistry.configurationS2C()
				.register(NeoForgeSplitPayload.TYPE, NeoForgeSplitPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C()
				.register(NeoForgeSplitPayload.TYPE, NeoForgeSplitPayload.STREAM_CODEC);
		// neoforge:modded_network_setup_failed —— 协商失败时服务器会发这个包说明原因。
		// 不注册它，失败原因就永远是黑盒（这是历史上把"协商被拒"误判成"解码 bug"的直接原因）。
		PayloadTypeRegistry.configurationS2C()
				.register(NeoForgeSetupFailedPayload.TYPE, NeoForgeSetupFailedPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C()
				.register(NeoForgeSetupFailedPayload.TYPE, NeoForgeSetupFailedPayload.STREAM_CODEC);

		// neoforge:frozen_registry_sync_completed —— FrozenRegistry 同步的**应答**包。
		// 服务端发完自己的同步后会停下等这个 ack（ServerPayloadHandler 里
		// finishCurrentTask(SyncRegistries.TYPE)），不回则配置阶段卡死。
		PayloadTypeRegistry.configurationC2S()
				.register(NeoForgeFrozenRegistryCompletedPayload.TYPE,
						NeoForgeFrozenRegistryCompletedPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S()
				.register(NeoForgeFrozenRegistryCompletedPayload.TYPE,
						NeoForgeFrozenRegistryCompletedPayload.STREAM_CODEC);
		// 同时注册 S2C：服务端也会发同名包（作为"发完了"的信号），我们要能收下不崩
		PayloadTypeRegistry.configurationS2C()
				.register(NeoForgeFrozenRegistryCompletedPayload.TYPE,
						NeoForgeFrozenRegistryCompletedPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C()
				.register(NeoForgeFrozenRegistryCompletedPayload.TYPE,
						NeoForgeFrozenRegistryCompletedPayload.STREAM_CODEC);

		// known_registry_data_maps_reply：**必须同时注册 C2S**，
		// 否则发送时会因找不到 C2S 编解码器而退化成 DiscardedPayload，
		// 报 ClassCastException: ...ReplyPayload cannot be cast to DiscardedPayload。
		PayloadTypeRegistry.configurationC2S()
				.register(NeoForgeKnownDataMapsReplyPayload.TYPE, NeoForgeKnownDataMapsReplyPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S()
				.register(NeoForgeKnownDataMapsReplyPayload.TYPE, NeoForgeKnownDataMapsReplyPayload.STREAM_CODEC);
		// 同样注册 S2C，避免服务端发来的请求包被当未知类型
		PayloadTypeRegistry.configurationS2C()
				.register(NeoForgeKnownDataMapsReplyPayload.TYPE, NeoForgeKnownDataMapsReplyPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C()
				.register(NeoForgeKnownDataMapsReplyPayload.TYPE, NeoForgeKnownDataMapsReplyPayload.STREAM_CODEC);

		// extensible_enum_ack：第三个请求/应答。**C2S 必须注册**，否则发送时
		// 退化成 DiscardedPayload 并抛 ClassCastException（已踩过这个坑）。
		PayloadTypeRegistry.configurationC2S()
				.register(NeoForgeExtensibleEnumAckPayload.TYPE, NeoForgeExtensibleEnumAckPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S()
				.register(NeoForgeExtensibleEnumAckPayload.TYPE, NeoForgeExtensibleEnumAckPayload.STREAM_CODEC);
		PayloadTypeRegistry.configurationS2C()
				.register(NeoForgeExtensibleEnumAckPayload.TYPE, NeoForgeExtensibleEnumAckPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C()
				.register(NeoForgeExtensibleEnumAckPayload.TYPE, NeoForgeExtensibleEnumAckPayload.STREAM_CODEC);

		// feature_flags_ack：第四个请求/应答。C2S 必须注册（否则发送时退化成
		// DiscardedPayload 并抛 ClassCastException —— 这个坑已踩过两次）。
		PayloadTypeRegistry.configurationC2S()
				.register(NeoForgeFeatureFlagsAckPayload.TYPE, NeoForgeFeatureFlagsAckPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S()
				.register(NeoForgeFeatureFlagsAckPayload.TYPE, NeoForgeFeatureFlagsAckPayload.STREAM_CODEC);
		PayloadTypeRegistry.configurationS2C()
				.register(NeoForgeFeatureFlagsAckPayload.TYPE, NeoForgeFeatureFlagsAckPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C()
				.register(NeoForgeFeatureFlagsAckPayload.TYPE, NeoForgeFeatureFlagsAckPayload.STREAM_CODEC);

		// 收到"服务端同步完毕" → 立刻回 ack，让配置阶段继续
		ClientConfigurationNetworking.registerGlobalReceiver(NeoForgeFrozenRegistryCompletedPayload.TYPE,
				(payload, context) -> sendFrozenRegistryAck("配置阶段"));
		ClientPlayNetworking.registerGlobalReceiver(NeoForgeFrozenRegistryCompletedPayload.TYPE,
				(payload, context) -> sendFrozenRegistryAck("PLAY 阶段"));

		// 收到协商失败原因 → 逐通道打印（这是本项目最有价值的日志）
		ClientConfigurationNetworking.registerGlobalReceiver(NeoForgeSetupFailedPayload.TYPE,
				(payload, context) -> logSetupFailure("配置阶段", payload));
		ClientPlayNetworking.registerGlobalReceiver(NeoForgeSetupFailedPayload.TYPE,
				(payload, context) -> logSetupFailure("PLAY 阶段", payload));

		// 配置阶段查询
		ClientConfigurationNetworking.registerGlobalReceiver(NeoForgeRegisterPayload.TYPE,
				(payload, context) -> {
					try {
						// 【关键订正】逐段回等量通道，而不是只回一段。
						// 服务器按 PAYLOAD_REGISTRATIONS 的每个协议段分别协商，
						// 任何一段没回都会被判 missing.server.client 而断连。
						NeoForgeRegisterPayload response = buildEchoResponse(payload, cfg);
						dumpPayload("发送 neoforge:register(" + response.queries().size()
								+ " 段: " + response.describeSegments() + ")", response);
						ClientConfigurationNetworking.send(response);
						LOGGER.info("[MILESTONE] M1a 配置阶段回 neoforge:register({} 段: {})",
								response.queries().size(), response.describeSegments());
					} catch (Throwable t) {
						LOGGER.warn("[nfhandshake] 配置阶段响应失败", t);
					}
				});

		// PLAY 阶段查询（机器人 PR OTO=1 成功路径）
		ClientPlayNetworking.registerGlobalReceiver(NeoForgeRegisterPayload.TYPE,
				(payload, context) -> {
					try {
						NeoForgeRegisterPayload response = buildEchoResponse(payload, cfg);
						dumpPayload("发送 neoforge:register(" + response.queries().size()
								+ " 段: " + response.describeSegments() + ")", response);
						context.responseSender().sendPacket(response);
						LOGGER.info("[MILESTONE] M1b PLAY 阶段回 neoforge:register({} 段: {})",
								response.queries().size(), response.describeSegments());
					} catch (Throwable t) {
						LOGGER.warn("[nfhandshake] PLAY 阶段响应失败", t);
					}
				});

		// ===== 调试开关（nfhandshake.properties，改后重启生效；无文件=纯观察模式）=====

		// 诊断：记录服务器发来的 custom_payload（定位流错位），不修改流程
		ClientConfigurationConnectionEvents.START.register((handler, client) -> installNettyFilter(handler));
		ClientPlayConnectionEvents.INIT.register((handler, client) -> installNettyFilter(handler));

		// M2：配置阶段完成 —— NeoForge 配置任务全部通过（观察模式下也记录）
		ClientConfigurationConnectionEvents.READY.register((handler, client) ->
				LOGGER.info("[MILESTONE] M2 配置阶段完成(configuration READY) —— NeoForge 配置任务全部通过"));

		// M3/M4：进入游戏世界
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			LOGGER.info("[MILESTONE] M3 进入游戏世界(play JOIN)");
			if (cfg.autojump && client != null) {
				new Thread(() -> {
					try {
						Thread.sleep(3000);
						client.execute(() -> sendCommandSafely(client, cfg.subServerCommand,
								"[MILESTONE] M4 已发送 /" + cfg.subServerCommand + " —— 等待子服 reconfiguration"));
					} catch (InterruptedException ignored) {
					}
				}).start();
			}
		});

		// 自动登录：监听聊天，匹配"请输入 /login"提示后自动回登录命令
		if (cfg.autologin && cfg.password != null && !cfg.password.isEmpty()) {
			ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
				String text = message.getString();
				if (text.contains("请输入") && text.contains("/login")) {
					LOGGER.info("[nfhandshake] 检测到登录提示，自动发送 /login ...");
					net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
					mc.execute(() -> sendCommandSafely(mc, "login " + cfg.password, "[nfhandshake] 已发送 /login <密码>"));
				}
			});
		}

		LOGGER.info("[nfhandshake] 初始化完成（autoconnect={} autojump={} autologin={}）",
				cfg.autoconnect, cfg.autojump, cfg.autologin);

		// M0：自动连接主服
		if (cfg.autoconnect) {
			net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
				Thread t = new Thread(() -> {
					try {
						Thread.sleep(8000);
						client.execute(() -> {
							try {
								var address = net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(cfg.serverAddress);
								var serverData = new net.minecraft.client.multiplayer.ServerData(
										"nfhandshake-debug", cfg.serverAddress,
										net.minecraft.client.multiplayer.ServerData.Type.OTHER);
								LOGGER.info("[MILESTONE] M0 自动发起连接 {} ...", cfg.serverAddress);
								net.minecraft.client.gui.screens.ConnectScreen.startConnecting(
										new net.minecraft.client.gui.screens.TitleScreen(),
										client, address, serverData, false, null);
							} catch (Throwable tt) {
								LOGGER.warn("[nfhandshake] 自动连接失败", tt);
							}
						});
					} catch (InterruptedException ignored) {
					}
				});
				t.setDaemon(true);
				t.start();
			});
		}
	}



	/** 把 payload 编码成字节并打印 hex（前 200 字节），用于与机器人成功样本比对。 */
	private static void dumpPayload(String tag, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
		try {
			net.minecraft.network.FriendlyByteBuf buf = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
			if (payload instanceof NeoForgeNetworkPayload np) {
				NeoForgeNetworkPayload.STREAM_CODEC.encode(buf, np);
			} else if (payload instanceof NeoForgeRegisterPayload rp) {
				NeoForgeRegisterPayload.STREAM_CODEC.encode(buf, rp);
			} else {
				return;
			}
			byte[] bytes = new byte[buf.readableBytes()];
			buf.getBytes(buf.readerIndex(), bytes);
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < Math.min(bytes.length, 200); i++) {
				sb.append(String.format("%02x", bytes[i]));
			}
			LOGGER.info("[DUMP] {} len={} hex={}", tag, bytes.length, sb);
		} catch (Throwable t) {
			LOGGER.warn("[DUMP] 失败", t);
		}
	}

	/**
	 * 【核心修复】按服务端查询的**每一个协议段**逐段回等量通道。
	 *
	 * <p>历史 bug：旧实现用 {@code ofSingle(proto, channels)} 只回一段，
	 * 而服务端的查询包含它 registry 里的**所有**协议段，
	 * {@code NetworkRegistry.initializeNeoForgeConnection} 又是逐段协商的
	 * （{@code for (ConnectionProtocol protocol : PAYLOAD_REGISTRATIONS.keySet())}）。
	 * 没回的段会被判为"客户端没有这些通道" → {@code missing.server.client} → 断连。
	 *
	 * <p>实测证据：390 个通道全部报 {@code failure.missing.server.client}。
	 *
	 * <p>通道表来源：优先用查询包本身携带的通道（服务端已经告诉我们它有哪些），
	 * 缺失时才回退到 {@link NeoForgeChannelTable}。这样比硬编码 390 个更稳。
	 */
	/**
	 * 构造对服务器通道查询的应答。
	 *
	 * <h2>核心思路：自动探测，无需事先知道通道名</h2>
	 * 服务器下发的 {@code neoforge:register} 查询包由
	 * {@code ModdedNetworkQueryPayload.fromRegistry()} 生成，
	 * 它会把服务端 registry 里的<b>每一个</b>通道（含 id / version / flow）都放进去 ——
	 * 也就是说<b>服务端主动把"它有哪些通道"告诉了我们</b>。
	 *
	 * <p>因此我们只需把查询包的内容<b>逐段照抄</b>回去即可，完全不需要事先维护通道表。
	 * 这比早期"手工抄 390 个通道"可靠得多，也让 jar 里不必包含服务器的模组清单（隐私考量）。
	 *
	 * <h2>为什么把 optional 一律放宽为 true</h2>
	 * 协商规则表明：服务端的<b>非 optional</b> 通道若客户端缺失，才会导致失败。
	 * 我们声明为 optional 只是为了在"服务端通道减少"时不会反过来把我们判失败；
	 * 对"服务端有、我们也照抄"的情况没有任何副作用。
	 *
	 * <h2>回退路径</h2>
	 * 若查询包为空（异常情况），则使用本地已知通道
	 * （外部配置文件 + NeoForge 自带通道），并同时回 CONFIGURATION 与 PLAY 两段。
	 */
	private static NeoForgeRegisterPayload buildEchoResponse(NeoForgeRegisterPayload query, DebugConfig cfg) {
		java.util.Map<Integer, java.util.List<NeoForgeRegisterPayload.Component>> out =
				new java.util.LinkedHashMap<>();

		if (query != null && query.queries() != null && !query.queries().isEmpty()) {
			// 主路径：自动探测 —— 逐段照抄服务端查询里的通道
			for (var e : query.queries().entrySet()) {
				java.util.List<NeoForgeRegisterPayload.Component> list = new java.util.ArrayList<>();
				for (NeoForgeRegisterPayload.Component c : e.getValue()) {
					list.add(new NeoForgeRegisterPayload.Component(
							c.id(), c.version(), c.flow(), true));
				}
				// 对照实验：可用 channelLimit 截断，验证"少一个都不行"
				if (cfg.channelLimit >= 0 && cfg.channelLimit < list.size()) {
					list = new java.util.ArrayList<>(list.subList(0, cfg.channelLimit));
				}
				out.put(e.getKey(), list);
			}
			LOGGER.info("[nfhandshake] 自动探测：照抄服务端查询的 {} 段通道",
					out.size());
			return new NeoForgeRegisterPayload(out);
		}

		// 回退路径：查询包为空时，用本地通道（外部配置 + 自带通道），回两段
		var all = NeoForgeChannelTable.get();
		LOGGER.warn("[nfhandshake] 查询包为空，回退到本地通道表（{} 个通道）", all.size());
		out.put(4, all);
		out.put(1, all);
		return new NeoForgeRegisterPayload(out);
	}

	/**
	 * 回送 FrozenRegistry 同步完成应答。
	 *
	 * <p>服务端在 {@code SyncRegistries} 任务里发完 {@code frozen_registry_sync_completed}
	 * 后会**停下等待**本应答；收到后执行
	 * {@code ServerPayloadHandler.handle} → {@code context.finishCurrentTask(SyncRegistries.TYPE)}，
	 * 配置阶段才继续。
	 *
	 * <p>该负载<b>线上零字节</b>（{@code StreamCodec.unit(INSTANCE)}），只靠通道 ID 表达语义。
	 */
	private static void sendFrozenRegistryAck(String phase) {
		try {
			ClientConfigurationNetworking.send(NeoForgeFrozenRegistryCompletedPayload.INSTANCE);
			LOGGER.info("[MILESTONE] M5 已回 frozen_registry_sync_completed 应答（{}）", phase);
		} catch (Throwable t) {
			LOGGER.warn("[nfhandshake] 回 frozen_registry ack 失败（{}）", phase, t);
		}
	}

	/** 把服务器给出的协商失败原因逐通道打出来 —— 这是定位协商问题的唯一可靠信息源。 */
	private static void logSetupFailure(String phase, NeoForgeSetupFailedPayload payload) {
		var reasons = payload.failureReasons();
		LOGGER.error("=========== [NEGOTIATION FAILED] {} —— 共 {} 个通道不匹配 ===========",
				phase, reasons.size());
		// 服务端发来的 Component 是可翻译键 + 参数；客户端语言文件里没有这些键，
		// 直接 getString() 只会得到裸键名（如 ...failure.mod）。所以要把
		// 「键 + 参数」完整展开，才能看到真正的失败规则。
		java.util.Map<String, Integer> ruleCount = new java.util.LinkedHashMap<>();
		int shown = 0;
		for (var e : reasons.entrySet()) {
			Component c = e.getValue();
			String full = describeComponent(c);
			String rule = extractRule(c);
			ruleCount.merge(rule, 1, Integer::sum);
			if (shown++ < 6) {
				LOGGER.error("  ✗ {}  =>  {}", e.getKey(), full);
			}
		}
		LOGGER.error("---- 失败规则统计（去重后）----");
		ruleCount.forEach((rule, n) -> LOGGER.error("  {} × {}", rule, n));
		LOGGER.error("=========== [NEGOTIATION FAILED] 结束 ===========");
	}

	/** 把 Component 展开成「键(参数1, 参数2, ...)」，便于看清真实含义。 */
	private static String describeComponent(Component c) {
		if (c == null) {
			return "(null)";
		}
		StringBuilder sb = new StringBuilder(c.getString());
		try {
			var contents = c.getContents();
			if (contents instanceof net.minecraft.network.chat.contents.TranslatableContents tc) {
				sb.setLength(0);
				sb.append(tc.getKey());
				Object[] args = tc.getArgs();
				if (args != null && args.length > 0) {
					sb.append('(');
					for (int i = 0; i < args.length; i++) {
						if (i > 0) {
							sb.append(", ");
						}
						Object a = args[i];
						if (a instanceof Component ac) {
							sb.append(describeComponent(ac));
						} else {
							sb.append(a);
						}
					}
					sb.append(')');
				}
			}
		} catch (Throwable ignored) {
		}
		return sb.toString();
	}

	/** 从嵌套 Component 里提取最内层的失败规则键。 */
	private static String extractRule(Component c) {
		if (c == null) {
			return "(null)";
		}
		try {
			var contents = c.getContents();
			if (contents instanceof net.minecraft.network.chat.contents.TranslatableContents tc) {
				// failure.mod 这类包装键，真正的原因在其参数里
				for (Object a : tc.getArgs()) {
					if (a instanceof Component ac) {
						String inner = extractRule(ac);
						if (inner.contains("failure.")) {
							return inner;
						}
					}
				}
				return tc.getKey();
			}
		} catch (Throwable ignored) {
		}
		return c.getString();
	}

	/** 构造 NetworkPayloadSetup 应答：PLAY(1) 与 CONFIGURATION(4) 两段各含全部通道。 */
	private static NeoForgeNetworkPayload buildNetworkResponse(List<NeoForgeRegisterPayload.Component> channels) {
		java.util.Map<String, String> table = new java.util.LinkedHashMap<>();
		for (NeoForgeRegisterPayload.Component c : channels) {
			table.put(c.id(), c.version() == null ? "" : c.version());
		}
		java.util.Map<Integer, java.util.Map<String, String>> setup = new java.util.LinkedHashMap<>();
		// 只用 PLAY(1) 段：与机器人成功路径一致（bot/iterate3.js 的 PROTO=1）
		setup.put(1, table);
		return new NeoForgeNetworkPayload(setup);
	}

	/** 安全地在客户端主线程发命令（ slash 前缀自动补）。 */
	private static void sendCommandSafely(net.minecraft.client.Minecraft client, String command, String logMsg) {
		try {
			if (client.player != null && client.getConnection() != null) {
				client.player.connection.sendCommand(command);
				LOGGER.info("{}", logMsg);
			} else {
				LOGGER.warn("[nfhandshake] 发送 /{} 失败：玩家未就绪", command);
			}
		} catch (Throwable t) {
			LOGGER.warn("[nfhandshake] 发送 /{} 异常", command, t);
		}
	}

	/**
	 * 把 {@link NettyModdedPayloadFilter} 插入到 {@code packet_handler} 之前。
	 *
	 * <p>{@code Connection.channel} 是 private 字段，{@code ClientCommonPacketListenerImpl.connection}
	 * 也是，所以经由两个 accessor mixin 取到 {@code ChannelPipeline}。整段用 try/catch 包住：
	 * 即使某个版本的管线结构变化，也只会留下一条警告日志，不会影响连接。
	 */
	private static void installNettyFilter(ClientCommonPacketListenerImpl handler) {
		try {
			Connection connection = ((ClientCommonPacketListenerImplAccessor) handler).nfhandshake$getConnection();
			if (connection == null) {
				return;
			}

			Channel channel = ((ConnectionAccessor) connection).nfhandshake$getChannel();
			if (channel == null) {
				return;
			}

			ChannelPipeline pipeline = channel.pipeline();
			if (pipeline.get(NettyModdedPayloadFilter.NAME) != null) {
				return;
			}

			pipeline.addBefore(HandlerNames.PACKET_HANDLER, NettyModdedPayloadFilter.NAME,
					NettyModdedPayloadFilter.INSTANCE);
			// FrozenRegistry 同步应答：必须在 Netty 层，
			// 因为服务端在配置阶段早期就发这些包（早于通道可用），
			// Fabric 的接收器不会被调用（包会变成 DiscardedPayload）。
			try {
				if (pipeline.get(FrozenRegistrySyncResponder.NAME) == null) {
					pipeline.addBefore(HandlerNames.PACKET_HANDLER, FrozenRegistrySyncResponder.NAME,
							FrozenRegistrySyncResponder.INSTANCE);
					LOGGER.info("[nfhandshake] FrozenRegistrySyncResponder 已挂载");
				}
			} catch (Throwable t) {
				LOGGER.warn("[nfhandshake] FrozenRegistrySyncResponder 挂载失败: {}", String.valueOf(t));
			}
			// 原始字节抓取：挂在 PacketDecoder 之前（splitter 之后），抓服务器裸字节
			// 挂在 DECODER 之前：抓 PacketDecoder 读到的原始字节（错位发生在此层）
			try {
				if (pipeline.get(RawByteSniffer.NAME) == null) {
					pipeline.addBefore(HandlerNames.DECODER, RawByteSniffer.NAME, RawByteSniffer.INSTANCE);
					LOGGER.info("[nfhandshake] RawByteSniffer 已挂在 {} 之前", HandlerNames.DECODER);
				}
			} catch (Throwable t) {
				LOGGER.warn("[nfhandshake] RawByteSniffer 挂载失败: {}", String.valueOf(t));
			}
			LOGGER.info("[{}] 已注入 Netty 过滤器: before '{}'", MOD_ID, HandlerNames.PACKET_HANDLER);
		} catch (Throwable t) {
			LOGGER.warn("[{}] 注入 Netty 过滤器失败（不影响连接）", MOD_ID, t);
		}
	}

	/**
	 * 判断一个入站自定义负载是否应当被静默丢弃。
	 *
	 * <p><b>"硬碰"模式下返回 false（不主动丢弃任何包）。</b>理由：
	 * 1.21.11 的 {@code ClientboundCustomPayloadPacket} 用 {@code DiscardedPayload} 作未知 ID 回退，
	 * 解码阶段不会抛异常；让所有包按原样进入 Minecraft 分发逻辑最安全，
	 * 否则会把 {@code neoforge:network} 等握手关键包误伤导致协商失败。
	 */
	public static boolean shouldDrop(CustomPacketPayload payload) {
		return false;
	}
}
