package net.fabneo.mcpmod;

import java.io.IOException;
import java.io.Reader;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 运行期可变的配置存储 —— <b>这是"不用每次重建"的关键</b>。
 *
 * <p>三个来源，优先级从高到低：
 * <ol>
 *   <li>通过 {@code /cmd?c=set key value} 在内存里改（立即生效）</li>
 *   <li>外部直接编辑 properties 文件 → 后台线程 1 秒内自动重载</li>
 *   <li>默认值</li>
 * </ol>
 *
 * <p>用 {@code set} 改的值会**写回文件**，保证重启后仍然生效。
 * 写文件时显式用 UTF-8 且不带 BOM（历史上 BOM 导致过配置读不到）。
 */
public final class ConfigStore {

	private final Path file;
	private final Properties props = new Properties();
	private final Map<String, String> runtimeOverrides = new LinkedHashMap<>();
	private volatile long lastModified = -1L;

	public ConfigStore(Path file) {
		this.file = file;
		reload();
	}

	public Path file() {
		return file;
	}

	/** 已载入的全部键值（含运行期覆盖）。 */
	public synchronized Map<String, String> asMap() {
		Map<String, String> out = new LinkedHashMap<>();
		for (String name : props.stringPropertyNames()) {
			out.put(name, props.getProperty(name));
		}
		out.putAll(runtimeOverrides);
		return out;
	}

	public synchronized String get(String key) {
		if (runtimeOverrides.containsKey(key)) {
			return runtimeOverrides.get(key);
		}
		return props.getProperty(key);
	}

	public String get(String key, String def) {
		String v = get(key);
		return v == null ? def : v;
	}

	public int getInt(String key, int def) {
		String v = get(key);
		if (v == null) {
			return def;
		}
		try {
			return Integer.parseInt(v.trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}

	public boolean getBool(String key, boolean def) {
		String v = get(key);
		return v == null ? def : Boolean.parseBoolean(v.trim());
	}

	/** 运行期改值：立即生效 + 写回文件。 */
	public synchronized String set(String key, String value) {
		String old = get(key);
		runtimeOverrides.put(key, value);
		persist();
		return "OK  " + key + ": " + (old == null ? "(未设置)" : old) + " -> " + value;
	}

	/** 文件若被外部修改，自动重新载入（内存覆盖项保留）。 */
	public synchronized void maybeReload() {
		try {
			if (!Files.exists(file)) {
				return;
			}
			long t = Files.getLastModifiedTime(file).toMillis();
			if (t != lastModified) {
				lastModified = t;
				reloadFromDisk();
			}
		} catch (IOException ignored) {
		}
	}

	public synchronized void reload() {
		try {
			if (Files.exists(file)) {
				lastModified = Files.getLastModifiedTime(file).toMillis();
			}
		} catch (IOException ignored) {
		}
		reloadFromDisk();
	}

	private void reloadFromDisk() {
		if (!Files.exists(file)) {
			return;
		}
		Properties fresh = new Properties();
		try (Reader r = new BufferedReader(new InputStreamReader(
				Files.newInputStream(file), StandardCharsets.UTF_8))) {
			BufferedReader br = (BufferedReader) r;
			// 剥离 UTF-8 BOM —— 否则第一个键名会带上 \uFEFF 而读不到
			br.mark(1);
			int first = br.read();
			if (first != 0xFEFF) {
				br.reset();
			}
			fresh.load(br);
		} catch (IOException e) {
			return;
		}
		props.clear();
		props.putAll(fresh);
	}

	/** 把当前全部键值写回文件（UTF-8 无 BOM）。 */
	private synchronized void persist() {
		Map<String, String> all = asMap();
		StringBuilder sb = new StringBuilder();
		sb.append("# mcpmod 实时配置 —— 可直接编辑本文件，mod 会在 1 秒内自动重载\n");
		for (Map.Entry<String, String> e : all.entrySet()) {
			sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
		}
		try {
			Files.createDirectories(file.getParent());
			Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
			lastModified = Files.getLastModifiedTime(file).toMillis();
		} catch (IOException e) {
			// 写失败不影响内存生效
		}
	}

	/** 默认文件位置：{@code <工作区>/stage/miniclient/mcpmod.properties}，便于与已有测试端共存。 */
	public static Path defaultFile() {
		Path p = Paths.get("mcpmod.properties");
		return p.toAbsolutePath();
	}
}
