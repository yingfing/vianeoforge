# ViaNeoForge

> 用 **Fabric 客户端**连接 **NeoForge 服务端**的握手适配 mod（协议层研究项目）。

[![Build](https://github.com/OWNER/vianeoforge/actions/workflows/build.yml/badge.svg)](https://github.com/OWNER/REPO/actions/workflows/build.yml)

---

## 一、这个项目做到了什么

在 **MC 1.21.11 Fabric 客户端**上，成功通过与 **NeoForge 21.1.248（MC 1.21.1）服务端**的完整握手流程：

| 阶段 | 状态 |
|---|---|
| MC 版本翻译（ViaFabricPlus 1.21.11 ↔ 1.21.1） | ✅ |
| NeoForge 通道协商（407 个通道） | ✅ 完全通过 |
| 注册表同步（含四层容错兜底） | ✅ |
| 配置阶段四个请求/应答 | ✅ |
| **进入 NeoForge 子服世界** | ✅ **成功** |

实测日志证据：

```
[MILESTONE] M1a 配置阶段回 neoforge:register(2 段: 4:407 1:407)
[MILESTONE] M6b 收到 frozen_registry_sync_completed（共 46 个分片）
[MILESTONE] M5  已回 frozen_registry_sync_completed 应答
[MILESTONE] M7  已回 known_registry_data_maps_reply
[MILESTONE] M8  已回 extensible_enum_ack
[MILESTONE] M9  已回 feature_flags_ack
[MILESTONE] M2  配置阶段完成 —— NeoForge 配置任务全部通过
[MILESTONE] M3  进入游戏世界(play JOIN)
```

---

## 二、⚠️ 但请注意：这不是一个可玩方案

**它能进世界，但不能正常游玩。** 原因是原理性的：

服务端有 **390 个模组**，它们定义了各自的：

- 方块 / 物品 / 实体 / 配方（注册表内容）
- **自定义网络包格式**
- **玩家数据扩展结构**

Fabric 客户端**物理上不包含这些模组的代码**，因此：

| 现象 | 原因 |
|---|---|
| 掉线 `Not enough bytes in buffer` | 服务端发来模组自定义包，客户端按 vanilla 规则读，字节数对不上 |
| 掉线 `Invalid player data` | 服务端读玩家存档失败（含模组数据） |
| 方块/物品显示异常 | 缺少模组资源（贴图/模型） |

要让这个方案真正可玩，等于**把 390 个模组重新实现一遍** —— 那是另一个量级的工程。

> **如果你的目标是正常游玩该服务器，请直接使用 NeoForge 客户端。**
> 本项目的价值在于**证明协议层可行**，以及作为 NeoForge 协议的逆向参考实现。

---

## 三、构建

### 前置要求

| 项 | 版本 | 说明 |
|---|---|---|
| JDK | **25** | Loom 1.18.2 强制要求 JVM ≥ 25 |
| Gradle | 9.7.1 | |

### 本地构建

```bash
# 指定 JDK 25（不要写死在 gradle.properties 里，否则 CI 会失败）
export JAVA_HOME=/path/to/jdk25          # Linux / macOS
set JAVA_HOME=F:\java\java25            # Windows

gradle build -x test
# 产物：build/libs/nfhandshake-1.0.0.jar

# 另一个工程（游戏内调试命令桥）
cd mcpmod && gradle build -x test
```

### 自动构建

推送到 GitHub 后，`.github/workflows/build.yml` 会自动构建两个 mod，
产物可在该次运行的 **Actions 页面底部**下载（Artifacts）。

---

## 四、配置

配置**不含在 jar 内**，由外部文件提供（隐私考虑）。

### 位置

```
<游戏目录>/config/nfhandshake.properties    ← 推荐
<游戏目录>/nfhandshake.properties           ← 兼容旧位置
```

首次运行会自动生成带中文注释的模板。

### 主要配置项

```properties
# 服务器地址（留空则不自动连接）
serverAddress=

# 启动后自动连接
autoconnect=false

# 进服后自动执行的命令（不含前导斜杠）
subServerCommand=

# 自动登录
autologin=false
password=

# 握手行为（一般不用改）
configProto=4      # 配置阶段协议段：4=CONFIGURATION（正确值）
playProto=1        # PLAY 阶段协议段
channelLimit=-1    # 对照实验用：只声明前 N 个通道
```

完整说明见 [`nfhandshake.properties.example`](nfhandshake.properties.example)。

---

## 五、通道表：自动探测

**通常无需手工维护通道表。**

NeoForge 服务端在握手时会主动下发它拥有的**完整通道清单**
（`ModdedNetworkQueryPayload.fromRegistry()`），
mod 收到后**照抄回去**即可完成协商。

因此 jar 内**不包含**任何服务器通道表。

如需离线预置或做对照实验，可提供
`<游戏目录>/config/nfhandshake-channels.json`，
格式见 [`nfhandshake-channels.example.json`](nfhandshake-channels.example.json)。

---

## 六、隐私声明

本仓库**不含**任何真实服务器的敏感信息：

- ❌ 服务器地址、端口
- ❌ 账号、密码
- ❌ 服务器模组清单（通道表）

这些内容一律通过**外部配置文件**提供，且已在
[`.gitignore`](.gitignore) 中排除。

---

## 七、技术要点

### 核心机制

| 机制 | 说明 |
|---|---|
| **自动探测** | 照抄服务端下发的通道清单，无需预先知道通道名 |
| **多协议段应答** | 服务端按协议段分别协商，必须逐段回复（只回一段会失败） |
| **NeoForge 自带通道** | 17 个 `neoforge:*` 通道必须声明，否则服务端拒绝发送 |
| **四层注册表兜底** | 缺失→空注册表、重复→跳过、解析失败→跳过、未绑定时间线→跳过 |
| **请求/应答补齐** | 配置阶段四个应答缺一不可，否则服务端静默等待后断连 |

### 踩过的坑（Mixin 相关）

| # | 报错 | 修法 |
|---|---|---|
| 1 | `target type mismatch: ... is an interface` | 目标是 interface → mixin 用 interface |
| 2 | `contains a non-shadow field` | interface mixin 不能有非 static 字段 |
| 3 | `... in a defined mixin package ... cannot be referenced directly` | 工具类移出 mixin 包 |
| 4 | `@At("RETURN")` 不生效 | 目标方法抛异常 → 改 `HEAD` |
| 5 | `target type mismatch: ... is not an interface` | 目标是 class → mixin 用 class（与 #1 相反！） |
| 6 | 清空 map 无效 | 方法无条件构造异常 → 改 Redirect 调用方 |
| 7 | `ClassCastException: ... cannot be cast to DiscardedPayload` | **C2S 未注册** → 发送时退化 |
| 8 | 编码失败 | 用 `key.identifier()` 而非 `key.registry()` |
| 9 | 找不到符号 | `writeResourceLocation` → `writeIdentifier` |

> **教训**：mixin 目标是 class 还是 interface **必须逐个查**（#1 与 #5 恰好相反）。

---

## 八、目录结构

```
src/main/java/net/fabneo/nfhandshake/
├── NfHandshakeClient.java                    # 主入口
├── DebugConfig.java                          # 外部配置（无隐私默认值）
├── ConfigTemplates.java                      # 配置模板
├── NeoForgeChannelTable.java                 # 通道表（自动探测 + 可选外部文件）
├── NeoForgeRegisterPayload.java              # neoforge:register 线格式
├── NeoForgeNetworkPayload.java               # neoforge:network 线格式
├── NeoForgeSetupFailedPayload.java           # ★ 协商失败诊断器
├── NeoForgeFrozenRegistryCompletedPayload.java
├── NeoForgeKnownDataMapsReplyPayload.java
├── NeoForgeExtensibleEnumAckPayload.java
├── NeoForgeFeatureFlagsAckPayload.java
├── FrozenRegistrySyncResponder.java          # Netty 层应答器
├── MissingRegistryTolerance.java             # 四层兜底状态
├── NettyModdedPayloadFilter.java             # 入站诊断
├── RawByteSniffer.java                       # 原始字节抓取
└── mixin/
    ├── RegistryAccessMixin.java              # 层1：缺失注册表
    ├── MappedRegistryMixin.java              # 层2：重复键
    ├── RegistryDataLoaderMixin.java          # 层3：元素解析失败
    └── TimelineRegistryFallbackMixin.java    # 层4：未绑定时间线

mcpmod/                                        # 游戏内实时命令桥（调试用）
```

---

## 九、许可

见 [LICENSE](LICENSE)。

---

## 十、致谢

本项目在逆向 NeoForge 协议时参考了 NeoForge 21.1.248 的公开源码与产物。
NeoForge 版权归 NeoForged 项目所有。
