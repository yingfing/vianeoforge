package net.fabneo.mcpmod;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 极简本地命令服务器 —— 让外部（Agent/脚本）能在**不重启客户端**的前提下
 * 提交文本命令并取回执行结果。
 *
 * <p>设计取舍（针对本机 3.6GB 内存）：
 * <ul>
 *   <li>只用 JDK 自带的 {@code com.sun.net.httpserver}，<b>零第三方依赖</b></li>
 *   <li>默认只监听 {@code 127.0.0.1}，不对外暴露</li>
 *   <li>单线程池，避免线程堆积</li>
 * </ul>
 *
 * <p>接口：
 * <pre>
 * GET  /cmd?c=&lt;命令&gt;   执行命令，返回纯文本结果
 * GET  /health         返回 {"ok":true}
 * </pre>
 *
 * <p>命令可以带参数，例如：{@code /cmd?c=set configProto 4}、
 * {@code /cmd?c=send neoforge:register}、{@code /cmd?c=log 50}。
 */
public final class CommandServer {

	private final HttpServer server;
	private final CommandRegistry registry;

	public CommandServer(int port, CommandRegistry registry) throws IOException {
		this.registry = registry;
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
		this.server.createContext("/cmd", this::handleCmd);
		this.server.createContext("/health", this::handleHealth);
		this.server.setExecutor(Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "mcpmod-http");
			t.setDaemon(true);
			return t;
		}));
	}

	public void start() {
		server.start();
	}

	public void stop() {
		server.stop(0);
	}

	public int port() {
		return server.getAddress().getPort();
	}

	private void handleHealth(HttpExchange ex) throws IOException {
		respond(ex, 200, "{\"ok\":true}", "application/json");
	}

	private void handleCmd(HttpExchange ex) throws IOException {
		Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
		String raw = q.getOrDefault("c", "");
		if (raw.isEmpty()) {
			respond(ex, 400, "缺少参数 c（命令）", "text/plain; charset=utf-8");
			return;
		}

		String result;
		try {
			result = registry.execute(raw);
		} catch (Throwable t) {
			// 单条命令失败绝不能让桥挂掉
			result = "ERROR: " + t + "\n" + stackTrace(t);
		}
		respond(ex, 200, result, "text/plain; charset=utf-8");
	}

	private static void respond(HttpExchange ex, int code, String body, String contentType)
			throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", contentType);
		// 便于脚本/Agent 直接读
		ex.sendResponseHeaders(code, bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}

	/** 解析 {@code a=1&b=2}，做 URL 解码（把 + 当空格处理）。 */
	public static Map<String, String> parseQuery(String raw) {
		Map<String, String> out = new LinkedHashMap<>();
		if (raw == null || raw.isEmpty()) {
			return out;
		}
		for (String pair : raw.split("&")) {
			if (pair.isEmpty()) {
				continue;
			}
			int eq = pair.indexOf('=');
			String k = eq < 0 ? pair : pair.substring(0, eq);
			String v = eq < 0 ? "" : pair.substring(eq + 1);
			out.put(urlDecode(k), urlDecode(v));
		}
		return out;
	}

	private static String urlDecode(String s) {
		try {
			return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
		} catch (Throwable t) {
			return s;
		}
	}

	private static String stackTrace(Throwable t) {
		StringBuilder sb = new StringBuilder();
		for (StackTraceElement e : t.getStackTrace()) {
			sb.append("  at ").append(e).append('\n');
			if (sb.length() > 2000) {
				break;
			}
		}
		return sb.toString();
	}
}
