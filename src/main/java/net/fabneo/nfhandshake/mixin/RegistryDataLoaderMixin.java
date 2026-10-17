package net.fabneo.nfhandshake.mixin;

import java.util.List;
import java.util.Map;

import net.fabneo.nfhandshake.MissingRegistryTolerance;
import net.minecraft.resources.RegistryDataLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 【第三层兜底】让注册表**逐元素解析失败**时不再中断整个同步流程。
 *
 * <h2>为什么需要（= "模组内容缺失"的第一手实证）</h2>
 * 前两层兜底之后暴露出真正的根本障碍：
 * <pre>
 * Registry loading errors:
 * > Errors in registry minecraft:enchantment:
 * >> Errors in element create:potato_recovery:
 * Failed to parse value {..., supported_items:"create:potato_cannon", ...} from server
 * </pre>
 * 服务端的附魔引用了物品 {@code create:potato_cannon}，客户端**没有 Create 模组**，
 * 解析不出该物品 → 抛异常 → 整个注册表同步失败 → 断连。
 *
 * <h2>为什么不能注入 logErrors（第一版错在哪）</h2>
 * 反汇编 {@code RegistryDataLoader}（{@code amp}）确认真实控制流：
 * <pre>
 * 收集：loadContentsFromNetwork → 逐元素 parse 失败时 Map.put（只记录，不抛）
 * 抛出：load(LoadingFunction, List, List)  method_45121：
 *        61-69: 对每个 registry 调用收集逻辑
 *        74-75: aload_3; Map.isEmpty()
 *        80:    ifne 88            ← 空 → 正常返回
 *        83-84: logErrors(map)    ← 非空 → 构造 ReportedException
 *        87:    athrow            ← 无条件抛出
 * </pre>
 *
 * <p><b>第一版注入 {@code logErrors} 的 HEAD 并清空 map，无效</b>：
 * 因为 {@code logErrors} 内部**无条件**调用 {@code createReportWithBriefInfo(map)}
 * 构造并返回异常，调用方紧接着 {@code athrow}，清空 map 无法阻止抛出。
 *
 * <h2>本版做法</h2>
 * 用 {@link Redirect} 劫持 {@code load} 里那次 {@code Map.isEmpty()} 调用：
 * 先把日志写全（保住可观测性）→ 清空 map → 返回其真实状态。
 * 清空后必然为空，于是分支走 {@code ifne 88}（无错误路径），跳过 {@code athrow}。
 *
 * <h2>副作用与配套处理（重要）</h2>
 * 跳过错误后，{@code minecraft:timeline} 这个注册表可能留下**未绑定**的 holder，
 * 导致进入世界时崩在 {@code EnvironmentAttributeSystem}：
 * <pre>
 * Trying to access unbound value 'ResourceKey[minecraft:timeline / minecraft:day]'
 * </pre>
 * 详见 {@code TimelineRegistryFallbackMixin}（第 4 层兜底）。
 */
@Mixin(RegistryDataLoader.class)
public class RegistryDataLoaderMixin {

	/**
	 * 劫持 {@code load} 中的 {@code Map.isEmpty()} 判定，
	 * 使"有解析错误"时也走无错误分支，不再抛出 {@code Registry Loading}。
	 */
	@Redirect(
			method = "load(Lnet/minecraft/resources/RegistryDataLoader$LoadingFunction;Ljava/util/List;Ljava/util/List;)Lnet/minecraft/core/RegistryAccess$Frozen;",
			at = @At(value = "INVOKE", target = "Ljava/util/Map;isEmpty()Z"))
	private static boolean nfhandshake$tolerateElementErrors(Map<?, ?> errors) {
		if (errors == null || errors.isEmpty()) {
			return true; // 本来就没错 —— 正常路径
		}
		MissingRegistryTolerance.logRegistryErrors(errors);
		errors.clear();
		// 清空后必然为空 → 调用方跳过 athrow，继续正常构造注册表
		return errors.isEmpty();
	}
}
