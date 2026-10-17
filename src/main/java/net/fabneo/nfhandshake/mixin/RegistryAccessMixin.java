package net.fabneo.nfhandshake.mixin;

import net.fabneo.nfhandshake.MissingRegistryTolerance;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 【兜底】让 {@code RegistryAccess.lookupOrThrow(ResourceKey)} 在注册表缺失时
 * 返回一个**空注册表**，而不是抛 {@code IllegalStateException} 导致断连。
 *
 * <h2>为什么需要它</h2>
 * NeoForge 子服的 390 个模组会通过 {@code ClientboundRegistryDataPacket} 同步自己的注册表
 * （方块、物品、实体、配方……）。Fabric 客户端**没有这些模组的代码**，
 * 所以本地 {@code RegistryAccess} 里根本没有对应的 {@code ResourceKey}。
 *
 * <p>真实报错（{@code stage/miniclient/launch-out.log}）：
 * <pre>
 * Failed to handle packet, disconnecting
 * java.lang.IllegalStateException: Missing registry: ResourceKey[minecraft:root / create:contraption_type]
 *   at net.minecraft.class_5455.method_30534(class_5455.java:22)   ← jr.j()，构造异常
 *   at java.base/java.util.Optional.orElseThrow(Optional.java:403)
 *   at net.minecraft.class_5455.method_30530(class_5455.java:22)   ← jr.f() = lookupOrThrow
 *   at net.minecraft.class_9173.method_62160(class_9173.java:57)   ← 注册表同步流程
 * </pre>
 *
 * <h2>为什么注入 lookupOrThrow</h2>
 * 反汇编 {@code RegistryAccess}（混淆名 {@code jr}）确认抛错点唯一：
 * <pre>
 * public abstract &lt;E&gt; Optional&lt;Registry&lt;E&gt;&gt; lookup(ResourceKey)   → a()，返回 Optional（安全）
 * public default  &lt;E&gt; Registry&lt;E&gt; lookupOrThrow(ResourceKey)       → f()：
 *     0: aload_0
 *     1: aload_1
 *     2: invokeinterface a:(Lamt;)Ljava/util/Optional;
 *     7-13: orElseThrow(Supplier)      ← 唯一的抛异常处
 * </pre>
 * 在这里兜底可一次覆盖所有调用方（注册表同步、标签加载、以及后续任何查未知注册表的代码）。
 *
 * <h2>为什么必须用 HEAD + cancellable（踩过的坑）</h2>
 * <b>第一版用了 {@code @At("RETURN")}，完全没生效。</b>
 * 原因：目标方法在 {@code orElseThrow} 处**抛异常**，
 * 根本不执行到 RETURN 指令，所以 RETURN 注入点永远不会被触发。
 * 正确做法是在 {@code HEAD} 拦截：先调 {@code lookup()} 取 Optional，
 * 有值就放行走原逻辑，没值就自己 {@code setReturnValue} 并取消原方法执行。
 *
 * <h2>实现约束（踩过的坑）</h2>
 * <ul>
 *   <li>{@code RegistryAccess} 是 <b>interface</b> → 本 mixin 必须是 interface，
 *       否则报 {@code @Mixin target type mismatch: ... is an interface}</li>
 *   <li>interface mixin <b>不能有非 static 字段</b> → 状态放在
 *       {@link MissingRegistryTolerance}，否则报
 *       {@code InvalidInterfaceMixinException: ... contains a non-shadow field}</li>
 * </ul>
 *
 * <h2>取舍</h2>
 * 返回空注册表 = "这个 mod 的内容一律当作不存在"，正是要的效果：
 * <b>能进去跑图就行，内容缺失可接受</b>。后续遇到这些 mod 的方块/物品时，
 * 会走 vanilla 的未知内容路径（缺失模型 → 紫黑方块 / 空气）。
 */
@Mixin(RegistryAccess.class)
public interface RegistryAccessMixin {

	/**
	 * 在 {@code lookupOrThrow} 入口拦截。
	 *
	 * <p>有值 → 不取消，走原方法（零行为差异）；
	 * 没值 → 用空注册表兜底并取消原方法（避免它抛异常）。
	 */
	@Inject(method = "lookupOrThrow", at = @At("HEAD"), cancellable = true)
	private void nfhandshake$tolerateMissingRegistry(
			ResourceKey<? extends Registry<?>> key,
			CallbackInfoReturnable<Registry<?>> cir) {
		RegistryAccess self = (RegistryAccess) this;
		java.util.Optional<? extends Registry<?>> found = self.lookup(key);
		if (found != null && found.isPresent()) {
			return; // 注册表存在 —— 正常路径
		}
		MissingRegistryTolerance.logOnce(key);
		cir.setReturnValue(MissingRegistryTolerance.emptyFor(key));
	}
}
