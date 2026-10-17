package net.fabneo.nfhandshake;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 调试与连接配置（从外部文件读取，**改配置不需要重新构建**）。
 *
 * <h2>设计原则：隐私内容一律不进代码</h2>
 * 本类<b>不再包含任何服务器地址、账号、密码等默认值</b>。
 * 全部敏感信息由用户在配置文件里填写：
 * <ul>
 *   <li>{@code serverAddress}（服务器地址）—— 默认<b>空</b>，未填则不自动连接</li>
 *   <li>{@code password}（登录密码）—— 默认空，且**日志里一律脱敏**</li>
 *   <li>通道表（服务器 mod 清单）—— 由 {@link NeoForgeChannelTable} 从外部文件读取，
 *       不再打包进 jar</li>
 * </ul>
 *
 * <h2>配置文件位置（按优先级）</h2>
 * <ol>
 *   <li>{@code <gameDir>/config/nfhandshake.properties}（推荐）</li>
 *   <li>{@code <gameDir>/nfhandshake.properties}（兼容旧位置）</li>
 * </ol>
 * 系统属性 {@code -Dnfhandshake.config=<路径>} 可显式指定，便于测试。
 *
 * <h2>配置项一览</h2>
 * <pre>
 * # ---- 连接 ----
 * serverAddress=            # 服务器地址，格式 域名:端口；留空则不自动连接
 * autoconnect=false         # 启动后是否自动连接 serverAddress
 *
 * # ---- 自动化 ----
 * autojump=false            # 进入服务器后是否自动执行 subServerCommand
 * subServerCommand=         # 要执行的命令（如 server xxx）；留空表示不执行
 * autologin=false           # 是否自动响应登录提示
 * password=                 # 登录密码（务必不要提交到版本库）
 *
 * # ---- 握手行为 ----
 * configProto=4             # 配置阶段回哪个协议段（4=CONFIGURATION）
 * playProto=1               # PLAY 阶段回哪个协议段（1=PLAY）
 * channelLimit=-1           # 对照实验用：只回前 N 个通道（-1=全部）
 * </pre>
 */
public final class DebugConfig {

	/** 服务器地址。默认<b>空</b>——隐私内容由用户自行填写。 */
	public String serverAddress = "";

	/** 启动后是否自动连接 {@link #serverAddress}。 */
	public boolean autoconnect = false;

	/** 进入服务器后是否自动执行 {@link #subServerCommand}。 */
	public boolean autojump = false;

	/** 进入服务器后要执行的命令（不含前导斜杠），留空表示不执行。 */
	public String subServerCommand = "";

	/** 是否自动响应"请输入 /login"提示。 */
	public boolean autologin = false;

	/** 登录密码。**绝不写入日志**，见 {@link #describe()}。 */
	public String password = "";

	/**
	 * 配置阶段回哪个协议段。
	 * <p>服务器在配置阶段协商的是 {@code CONFIGURATION}(4) 段；
	 * 只回 {@code PLAY}(1) 会让第 4 段被判为空集 → 协商失败被拒。
	 */
	public int configProto = 4;

	/** PLAY 阶段回哪个协议段（1 = PLAY）。 */
	public int playProto = 1;

	/** 对照实验：只回前 N 个通道（-1 = 全部）。 */
	public int channelLimit = -1;

	private DebugConfig() {
	}

	/** 供 {@link #describe()} 使用的脱敏常量。 */
	private static final String MASKED = "******";

	/**
	 * 返回可安全写进日志的配置摘要 —— **密码只显示是否已设置，绝不显示内容**。
	 *
	 * <p>历史上日志里曾直接出现明文密码，这是一个必须堵住的泄露点。
	 */
	public String describe() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("serverAddress", serverAddress == null || serverAddress.isEmpty() ? "(未设置)" : serverAddress);
		m.put("autoconnect", autoconnect);
		m.put("autojump", autojump);
		m.put("subServerCommand", subServerCommand == null || subServerCommand.isEmpty()
				? "(未设置)" : subServerCommand);
		m.put("autologin", autologin);
		// 关键：只报告"是否已配置"，不输出内容
		m.put("password", (password == null || password.isEmpty()) ? "(未设置)" : MASKED);
		m.put("configProto", configProto);
		m.put("playProto", playProto);
		m.put("channelLimit", channelLimit);
		return m.toString();
	}

	/** 密码脱敏工具：任何要打印密码的地方都必须经过它。 */
	public static String mask(String secret) {
		return (secret == null || secret.isEmpty()) ? "(未设置)" : MASKED;
	}

	/** 服务器地址是否已配置（未配置则所有自动连接行为都不应触发）。 */
	public boolean hasServerAddress() {
		return serverAddress != null && !serverAddress.isBlank();
	}

	/** 配置模板写出用的目录缓存（供 {@link #currentGameDir()} 复用）。 */
	private static volatile Path cachedGameDir;

	/**
	 * 取当前游戏目录（{@code .minecraft}），供其他组件定位配置文件。
	 *
	 * <p>由 {@link #load(Path)} 记录，避免各处重复推导导致不一致。
	 */
	public static Path currentGameDir() {
		return cachedGameDir;
	}

	/**
	 * 从磁盘加载配置。
	 *
	 * @param gameDir 游戏目录（通常是 {@code .minecraft}）；为 {@code null} 时只找系统属性指定的路径
	 */
	public static DebugConfig load(Path gameDir) {
		cachedGameDir = gameDir;
		DebugConfig c = new DebugConfig();
		Path p = resolveConfigPath(gameDir);
		NfHandshakeClient.LOGGER.info("[nfhandshake] 配置文件路径: {} (存在={})",
				p, p != null && Files.exists(p));

		if (p == null || !Files.exists(p)) {
			// 首次运行：写出带中文注释的模板，方便用户直接编辑
			writeTemplateIfPossible(gameDir);
			NfHandshakeClient.LOGGER.info(
					"[nfhandshake] 未找到配置文件 —— 以默认值运行（不自动连接）。已尝试写出配置模板。");
			return c;
		}

		Properties props = new Properties();
		try (java.io.BufferedReader br = Files.newBufferedReader(p, java.nio.charset.StandardCharsets.UTF_8)) {
			// 注意：文件若带 UTF-8 BOM，需先剥离，否则第一个键会变成 "\uFEFFserverAddress"
			br.mark(1);
			int first = br.read();
			if (first != 0xFEFF) {
				br.reset();
			}
			props.load(br);

			c.serverAddress = props.getProperty("serverAddress", "").trim();
			c.autoconnect = Boolean.parseBoolean(props.getProperty("autoconnect", "false"));
			c.autojump = Boolean.parseBoolean(props.getProperty("autojump", "false"));
			c.subServerCommand = props.getProperty("subServerCommand", "").trim();
			c.autologin = Boolean.parseBoolean(props.getProperty("autologin", "false"));
			c.password = props.getProperty("password", "");
			c.configProto = parseIntSafe(props.getProperty("configProto"), 4);
			c.playProto = parseIntSafe(props.getProperty("playProto"), 1);
			c.channelLimit = parseIntSafe(props.getProperty("channelLimit"), -1);

			// 用脱敏摘要打日志（绝不打印密码明文）
			NfHandshakeClient.LOGGER.info("[nfhandshake] 配置载入: {}", c.describe());
		} catch (IOException e) {
			NfHandshakeClient.LOGGER.warn("[nfhandshake] 读配置失败，按默认值运行", e);
		}
		return c;
	}

	/** 解析整数，失败时回退默认值（避免一个笔误导致整个配置读取失败）。 */
	private static int parseIntSafe(String s, int def) {
		if (s == null) {
			return def;
		}
		try {
			return Integer.parseInt(s.trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}

	/**
	 * 决定配置文件路径。
	 *
	 * <p>优先级：
	 * <ol>
	 *   <li>系统属性 {@code -Dnfhandshake.config=<路径>}（测试用）</li>
	 *   <li>{@code <gameDir>/config/nfhandshake.properties}（推荐位置）</li>
	 *   <li>{@code <gameDir>/nfhandshake.properties}（兼容旧位置）</li>
	 * </ol>
	 *
	 * <p><b>不再使用任何写死的绝对路径</b>（旧版本曾硬编码开发机路径，既不通用也不安全）。
	 */
	private static Path resolveConfigPath(Path gameDir) {
		String explicit = System.getProperty("nfhandshake.config");
		if (explicit != null && !explicit.isBlank()) {
			return java.nio.file.Paths.get(explicit);
		}
		if (gameDir == null) {
			return null;
		}
		Path preferred = gameDir.resolve("config").resolve("nfhandshake.properties");
		if (Files.exists(preferred)) {
			return preferred;
		}
		Path legacy = gameDir.resolve("nfhandshake.properties");
		if (Files.exists(legacy)) {
			return legacy;
		}
		// 都不存在时返回推荐位置（供首次写出模板）
		return preferred;
	}

	/** 首次运行时写出带中文注释的配置模板，降低使用门槛。 */
	private static void writeTemplateIfPossible(Path gameDir) {
		if (gameDir == null) {
			return;
		}
		try {
			Path dir = gameDir.resolve("config");
			Path file = dir.resolve("nfhandshake.properties");
			if (Files.exists(file)) {
				return;
			}
			Files.createDirectories(dir);
			// 工具类负责生成模板内容（与 NeoForgeChannelTable 的模板风格保持一致）
			Files.writeString(file, ConfigTemplates.MAIN, java.nio.charset.StandardCharsets.UTF_8);
			NfHandshakeClient.LOGGER.info("[nfhandshake] 已生成配置模板: {}", file);
		} catch (Throwable t) {
			NfHandshakeClient.LOGGER.warn("[nfhandshake] 生成配置模板失败（不影响运行）: {}", String.valueOf(t));
		}
	}
}
