package net.fabneo.mcpmod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import net.minecraft.client.Minecraft;

/**
 * 命令注册与执行中心。
 *
 * <p><b>核心设计</b>：命令体在<b>客户端主线程</b>上执行（Minecraft 的绝大多数 API
 * 只能在主线程调用），HTTP 线程只负责提交与等待结果。
 * 这样既满足线程约束，又保留了"外部随时投递命令"的能力。
 *
 * <p>内置命令（全部可热用，改配置无需重启）：
 * <pre>
 * help                     列出所有命令
 * ping                     连通性 + 当前状态
 * state                    打印当前连接/协商状态
 * set &lt;key&gt; &lt;value&gt;        热改配置（立即生效，写回 properties）
 * get &lt;key&gt;                读配置
 * send &lt;channel&gt;           主动发一个 custom_payload（调试用）
 * cmd &lt;游戏命令&gt;            以玩家身份发聊天/命令
 * log [n]                  取最近 n 行客户端日志
 * reconfig                 强制重新发起握手应答
 * </pre>
 */
public final class CommandRegistry {

	/** 结构化日志环形缓冲：外部可以直接"取日志"，不必去翻文件。 */
	public static final class LogRing {
		private final ConcurrentLinkedDeque<String> lines = new ConcurrentLinkedDeque<>();
		private final AtomicInteger count = new AtomicInteger();
		private static final int MAX = 4000;

		public void add(String line) {
			lines.addLast(line);
			while (lines.size() > MAX) {
				lines.pollFirst();
			}
		}

		public List<String> tail(int n) {
			List<String> all = new ArrayList<>(lines);
			int from = Math.max(0, all.size() - n);
			return all.subList(from, all.size());
		}

		public String grep(String needle, int limit) {
			StringBuilder sb = new StringBuilder();
			int shown = 0;
			for (String l : lines) {
				if (l.contains(needle)) {
					sb.append(l).append('\n');
					if (++shown >= limit) {
						break;
					}
				}
			}
			return sb.toString();
		}
	}

	public static final LogRing LOG = new LogRing();

	/** 记录"我方"收到的服务器查询/应答，供 state 命令展示。 */
	public static final Map<String, String> STATE = new LinkedHashMap<>();

	private final Map<String, Function<String, String>> commands = new LinkedHashMap<>();
	private final ConfigStore config;

	public CommandRegistry(ConfigStore config) {
		this.config = config;
		registerBuiltins();
	}

	private void registerBuiltins() {
		cmd("help", args -> {
			StringBuilder sb = new StringBuilder("可用命令:\n");
			for (String k : commands.keySet()) {
				sb.append("  ").append(k).append('\n');
			}
			return sb.toString();
		});

		cmd("ping", args -> "pong  t=" + System.currentTimeMillis() + "  " + statusLine());

		cmd("state", args -> {
			StringBuilder sb = new StringBuilder();
			sb.append("玩家: ").append(playerName()).append('\n');
			sb.append("连接: ").append(connectionSummary()).append('\n');
			sb.append("配置:\n");
			config.asMap().forEach((k, v) -> sb.append("  ").append(k).append(" = ").append(v).append('\n'));
			sb.append("STATE:\n");
			STATE.forEach((k, v) -> sb.append("  ").append(k).append(" = ").append(v).append('\n'));
			return sb.toString();
		});

		cmd("set", args -> {
			String[] parts = splitFirst(args);
			if (parts.length < 2) {
				return "用法: set <key> <value>";
			}
			return config.set(parts[0], parts[1]);
		});

		cmd("get", args -> {
			String v = config.get(args.trim());
			return v == null ? ("(无此键: " + args.trim() + ")") : (args.trim() + " = " + v);
		});

		cmd("cmd", args -> {
			if (args.isBlank()) {
				return "用法: cmd <游戏命令，如 server imm>";
			}
			return onClientThread(mc -> {
				if (mc.player == null || mc.player.connection == null) {
					return "玩家未就绪";
				}
				String c = args.startsWith("/") ? args.substring(1) : args;
				mc.player.connection.sendCommand(c);
				return "已发送 /" + c;
			});
		});

		cmd("chat", args -> onClientThread(mc -> {
			if (mc.player == null) {
				return "玩家未就绪";
			}
			mc.player.connection.sendChat(args);
			return "已发送聊天: " + args;
		}));

		cmd("send", args -> onClientThread(mc -> {
			if (mc.getConnection() == null) {
				return "无连接";
			}
			return "send 需要在具体 payload 类型上实现，当前仅用于验证桥路: " + args;
		}));

		cmd("log", args -> {
			int n = 60;
			try {
				if (!args.isBlank()) {
					n = Integer.parseInt(args.trim());
				}
			} catch (NumberFormatException ignored) {
			}
			return String.join("\n", LOG.tail(n));
		});

		cmd("grep", args -> {
			String[] parts = splitFirst(args);
			if (parts.length < 1 || parts[0].isBlank()) {
				return "用法: grep <关键字> [条数]";
			}
			int n = 50;
			if (parts.length > 1) {
				try {
					n = Integer.parseInt(parts[1].trim());
				} catch (NumberFormatException ignored) {
				}
			}
			String r = LOG.grep(parts[0], n);
			return r.isEmpty() ? ("(无匹配: " + parts[0] + ")") : r;
		});

		cmd("reconnect", args -> onClientThread(mc -> {
			if (mc.getConnection() != null) {
				mc.getConnection().getConnection().disconnect(
						net.minecraft.network.chat.Component.literal("mcpmod: reconnect"));
				return "已断开，请用 cmd /server ... 或重启连接";
			}
			return "无连接";
		}));

		cmd("eval", args -> "eval 已禁用（安全考虑）；请用具体命令。");
	}

	/** 注册一条命令。 */
	public void cmd(String name, Function<String, String> impl) {
		commands.put(name, impl);
	}

	/** 供外部在无客户端依赖时注入命令（便于扩展）。 */
	public String execute(String raw) throws Exception {
		String trimmed = raw.trim();
		if (trimmed.startsWith("/")) {
			trimmed = trimmed.substring(1);
		}
		String[] parts = splitFirst(trimmed);
		String name = parts.length > 0 ? parts[0] : "";
		String args = parts.length > 1 ? parts[1] : "";
		Function<String, String> impl = commands.get(name);
		if (impl == null) {
			return "未知命令: " + name + "\n用 help 查看全部命令";
		}
		return impl.apply(args);
	}

	// ---------- helpers ----------

	private static String[] splitFirst(String s) {
		String t = s.trim();
		int sp = t.indexOf(' ');
		if (sp < 0) {
			return new String[] { t, "" };
		}
		return new String[] { t.substring(0, sp), t.substring(sp + 1) };
	}

	private static String playerName() {
		Minecraft mc = Minecraft.getInstance();
		return mc == null || mc.player == null ? "(未进入世界)" : mc.player.getName().getString();
	}

	private static String connectionSummary() {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.getConnection() == null) {
			return "(无连接)";
		}
		// 注意：player 在切服/重连的瞬间会短暂为 null，不能据此判断"未连接"。
		// 以 getConnection() + level 为准，并额外报告 player 是否就绪。
		boolean playerReady = mc.player != null;
		boolean inLevel = mc.level != null;
		String target = mc.getCurrentServer() == null ? "(本地/未知)" : mc.getCurrentServer().ip;
		return "已连接（" + target + "） player=" + playerReady + " level=" + inLevel;
	}

	private static String statusLine() {
		Minecraft mc = Minecraft.getInstance();
		boolean inWorld = mc != null && mc.player != null;
		return "inWorld=" + inWorld;
	}

	/**
	 * 在客户端主线程上执行并等待结果（最多 10 秒）。
	 *
	 * <p>Minecraft 的绝大多数 API 都要求主线程；HTTP 线程直接调用会崩。
	 * 这里用 {@code mc.execute(...)} 投递并阻塞等待，调用方（HTTP 线程）拿结果。
	 */
	public static String onClientThread(Function<Minecraft, String> fn) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null) {
			return "Minecraft 实例不可用";
		}
		if (mc.isSameThread()) {
			return fn.apply(mc);
		}
		CompletableFuture<String> f = new CompletableFuture<>();
		mc.execute(() -> {
			try {
				f.complete(fn.apply(mc));
			} catch (Throwable t) {
				f.complete("ERROR: " + t);
			}
		});
		try {
			return f.get(10, TimeUnit.SECONDS);
		} catch (Exception e) {
			return "等待主线程超时/失败: " + e;
		}
	}
}
