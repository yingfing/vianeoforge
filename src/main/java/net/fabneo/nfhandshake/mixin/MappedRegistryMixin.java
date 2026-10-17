package net.fabneo.nfhandshake.mixin;

import net.fabneo.nfhandshake.MissingRegistryTolerance;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 【兜底】让 {@code MappedRegistry.register(...)} 在**键已存在**时直接返回，
 * 而不是抛 {@code IllegalStateException: Adding duplicate key ...}。
 *
 * <h2>为什么需要它</h2>
 * 打通注册表同步后，出现新的、性质不同的错误（{@code launch-out.log}）：
 * <pre>
 * Caused by: java.lang.IllegalStateException: Adding duplicate key
 *     'ResourceKey[minecraft:worldgen/biome / minecraft:plains]' to registry
 * </pre>
 * 共 315 条，涉及的**全是 vanilla 注册表**：
 *
 * <table border="1">
 * <tr><th>注册表</th><th>重复条数</th></tr>
 * <tr><td>minecraft:painting_variant</td><td>50</td></tr>
 * <tr><td>minecraft:damage_type</td><td>49</td></tr>
 * <tr><td>minecraft:banner_pattern</td><td>43</td></tr>
 * <tr><td>minecraft:enchantment</td><td>42</td></tr>
 * <tr><td>minecraft:jukebox_song</td><td>19</td></tr>
 * <tr><td>…（共 10 个注册表）</td><td>315</td></tr>
 * </table>
 *
 * <p><b>性质与前几个卡点不同</b>：
 * 前面都是"客户端缺东西"（缺通道、缺注册表）→ 可以"当作没有"兜底；
 * 这个是"服务端重发了客户端已有的东西"（vanilla 注册表又被同步了一遍）
 * → 属于注册表同步协商不一致，而非内容缺失。
 *
 * <h2>为什么注入 register</h2>
 * 反汇编 {@code MappedRegistry}（混淆名 {@code jl}）确认抛错点：
 * <pre>
 * public Holder.Reference&lt;T&gt; register(ResourceKey, T, RegistrationInfo) → a()
 *     0-2:  h(key)              // validateWrite，冻结检查
 *    15-28: byLocation.containsKey(key.location())   ← 第一个抛错点
 *   31-53:  throw new IllegalStateException("Adding duplicate key ...")
 *    54-64: byValue.containsKey(value)               ← 第二个抛错点
 *   67-89:  throw ... (duplicate value)
 * </pre>
 *
 * <h2>为何"忽略重复"是安全的</h2>
 * 同名条目**已经在本地注册表里**，客户端本来就拥有该内容。
 * 直接跳过服务端这次重复注册，等价于"以本地为准"——正是我们要的
 * <b>能进去跑图就行</b>。不会造成内容错乱，只是不覆盖。
 *
 * <p>用 {@code HEAD} + {@code cancellable}（不能像上次那样用 RETURN —— 目标方法会抛异常，
 * RETURN 永远到不了；这是本项目已经踩过一次的坑）。
 *
 * <p><b>注意</b>：{@code MappedRegistry} 是 <b>class</b>（不是 interface），
 * 所以本 mixin 必须声明为 <b>class</b>。这与 {@code RegistryAccessMixin} 恰好相反
 * （那个目标是 interface，所以必须是 interface）。搞反会报：
 * <pre>
 * @Mixin target type mismatch: net.minecraft.class_2370 is not an interface
 * </pre>
 */
@Mixin(MappedRegistry.class)
public class MappedRegistryMixin {

	/**
	 * 注册前先查是否已存在；存在就跳过本次注册并返回 {@code null}。
	 *
	 * <p>返回 {@code null} 是安全的：调用方（注册表同步）不依赖 register 的返回值。
	 */
	@Inject(method = "register", at = @At("HEAD"), cancellable = true)
	private void nfhandshake$skipDuplicateKey(
			ResourceKey<?> key,
			Object value,
			RegistrationInfo info,
			CallbackInfoReturnable<Object> cir) {
		if (key == null) {
			return;
		}
		// containsKey(ResourceKey) 是泛型方法，这里 key 是通配符，需要裸类型调用
		if (containsKeyUnchecked(this, key)) {
			MissingRegistryTolerance.logDuplicateOnce(key);
			cir.setReturnValue(null);
		}
	}

	/** 以裸类型调用 {@code Registry.containsKey(ResourceKey)}，规避通配符捕获。 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static boolean containsKeyUnchecked(Object self, ResourceKey<?> key) {
		return ((net.minecraft.core.Registry) self).containsKey((ResourceKey) key);
	}
}
