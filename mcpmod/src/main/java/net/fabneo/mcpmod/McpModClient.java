package net.fabneo.mcpmod;

import java.nio.file.Path;
import java.nio.file.Paths;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP Mod —— 在游戏内运行一个本地命令桥，让外部（Agent/脚本）
 * <b>无需重启客户端</b>即可：
 * <ul>
 *   <li>读日志（结构化环形缓冲，可直接 grep）</li>
 *   <li>改配置（立即生效并写回文件）</li>
 *   <li>操作界面（列控件、点按钮、切屏幕）</li>
 *   <li><b>在输入框打字并发送</b>（真·UI 路径或直发路径）</li>
 *   <li>连接服务器、发游戏命令</li>
 * </ul>
 *
 * <p>设计约束（本机 3.6GB 内存）：
 * 只用 JDK 自带 {@code com.sun.net.httpserver}，零第三方依赖；
 * 只监听 127.0.0.1；单线程池。
 */
public class McpModClient implements ClientModInitializer {

	public static final String MOD_ID = "mcpmod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final int DEFAULT_PORT = 25199;

	private CommandServer server;
	private ConfigStore config;

	@Override
	public void onInitializeClient() {
		// 配置文件位置：优先用系统属性 -Dmcpmod.config=<路径>，
		// 否则放在游戏目录下的 config/mcpmod.properties。
		// 【隐私】不写死任何本机绝对路径或服务器地址。
		Path cfgFile = resolveConfigFile();

		config = new ConfigStore(cfgFile);

		// 首次运行写入默认值，便于直接编辑。
		// 【注意】serverAddress 默认留空 —— 真实地址由用户自行填写，
		// 不把任何具体服务器写进代码。
		if (!java.nio.file.Files.exists(cfgFile)) {
			config.set("port", String.valueOf(DEFAULT_PORT));
			config.set("autoconnect", "false");
			config.set("serverAddress", "");
			config.set("logRingSize", "4000");
		}

		CommandRegistry registry = new CommandRegistry(config);
		registerCommands(registry);

		// 聊天捕获：把服务器/系统发的聊天与动作栏文本收进环形缓冲，
		// 这样外部可以直接"取反馈"，不必去翻日志文件。
		// 签名与 nfhandshake 中已验证可用的用法一致。
		net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register(
				(message, overlay) -> {
					String text = message == null ? "" : message.getString();
					if (!text.isEmpty()) {
						CommandRegistry.LOG.add((overlay ? "[ACTIONBAR] " : "[CHAT] ") + text);
					}
				});

		// 关键里程碑也进环形缓冲，便于外部 grep
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register(
				(handler, sender, client) -> CommandRegistry.LOG.add("[EVENT] 进入世界(play JOIN)"));

		int port = config.getInt("port", DEFAULT_PORT);
		try {
			// 端口被占用时自动向后找一个可用端口，避免"起不来"这种低级阻塞
			server = startOnFreePort(port, registry);
			LOGGER.info("[mcpmod] 命令桥已启动: http://127.0.0.1:{}/cmd?c=help", server.port());
			LOGGER.info("[mcpmod] 配置文件: {}（可直接编辑，1 秒内自动重载）", cfgFile);
		} catch (Throwable t) {
			LOGGER.error("[mcpmod] 命令桥启动失败", t);
		}

		// 后台线程：自动重载配置（这就是"改参数不用重建"的关键）
		Thread watcher = new Thread(() -> {
			while (true) {
				try {
					config.maybeReload();
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					return;
				} catch (Throwable ignored) {
				}
			}
		}, "mcpmod-cfg-watch");
		watcher.setDaemon(true);
		watcher.start();

		// 自动连服（可选）
		// 【隐私】serverAddress 由用户配置，未配置时不自动连接。
		if (config.getBool("autoconnect", false)) {
			net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED
					.register(client -> {
						Thread t = new Thread(() -> {
							try {
								Thread.sleep(8000);
								String addr = config.get("serverAddress", "");
								if (addr == null || addr.isBlank()) {
									LOGGER.warn("[mcpmod] autoconnect=true 但未配置 serverAddress，跳过自动连接");
									return;
								}
								UiDriver.connect(addr);
							} catch (InterruptedException ignored) {
							}
						}, "mcpmod-autoconnect");
						t.setDaemon(true);
						t.start();
					});
		}
	}

	/**
	 * 决定配置文件路径。
	 *
	 * <p>优先级：
	 * <ol>
	 *   <li>系统属性 {@code -Dmcpmod.config=<路径>}</li>
	 *   <li>{@code <gameDir>/config/mcpmod.properties}</li>
	 *   <li>兜底：当前工作目录下的 {@code mcpmod.properties}</li>
	 * </ol>
	 * <b>不写死任何本机绝对路径</b>（旧版本曾硬编码开发机路径）。
	 */
	private static Path resolveConfigFile() {
		String explicit = System.getProperty("mcpmod.config");
		if (explicit != null && !explicit.isBlank()) {
			return Paths.get(explicit);
		}
		try {
			// FabricLoader 的 configDir 直接就是 <gameDir>/config
			Path cfgDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
			if (cfgDir != null) {
				return cfgDir.resolve("mcpmod.properties");
			}
		} catch (Throwable ignored) {
		}
		return Paths.get("mcpmod.properties").toAbsolutePath();
	}

	private void registerCommands(CommandRegistry r) {
		// ---- 界面/UI 驱动 ----
		r.cmd("screen", a -> UiDriver.dumpScreen());
		r.cmd("click", a -> {
			String s = a.trim();
			if (s.matches("\\d+")) {
				return UiDriver.clickIndex(Integer.parseInt(s));
			}
			return UiDriver.clickButton(s);
		});
		r.cmd("type", a -> UiDriver.typeAndSend(a));
		r.cmd("say", a -> UiDriver.sendDirect(a));
		r.cmd("title", a -> UiDriver.toTitle());
		r.cmd("closescreen", a -> UiDriver.closeScreen());
		r.cmd("connect", a -> UiDriver.connect(a.trim()));
	}

	/** 从 port 起找一个可用端口。 */
	private static CommandServer startOnFreePort(int start, CommandRegistry registry) throws Exception {
		Exception last = null;
		for (int p = start; p < start + 20; p++) {
			try {
				CommandServer s = new CommandServer(p, registry);
				s.start();
				return s;
			} catch (Exception e) {
				last = e;
			}
		}
		throw last == null ? new IllegalStateException("无可用端口") : last;
	}
}
