# 技术笔记 —— NeoForge 握手协议逆向

> 本文汇总本项目在实现 Fabric ↔ NeoForge 握手适配过程中
> 掌握的全部协议事实与技术要点。
> **已脱敏**：不含任何真实服务器地址、账号或通道清单。

---

## 一、协议概览

NeoForge 在 vanilla 的配置阶段（configuration phase）之上，
额外插入了一套**通道协商**流程。

### 1.1 涉及的自定义负载

| 通道 | 方向 | 作用 | 线格式 |
|---|---|---|---|
| `neoforge:register` | S→C | 服务器下发通道查询 | `VAR_INT mapSize` + 每段 `(proto, count, 组件×count)` |
| `neoforge:register` | C→S | 客户端应答通道声明 | 同上 |
| `neoforge:network` | S→C | 协商结果（setup） | `VAR_INT mapSize` + `(proto, count, (id, version)×count)` |
| `neoforge:split` | 双向 | 超长包分片 | `VAR_INT len` + `byte[len]` |
| `neoforge:modded_network_setup_failed` | S→C | **协商失败原因** | `Map<Identifier, Component>` |
| `neoforge:frozen_registry_sync_start` | S→C | 开始注册表同步 | `ResourceLocation` |
| `neoforge:frozen_registry` | S→C | 单个注册表内容 | `(registryName, RegistrySnapshot)` |
| `neoforge:frozen_registry_sync_completed` | **双向** | 同步完成 / **客户端应答** | 零字节（`StreamCodec.unit`） |
| `neoforge:known_registry_data_maps` | S→C | 询问 data map | — |
| `neoforge:known_registry_data_maps_reply` | C→S | **应答** | `Map<ResourceKey, Collection<Identifier>>` |
| `neoforge:extensible_enum_data` | S→C | 可扩展枚举清单 | — |
| `neoforge:extensible_enum_ack` | C→S | **应答** | 零字节 |
| `neoforge:feature_flags` | S→C | 特性开关 | — |
| `neoforge:feature_flags_ack` | C→S | **应答** | 零字节 |

### 1.2 通道组件线格式

```
UTF8 id          // 通道全名，如 "modid:channel"
UTF8 version     // 版本字符串，必须与服务端 String.equals 完全相同
BOOL hasFlow
  [VAR_INT flowOrd]   // 0=SERVERBOUND, 1=CLIENTBOUND
BOOL optional
```

### 1.3 ConnectionProtocol 序号

```
0 = HANDSHAKING
1 = PLAY
2 = STATUS
3 = LOGIN
4 = CONFIGURATION
```

---

## 二、四个关键机制

### 2.1 必须逐段应答（不是只回一段）

服务端的查询包由 `ModdedNetworkQueryPayload.fromRegistry()` 生成，
包含它 registry 里的**全部协议段**。

协商是**逐段**进行的（`NetworkRegistry.initializeNeoForgeConnection`）：

```java
for (ConnectionProtocol protocol : PAYLOAD_REGISTRATIONS.keySet()) {
    negotiate(server.get(protocol), clientChannels.getOrDefault(protocol, emptySet()));
    if (!success) { disconnect(); return; }
}
```

**只回一段会让其余段被判为空集** → `missing.server.client` → 断连。

> 实测后果：曾因只回 PLAY 段，导致 390 个通道全部报此错。

### 2.2 通道表可以自动探测

由于 `fromRegistry()` 把服务端**全部通道**放进查询包，
客户端只需**照抄查询内容**即可完成应答，**无需事先知道任何通道名**。

这是本项目最终采用的方式（也让 jar 内不必包含服务器模组清单）。

### 2.3 必须声明 NeoForge 自带通道

服务端在发送自定义负载前会检查（`NetworkRegistry.checkPacket`）：

```java
if (BUILTIN_PAYLOADS.containsKey(id) || "minecraft".equals(id.getNamespace())) return;
if (hasChannel(listener, id)) return;
throw new UnsupportedOperationException("Payload %s may not be sent to the client!");
```

`BUILTIN_PAYLOADS` **只含握手类包**（register / network / split / c:version / c:register）。

因此像 `neoforge:extensible_enum_data` 这类自带通道，
**必须由客户端显式声明**，否则服务端拒绝发送。

实测报错：
```
Unable to connect: java.lang.UnsupportedOperationException:
    Payload neoforge:extensible_enum_data may not be sent to the client!
```

**17 个自带通道及方向**（由反汇编 `javap -c` 从各类的 `<clinit>` 提取）：

| 通道 | flow |
|---|---|
| advanced_add_entity / advanced_container_set_data / advanced_open_screen | CLIENTBOUND |
| auxiliary_light_data / custom_time_packet / config_file | CLIENTBOUND |
| feature_flags / frozen_registry / frozen_registry_sync_start | CLIENTBOUND |
| known_registry_data_maps / registry_data_map_sync / sync_attachments | CLIENTBOUND |
| **extensible_enum_data** | **CLIENTBOUND**（注意：不是 SERVERBOUND） |
| extensible_enum_ack / feature_flags_ack / known_registry_data_maps_reply | SERVERBOUND |
| **frozen_registry_sync_completed** | **不声明**（服务端也未声明；声明了反而失败） |

### 2.4 flow 一致性规则

`NetworkComponentNegotiator.validateComponent`：

```java
if (left.flow().isPresent()) {
    if (right.flow().isEmpty())        → 失败（flow.*.missing）
    else if (left.flow() != right.flow()) → 失败（flow.*.mismatch）
}
if (!left.version().equals(right.version())) → 失败（version.mismatch）
```

**两个方向都会失败**：
- 服务端设了、客户端没设 → 失败
- 客户端设了、服务端没设 → 也失败

所以对"服务端未声明 flow"的通道，客户端也应留空。

---

## 三、配置阶段是一条请求/应答链

配置阶段串行执行多个任务，**每个都需要客户端应答**才继续：

```
1. frozen_registry_sync_start
   → frozen_registry × N
   → frozen_registry_sync_completed    ← 服务端发完后等待
   客户端 → frozen_registry_sync_completed   ★ 必须应答

2. known_registry_data_maps
   客户端 → known_registry_data_maps_reply   ★ 必须应答

3. extensible_enum_data
   客户端 → extensible_enum_ack              ★ 必须应答

4. feature_flags
   客户端 → feature_flags_ack                ★ 必须应答
```

**不应答的后果**：服务端静默等待约 30-50 秒后 `Connection reset`。

> **重要实现细节**：这些包在配置阶段**早期**下发，
> 早于 vanilla 的 `MinecraftRegisterPayload` 使通道可用。
> 此时包会退化为 `DiscardedPayload`，
> Fabric 的 `registerGlobalReceiver` **不会被调用**。
> 因此应答逻辑必须放在 **Netty 管线层**，直接观察 `ClientboundCustomPayloadPacket` 的通道 ID。

---

## 四、注册表同步的容错

当客户端没有服务端模组的注册表时会连环报错。
本项目实现了四层兜底，逐层攻克：

| 层 | 报错 | 修法 |
|---|---|---|
| 1 | `IllegalStateException: Missing registry` | 让 `RegistryAccess.lookupOrThrow` 返回空注册表 |
| 2 | `IllegalStateException: Adding duplicate key` | 让 `MappedRegistry.register` 跳过已存在的键 |
| 3 | `IllegalStateException: Failed to parse value ... from server` | 清空 `RegistryDataLoader` 的错误表 |
| 4 | `Trying to access unbound value ... from registry timeline` | 跳过未绑定的 timeline 图层 |

### 4.1 各层的注入点（均由反汇编确认）

**层 1**：`RegistryAccess.lookupOrThrow(ResourceKey)`
```
0: aload_0
1: aload_1
2: invokeinterface lookup(ResourceKey) -> Optional
7-13: orElseThrow(Supplier)      ← 唯一的抛错点
```

**层 2**：`MappedRegistry.register(ResourceKey, Object, RegistrationInfo)`
```
15-28: byLocation.containsKey(key.identifier())   ← 抛错点
```

**层 3**：`RegistryDataLoader.load(...)`
```
74-75: Map.isEmpty()
80:    ifne 88          ← 空 → 正常返回
83-84: logErrors(map)   ← 非空 → 构造异常
87:    athrow
```

**层 4**：`EnvironmentAttributeSystem.Builder.addTimelineLayer(Holder, LongSupplier)`
```
0-1: Holder.value()   ← 未绑定时抛异常
```
注意**不能**让 `value()` 返回 null（下一步 `Timeline.accessor()` 会 NPE），
必须在方法入口整体取消。

---

## 五、Mixin 实现的九个坑

| # | 报错 | 根因 / 修法 |
|---|---|---|
| 1 | `@Mixin target type mismatch: ... is an interface` | 目标是 interface → mixin 也必须声明为 interface |
| 2 | `InvalidInterfaceMixinException: contains a non-shadow field` | interface mixin 不能有非 static 字段 → 状态移到普通类 |
| 3 | `IllegalClassLoadError: ... is in a defined mixin package ... cannot be referenced directly` | mixin 包内不能放被直接引用的普通类 → 移出该包 |
| 4 | `@At("RETURN")` 注入完全不生效 | 目标方法在抛异常路径上 → 改 `@At("HEAD")` + `cancellable` |
| 5 | `@Mixin target type mismatch: ... is not an interface` | 目标是 class → mixin 必须声明为 class（**与 #1 恰好相反**） |
| 6 | 清空错误表仍抛异常 | 目标方法无条件构造异常 → 改为 `Redirect` 调用方的判定 |
| 7 | `ClassCastException: ... cannot be cast to DiscardedPayload` | **发送方向的 payload 未注册 C2S** → 退化；C2S/S2C 都要注册 |
| 8 | 编码失败 / 数据错乱 | 注册表 key 应用 `ResourceKey.identifier()`，而非 `registry()` |
| 9 | 编译期找不到符号 | 1.21.11 已把 `writeResourceLocation` 改名为 `writeIdentifier` |

> **最重要的一条**：#1 与 #5 说明
> **mixin 目标是 class 还是 interface 必须逐个用 `javap -p` 查证，不能套用经验。**

---

## 六、1.21.11 相对 1.21.1 的注意点

| 项 | 说明 |
|---|---|
| `ResourceLocation` | 在 1.21.11 映射为 `Identifier` |
| `FriendlyByteBuf.writeResourceLocation` | 改名为 `writeIdentifier` / `readIdentifier` |
| `ResourceKey.location()` | 改名为 `identifier()` |
| `minecraft:timeline` 注册表 | **1.21.11 新增**，1.21.1 服务端不会同步 |
| `Holder.Reference` 未绑定 | 访问未绑定值时抛 `IllegalStateException` |

---

## 七、ViaFabricPlus 相关

版本翻译用 VF+，其配置有两处关键项：

```json
{
  "selected-protocol-version": "1.21.1",
  "ignore_packet_translation_errors": "cancel"
}
```

**第一项**：必须写目标服务端的版本。
- VF+ 用 `ProtocolVersion.getClosest(String)` 解析该字符串
- 实测：`getClosest("1.21.1")` → 协议号 **767**（名称 `1.21-1.21.1`）
- 注意 ViaVersion 中**没有 `v1_21_1` 枚举**，1.21 与 1.21.1 共用 767

**第二项**取值：`kick` / `cancel_and_notify` / `cancel` / `off`
- `kick`（默认）= 翻译出错即断连
- `cancel` = 跳过该包、连接继续（**遇到模组包翻译失败时必需**）

---

## 八、最终限制（诚实说明）

即使上述全部打通，仍有**原理性障碍**：

服务端的模组自定义包使用**模组自己的序列化格式**。
Fabric 客户端没有这些模组，只能按 vanilla 的"长度前缀"规则读，
导致字节数不匹配：

```
DecoderException: Not enough bytes in buffer, expected 118, but got 95
  at net.minecraft.network.protocol.common.custom.DiscardedPayload
```

要解决它，等于**把服务端全部模组重新实现一遍** ——
这超出了"握手适配"的范畴。

因此本项目的定位是：
**证明协议层可行** + **作为 NeoForge 协议的逆向参考资料**，
而不是一个可玩的连接方案。
