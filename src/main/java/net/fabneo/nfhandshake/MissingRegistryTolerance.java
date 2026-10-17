package net.fabneo.nfhandshake;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.serialization.Lifecycle;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;

/**
 * 注册表相关的**三层兜底**状态与工具方法（供 mixin 调用）。
 *
 * <h2>为什么单独放在这个包（不在 mixin 包里）</h2>
 * Mixin 规定：**mixin 配置声明的包里不能存在被直接引用的普通类**，否则运行时报
 * {@code IllegalClassLoadError: ... is in a defined mixin package ... cannot be referenced directly}。
 * 所以工具类放在父包 {@code net.fabneo.nfhandshake}。
 *
 * <p>另外 {@code RegistryAccess} 是 interface，interface mixin 不允许有非 static 字段，
 * 状态也必须放在这里。
 *
 * <h2>三层兜底总览</h2>
 * <ol>
 *   <li><b>缺失注册表</b> → {@link #emptyFor}：返回空注册表，不抛 {@code Missing registry}</li>
 *   <li><b>重复键</b> → {@link #logDuplicateOnce}：跳过重复注册，以本地为准</li>
 *   <li><b>元素解析失败</b> → {@link #logRegistryErrors}：记录后清空错误表继续同步</li>
 * </ol>
 */
public final class MissingRegistryTolerance {

	private MissingRegistryTolerance() {
	}

	// ==================== 第 1 层：缺失注册表 ====================

	/** 每个缺失的注册表键复用一个空注册表实例，避免反复分配。 */
	private static final Map<ResourceKey<?>, Registry<?>> EMPTY = new ConcurrentHashMap<>();

	/** 已经提示过的键（每个只提示一次，避免刷屏）。 */
	private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

	/** 取（或创建）给定键的空注册表。 */
	public static Registry<?> emptyFor(ResourceKey<?> key) {
		return EMPTY.computeIfAbsent(key, MissingRegistryTolerance::newEmptyRegistry);
	}

	/** 用给定的 resource key 造一个空注册表（已 freeze）。 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Registry<?> newEmptyRegistry(ResourceKey<?> key) {
		MappedRegistry<?> reg = new MappedRegistry((ResourceKey) key, Lifecycle.stable());
		// 【必须 freeze】注册表同步流程下一步会调
		//   MappedRegistry.getOrCreateTagForRegistration(TagKey)
		// 它内部检查 `frozen` 标志，未冻结就抛：
		//   IllegalStateException: Invalid method used for tag loading
		// 空注册表本来就再无内容可注册，直接冻结是安全的。
		reg.freeze();
		return reg;
	}

	/** 首次遇到某注册表缺失时记一条日志。 */
	public static void logOnce(ResourceKey<?> key) {
		if (SEEN.add(key.toString())) {
			org.slf4j.LoggerFactory.getLogger("nfhandshake").warn(
					"[nfhandshake] 注册表缺失，已用空注册表兜底: {}（该 mod 的内容将不可见）", key);
		}
	}

	/** 已兜底过的注册表数量（供诊断用）。 */
	public static int toleratedCount() {
		return EMPTY.size();
	}

	// ==================== 第 2 层：重复键 ====================

	/** 重复键提示：按注册表归类，每个注册表只提示一次（315 条会刷屏）。 */
	private static final Set<String> DUP_SEEN = ConcurrentHashMap.newKeySet();
	private static final AtomicInteger DUP_COUNT = new AtomicInteger();

	/** 跳过重复注册时记一条日志（按注册表去重，避免刷屏）。 */
	public static void logDuplicateOnce(ResourceKey<?> key) {
		DUP_COUNT.incrementAndGet();
		String s = key.toString();
		String registry = s;
		int slash = s.indexOf(" / ");
		if (slash > 0) {
			registry = s.substring(0, slash);
		}
		if (DUP_SEEN.add(registry)) {
			org.slf4j.LoggerFactory.getLogger("nfhandshake").warn(
					"[nfhandshake] 跳过重复注册（以本地为准）: registry={}（后续同类不再提示）", registry);
		}
	}

	/** 已跳过的重复注册总数。 */
	public static int duplicateSkippedCount() {
		return DUP_COUNT.get();
	}

	// ==================== 第 3 层：元素解析失败 ====================

	private static final AtomicInteger SKIPPED_ELEMENTS = new AtomicInteger();

	/**
	 * 记录注册表**逐元素解析失败**的详情（第三层兜底用）。
	 *
	 * <p>这些元素引用了客户端不存在的模组内容，例如
	 * {@code create:potato_recovery} 附魔引用物品 {@code create:potato_cannon}。
	 * 我们选择跳过它们而不是中断同步，但必须把原因写进日志，否则问题会变成黑盒。
	 */
	public static void logRegistryErrors(Map<?, ?> errors) {
		org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger("nfhandshake");
		log.warn("[nfhandshake] 注册表元素解析失败 {} 处 —— 已跳过这些内容（对应 mod 未安装），继续同步",
				errors.size());
		int shown = 0;
		for (Map.Entry<?, ?> e : errors.entrySet()) {
			if (shown++ >= 10) {
				log.warn("[nfhandshake]   ... 其余 {} 处省略", errors.size() - 10);
				break;
			}
			Throwable t = (e.getValue() instanceof Throwable th) ? th : null;
			String msg = t == null ? String.valueOf(e.getValue()) : String.valueOf(t.getMessage());
			log.warn("[nfhandshake]   ✗ {} => {}", e.getKey(), msg);
		}
		SKIPPED_ELEMENTS.addAndGet(errors.size());
	}

	/** 已跳过的注册表元素总数（供诊断用）。 */
	public static int skippedElementCount() {
		return SKIPPED_ELEMENTS.get();
	}

	// ==================== 第 4 层：未绑定的时间线 ====================

	private static final Set<String> UNBOUND_TIMELINE_SEEN = ConcurrentHashMap.newKeySet();

	/**
	 * 时间线 holder 未绑定时记一条日志。
	 *
	 * <p>{@code minecraft:timeline} 是 MC 1.21.11 新增注册表，1.21.1 服务端不会同步它；
	 * 本应由客户端内置的 {@code data/minecraft/timeline/*.json} 提供。
	 */
	public static void logUnboundTimelineOnce(net.minecraft.core.Holder<?> holder) {
		String id = holder == null ? "(null)" : String.valueOf(holder.unwrapKey().orElse(null));
		if (UNBOUND_TIMELINE_SEEN.add(id)) {
			org.slf4j.LoggerFactory.getLogger("nfhandshake").warn(
					"[nfhandshake] 时间线未绑定，已跳过该图层（环境属性退化为静态）: {}: {}（后续同类不再提示）",
					id, holder == null ? "null" : holder.getClass().getSimpleName());
		}
	}

	/** 已跳过的时间线图层数（供诊断用）。 */
	public static int skippedTimelineCount() {
		return UNBOUND_TIMELINE_SEEN.size();
	}
}
