package net.fabneo.nfhandshake;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.fabneo.nfhandshake.NeoForgeRegisterPayload.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge 通道声明表。
 *
 * <h2>设计目标：jar 内不含任何服务器隐私</h2>
 * 通道表本质上是**服务器的模组清单**，属于敏感信息，因此：
 * <ul>
 *   <li>jar 内**不再打包**该表（旧版本曾把 390 个通道写进 {@code resources/}）</li>
 *   <li>改由外部文件提供，或由 mod **自动探测**</li>
 * </ul>
 *
 * <h2>两种来源（按优先级）</h2>
 * <ol>
 *   <li><b>自动探测（首选，零配置）</b>：服务器在配置阶段下发的
 *       {@code neoforge:register} 查询包里**自带它的完整通道清单**
 *       （见 {@code ModdedNetworkQueryPayload.fromRegistry()}）。
 *       我们收到后直接照抄回去即可，无需事先知道任何通道名。</li>
 *   <li><b>外部文件（可选）</b>：{@code <gameDir>/config/nfhandshake-channels.json}。
 *       仅在需要离线预置、或做对照实验时使用。</li>
 * </ol>
 *
 * <h2>关于 NeoForge 自带通道</h2>
 * 除上述两者，还会固定补上一批 {@code neoforge:*} 自带通道
 * （它们是 NeoForge 框架自身注册的，任何 NeoForge 服务器都有）。
 * 这部分不是隐私，写死在代码里是合适的。
 */
public final class NeoForgeChannelTable {

	private static final Logger LOGGER = LoggerFactory.getLogger("nfhandshake");
	private static final Gson GSON = new Gson();

	/** 外部通道表文件名（放在 config 目录下）。 */
	public static final String FILE_NAME = "nfhandshake-channels.json";

	/** 缓存：外部文件载入的通道（懒加载）。 */
	private static List<Component> fromFile;

	/** 标记：外部文件是否已尝试载入过（避免反复报错刷屏）。 */
	private static boolean fileLoadAttempted;

	private NeoForgeChannelTable() {
	}

	/**
	 * 取"本地已知的通道"（**不含**自动探测的部分）。
	 *
	 * <p>自动探测才是主路径（见 {@link #BUILTIN_CHANNELS} 的说明）；
	 * 本方法只返回外部文件配置的通道，供无查询包时兜底。
	 */
	public static List<Component> get() {
		if (!fileLoadAttempted) {
			fileLoadAttempted = true;
			fromFile = loadFromExternalFile();
		}
		List<Component> out = new ArrayList<>();
		if (fromFile != null) {
			out.addAll(fromFile);
		}
		// 补上 NeoForge 自带通道（非隐私，固定注入）
		java.util.Set<String> seen = new java.util.HashSet<>();
		out.forEach(c -> seen.add(c.id()));
		for (String[] c : BUILTIN_CHANNELS) {
			if (seen.add(c[0])) {
				int f = Integer.parseInt(c[2]);
				// -1 = 不声明 flow（服务端也未声明时，声明了反而失败）
				out.add(new Component(c[0], c[1], f < 0 ? Optional.empty() : Optional.of(f), true));
			}
		}
		return out;
	}

	/** 仅返回 NeoForge 自带通道（供需要区分来源的调用方使用）。 */
	public static List<Component> builtins() {
		List<Component> out = new ArrayList<>();
		for (String[] c : BUILTIN_CHANNELS) {
			int f = Integer.parseInt(c[2]);
			out.add(new Component(c[0], c[1], f < 0 ? Optional.empty() : Optional.of(f), true));
		}
		return out;
	}

	/**
	 * 从外部文件载入通道表。
	 *
	 * <p>查找位置（按顺序）：
	 * <ol>
	 *   <li>系统属性 {@code -Dnfhandshake.channels=<路径>}（测试用）</li>
	 *   <li>{@code <gameDir>/config/nfhandshake-channels.json}</li>
	 * </ol>
	 * 找不到时返回空列表（**不是错误**——自动探测才是主路径）。
	 */
	private static List<Component> loadFromExternalFile() {
		Path p = resolveFile();
		if (p == null || !Files.exists(p)) {
			LOGGER.info("[nfhandshake] 未找到外部通道表（{}）—— 将使用【自动探测】模式",
					p == null ? "(无路径)" : p);
			return List.of();
		}
		try (InputStreamReader r = new InputStreamReader(
				Files.newInputStream(p), StandardCharsets.UTF_8)) {
			List<Component> out = parseJson(r);
			LOGGER.info("[nfhandshake] 从外部文件载入 {} 个通道: {}", out.size(), p);
			return out;
		} catch (Throwable t) {
			LOGGER.warn("[nfhandshake] 解析外部通道表失败（将退回自动探测）: {}", String.valueOf(t));
			return List.of();
		}
	}

	/** 决定外部通道表路径。 */
	private static Path resolveFile() {
		String explicit = System.getProperty("nfhandshake.channels");
		if (explicit != null && !explicit.isBlank()) {
			return java.nio.file.Paths.get(explicit);
		}
		try {
			// 与 DebugConfig 使用同一个 gameDir 推导方式
			Path gameDir = DebugConfig.currentGameDir();
			if (gameDir == null) {
				return null;
			}
			return gameDir.resolve("config").resolve(FILE_NAME);
		} catch (Throwable t) {
			return null;
		}
	}

	/** 解析 JSON 通道表（也兼容 jar 内旧格式，便于平滑迁移）。 */
	private static List<Component> parseJson(java.io.Reader reader) {
		List<Component> out = new ArrayList<>();
		JsonObject root = GSON.fromJson(reader, JsonObject.class);
		if (root == null) {
			return out;
		}
		for (Map.Entry<String, JsonElement> e : root.entrySet()) {
			String key = e.getKey();
			// 跳过以 "_" 开头的注释字段（模板里有 "_说明"）
			if (key.startsWith("_")) {
				continue;
			}
			if (!e.getValue().isJsonObject()) {
				continue;
			}
			JsonObject v = e.getValue().getAsJsonObject();
			String version = v.has("version") ? v.get("version").getAsString() : "";
			Optional<Integer> flow = Optional.empty();
			if (v.has("flowOrd") && !v.get("flowOrd").isJsonNull()) {
				int f = v.get("flowOrd").getAsInt();
				if (f == 0 || f == 1) {
					flow = Optional.of(f);
				}
			}
			out.add(new Component(key, version, flow, true));
		}
		return out;
	}

	/**
	 * NeoForge **自带**通道（由 NeoForge 框架自身注册，任何 NeoForge 服务器都有）。
	 *
	 * <h2>为什么必须补上它们</h2>
	 * 服务器在发送这些通道之前会做检查（{@code NetworkRegistry.checkPacket}）：
	 * <pre>
	 * if (BUILTIN_PAYLOADS.containsKey(id) || "minecraft".equals(id.getNamespace())) return;
	 * if (hasChannel(listener, id)) return;
	 * throw new UnsupportedOperationException("Payload %s may not be sent to the client!");
	 * </pre>
	 * 若客户端没声明过它们，服务端会拒绝发送并断连。实测报错：
	 * <pre>
	 * Unable to connect to imm: java.lang.UnsupportedOperationException:
	 *     Payload neoforge:extensible_enum_data may not be sent to the client!
	 * </pre>
	 *
	 * <h2>数据来源</h2>
	 * 由 {@code javap -c} 逐个反汇编 NeoForge 的
	 * {@code net/neoforged/neoforge/network/payload/*.class} 提取
	 * （每个类 {@code <clinit>} 里的
	 * {@code ResourceLocation.fromNamespaceAndPath(namespace, path)}）。
	 * 版本统一为 {@code "1"}（NeoForge 自带 payload 均以 "1" 注册）。
	 *
	 * <p><b>flow 必须与服务端一致</b>（实测教训）：曾因 flow 留空导致协商失败，
	 * 服务端逐条报出原因：
	 * <pre>
	 * ✗ neoforge:extensible_enum_data => failure.flow.client.missing(CLIENTBOUND)
	 * ✗ neoforge:feature_flags_ack    => failure.flow.client.missing(SERVERBOUND)
	 * </pre>
	 * 规则（{@code NetworkComponentNegotiator.validateComponent}）：
	 * 服务端设了 flow 而客户端没设 → 失败。故这里逐个填上正确方向。
	 *
	 * <p>格式：{@code {通道名, 版本, flow}}，flow 中 {@code -1} 表示不声明方向。
	 */
	private static final String[][] BUILTIN_CHANNELS = {
			// 服务端 → 客户端（CLIENTBOUND = 1）
			{"neoforge:advanced_add_entity", "1", "1"},
			{"neoforge:advanced_container_set_data", "1", "1"},
			{"neoforge:advanced_open_screen", "1", "1"},
			{"neoforge:auxiliary_light_data", "1", "1"},
			{"neoforge:custom_time_packet", "1", "1"},
			{"neoforge:config_file", "1", "1"},
			{"neoforge:feature_flags", "1", "1"},
			{"neoforge:frozen_registry", "1", "1"},
			{"neoforge:frozen_registry_sync_start", "1", "1"},
			{"neoforge:known_registry_data_maps", "1", "1"},
			{"neoforge:registry_data_map_sync", "1", "1"},
			{"neoforge:sync_attachments", "1", "1"},
			// 客户端 → 服务端（SERVERBOUND = 0）
			{"neoforge:extensible_enum_ack", "1", "0"},
			{"neoforge:feature_flags_ack", "1", "0"},
			{"neoforge:known_registry_data_maps_reply", "1", "0"},
			// 【实测订正】服务端要求 CLIENTBOUND，不是 SERVERBOUND。
			// 报错原文：flow.client.mismatch(CLIENTBOUND, SERVERBOUND)
			{"neoforge:extensible_enum_data", "1", "1"},
			// 【实测订正】服务端**未设** flow；我们设了反而报 flow.server.missing。
			// 按协商规则：只有"服务端设了而客户端没设"才算失败，
			// 此处置 -1 表示不声明方向。
			{"neoforge:frozen_registry_sync_completed", "1", "-1"},
	};
}
