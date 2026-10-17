package net.fabneo.nfhandshake.mixin;

import net.fabneo.nfhandshake.MissingRegistryTolerance;
import net.minecraft.core.Holder;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.timeline.Timeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 【第 4 层兜底】进入世界时，时间线（timeline）注册表里若有**未绑定的 holder**，
 * 整体跳过该时间线图层，而不抛异常打断进入世界。
 *
 * <h2>为什么会出现未绑定值</h2>
 * {@code minecraft:timeline} 是 <b>MC 1.21.11 新增</b>的注册表，1.21.1 服务端根本不知道它，
 * 因此服务端注册表同步**永远不会发它**。它本应由客户端内置资源提供：
 * <pre>
 * 1.21.11 客户端 jar 内：
 *   data/minecraft/timeline/day.json
 *   data/minecraft/timeline/early_game.json
 *   data/minecraft/timeline/moon.json
 *   data/minecraft/timeline/villager_schedule.json
 * </pre>
 * 但第三层兜底跳过注册表同步错误后，该注册表可能残留**未绑定**引用。
 *
 * <p>真实报错（{@code launch-out.log}，发生在进入世界的最后一个包
 * {@code ClientboundLoginPacket} 内）：
 * <pre>
 * Failed to handle packet class_2678[...]
 * java.lang.IllegalStateException: Trying to access unbound value
 *     'ResourceKey[minecraft:timeline / minecraft:day]'
 *   at net.minecraft.class_6880$class_6883.comp_349(class_6880.java:178)   ← Holder.Reference.value()
 *   at net.minecraft.class_12205$class_12314.method_76420(class_12205.java:179)
 *   at net.minecraft.class_12205.method_76402(class_12205.java:60)
 *   at net.minecraft.class_638.<init>(class_638.java:233)                  ← ClientLevel 构造
 * </pre>
 *
 * <h2>为什么是 HEAD + cancellable，而不是 Redirect value()</h2>
 * 反汇编 {@code EnvironmentAttributeSystem.Builder}（{@code cef$a}）的
 * {@code addTimelineLayer(Holder&lt;Timeline&gt;, LongSupplier)}：
 * <pre>
 * 0: aload_1
 * 1: invokeinterface Holder.value()      ← 未绑定则在此抛异常
 * 6: checkcast Timeline
 * 9: invokevirtual Timeline.attributeTracks()   ← 若上一步返回 null，这里必然 NPE
 * ...
 * </pre>
 * <b>所以不能简单地让 {@code value()} 返回 {@code null}</b>（会在下一步
 * {@code Timeline.attributeTracks()} 处 NPE）。正确做法是在方法入口判断
 * holder 是否已绑定：未绑定就整体取消这次图层添加。
 *
 * <h2>代价</h2>
 * 时间线驱动的环境属性（天空/云颜色随时间变化等）退化为静态值。
 * 纯视觉影响，符合"能进去跑图就行"的预期。
 */
@Mixin(EnvironmentAttributeSystem.Builder.class)
public class TimelineRegistryFallbackMixin {

	/**
	 * 在 {@code addTimelineLayer} 入口：holder 未绑定就跳过整层。
	 */
	@Inject(method = "addTimelineLayer", at = @At("HEAD"), cancellable = true)
	private void nfhandshake$skipUnboundTimeline(
			Holder<Timeline> holder,
			java.util.function.LongSupplier supplier,
			CallbackInfoReturnable<Object> cir) {
		if (holder == null || !holder.isBound()) {
			MissingRegistryTolerance.logUnboundTimelineOnce(holder);
			cir.setReturnValue(null); // 跳过这一层；调用方不使用返回值
		}
	}
}
