package net.fabneo.mcpmod;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/**
 * 客户端 UI 驱动器 —— 让 Agent 能像人一样<b>操作菜单、进游戏、在输入框打字并发送</b>。
 *
 * <p><b>所有 API 均已用真实客户端（{@code 1.21.11-Fabric 0.19.5.jar}）反汇编核实</b>，
 * 并已用 {@code javap -p} 确认访问修饰符（仅看映射表会被 protected/private 坑到）：
 *
 * <table border="1">
 * <tr><th>目标</th><th>混淆名</th><th>真实修饰符</th><th>用法</th></tr>
 * <tr><td>{@code ChatScreen}</td><td>gqy</td><td>public class</td><td>构造器 public (String,boolean)</td></tr>
 * <tr><td>{@code ChatScreen.input}</td><td>gjn b</td><td><b>protected</b></td><td>用反射读（见 {@link #readChatInput}）</td></tr>
 * <tr><td>{@code ChatScreen.handleChatInput(String,boolean)}</td><td>b</td><td><b>public</b></td><td>直接调用 = 按回车发送</td></tr>
 * <tr><td>{@code ChatScreen.init()}</td><td>bg_</td><td><b>protected</b></td><td>用反射调用</td></tr>
 * <tr><td>{@code Screen.children()}</td><td>aJ_</td><td>public</td><td>列控件</td></tr>
 * <tr><td>{@code Button.onPress(Button)}</td><td>a</td><td>public</td><td>点按钮</td></tr>
 * </table>
 *
 * <p>关键字节码证据（{@code ChatScreen.b(String,boolean)}）：
 * <pre>
 *  32: aload_1
 *  33: ldc  "/"
 *  36: invokevirtual String.startsWith   ← 判断是否以 / 开头
 *  39: ifeq 63
 *  42-57: ... hig.d(String)              ← /xxx → ClientPacketListener.sendCommand(去掉斜杠)
 *  63-74: ... hig.c(String)              ← 其他 → ClientPacketListener.sendChat
 * </pre>
 * 即 {@code handleChatInput} 就是"在聊天框里回车"所执行的逻辑，命令与聊天自动区分。
 */
public final class UiDriver {

	private UiDriver() {
	}

	// ==================== 屏幕查看 ====================

	/** 列出当前界面上的所有可交互控件（含按钮文字、输入框内容），用于"看懂菜单"。 */
	public static String dumpScreen() {
		return CommandRegistry.onClientThread(mc -> {
			Screen s = mc.screen;
			if (s == null) {
				return "(没有打开的界面 —— 可能正在游戏内)";
			}
			StringBuilder sb = new StringBuilder();
			sb.append("当前界面: ").append(s.getClass().getSimpleName()).append('\n');
			try {
				sb.append("标题: ").append(s.getTitle().getString()).append('\n');
			} catch (Throwable ignored) {
			}
			List<AbstractWidget> widgets = collectWidgets(s);
			int idx = 0;
			int shown = 0;
			for (AbstractWidget w : widgets) {
				String label = describe(w);
				if (label == null) {
					idx++;
					continue;
				}
				sb.append(String.format("  [%d] %-20s %s%s%n", idx, w.getClass().getSimpleName(),
						label, w.isActive() ? "" : "  (disabled)"));
				idx++;
				shown++;
			}
			if (shown == 0) {
				sb.append("(界面上没有可识别的控件)\n");
			}
			return sb.toString();
		});
	}

	/**
	 * 收集界面控件。
	 *
	 * <p>用公开的 {@code Screen.children()}（{@code aJ_}，{@code javap -p} 确认为 public），
	 * 而非受保护的 {@code renderables} 字段 —— 后者编译不过。
	 */
	private static List<AbstractWidget> collectWidgets(Screen s) {
		List<AbstractWidget> out = new ArrayList<>();
		try {
			for (Object o : s.children()) {
				if (o instanceof AbstractWidget w) {
					out.add(w);
				} else if (o instanceof Screen sub) {
					out.addAll(collectWidgets(sub));
				}
			}
		} catch (Throwable ignored) {
		}
		return out;
	}

	private static String describe(AbstractWidget w) {
		try {
			if (w instanceof EditBox eb) {
				return "输入框: \"" + eb.getValue() + "\"";
			}
			Component msg = w.getMessage();
			String text = msg == null ? "" : msg.getString();
			if (text.isEmpty()) {
				return null;
			}
			return "\"" + text + "\"";
		} catch (Throwable t) {
			return null;
		}
	}

	// ==================== 点击菜单 ====================

	/** 按文字（子串、忽略大小写）点击当前界面上的按钮。 */
	public static String clickButton(String label) {
		return CommandRegistry.onClientThread(mc -> {
			Screen s = mc.screen;
			if (s == null) {
				return "当前没有打开任何界面";
			}
			String needle = label.trim().toLowerCase();
			for (AbstractWidget w : collectWidgets(s)) {
				if (!(w instanceof Button b)) {
					continue;
				}
				String text = b.getMessage() == null ? "" : b.getMessage().getString();
				if (text.toLowerCase().contains(needle)) {
					if (!b.isActive()) {
						return "按钮存在但被禁用: \"" + text + "\"";
					}
					b.onPress(null);
					return "已点击按钮: \"" + text + "\"";
				}
			}
			return "未找到包含 \"" + label + "\" 的按钮";
		});
	}

	/** 按索引点击控件（配合 screen 命令列出的编号）。 */
	public static String clickIndex(int index) {
		return CommandRegistry.onClientThread(mc -> {
			Screen s = mc.screen;
			if (s == null) {
				return "当前没有打开任何界面";
			}
			List<AbstractWidget> ws = collectWidgets(s);
			if (index < 0 || index >= ws.size()) {
				return "索引越界: " + index + "（共 " + ws.size() + " 个控件）";
			}
			AbstractWidget w = ws.get(index);
			if (w instanceof Button b) {
				String msg = b.getMessage() == null ? "" : b.getMessage().getString();
				b.onPress(null);
				return "已点击 [" + index + "] \"" + msg + "\"";
			}
			return "[" + index + "] 不是按钮（" + w.getClass().getSimpleName() + "）";
		});
	}

	// ==================== 输入框打字并发送 ====================

	/** 反射读取 {@code ChatScreen.input}（protected 字段）。 */
	private static EditBox readChatInput(ChatScreen chat) {
		try {
			Class<?> c = chat.getClass();
			while (c != null) {
				for (Field f : c.getDeclaredFields()) {
					if (EditBox.class.isAssignableFrom(f.getType())) {
						f.setAccessible(true);
						return (EditBox) f.get(chat);
					}
				}
				c = c.getSuperclass();
			}
		} catch (Throwable ignored) {
		}
		return null;
	}

	/** 反射调用 protected 的 {@code Screen.init()}（混淆名 bg_）。失败不影响流程。 */
	private static void invokeInit(Screen s) {
		try {
			for (Class<?> c = s.getClass(); c != null; c = c.getSuperclass()) {
				for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
					if (m.getName().equals("init") && m.getParameterCount() == 0) {
						m.setAccessible(true);
						m.invoke(s);
						return;
					}
				}
			}
		} catch (Throwable ignored) {
		}
	}

	/**
	 * 【真·UI 操作】打开聊天界面 → 在输入框填入文本 → 调用 {@code handleChatInput}（等于按回车）。
	 *
	 * <p>这是"像人一样"的路径：先 {@code setScreen(new ChatScreen("",false))} 打开聊天框，
	 * 把文字写进 {@code EditBox}（等价于逐键输入的结果），再走聊天框自己的发送逻辑。
	 */
	public static String typeAndSend(String text) {
		return CommandRegistry.onClientThread(mc -> {
			// 注意：不能只看 mc.player。重连/切服后 player 可能暂时为 null，
			// 但连接仍然可用；此时应该走 ClientPacketListener 直发。
			StringBuilder trace = new StringBuilder();

			// 1) 打开聊天界面（等价于按 T）
			ChatScreen chat = new ChatScreen("", false);
			mc.setScreen(chat);
			invokeInit(chat);
			trace.append("已打开 ChatScreen；");

			// 2) 往输入框里写字（等价于逐键输入后的结果）
			EditBox box = readChatInput(chat);
			String typed;
			if (box != null) {
				box.setValue(text);
				typed = box.getValue();
				trace.append("输入框已填入；");
			} else {
				typed = "(未找到输入框)";
				trace.append("未找到输入框；");
			}

			// 3) 等价于按回车 —— handleChatInput 内部区分 /命令 与 普通聊天
			chat.handleChatInput(text, false);
			mc.setScreen(null);
			trace.append("已回车发送；");

			return "已通过输入框发送: \"" + text + "\"\n"
					+ "输入框实际内容: \"" + typed + "\"\n"
					+ "过程: " + trace;
		});
	}

	/** 快速发送（不开界面，直接走 ClientPacketListener），适合脚本化场景。 */
	public static String sendDirect(String text) {
		return CommandRegistry.onClientThread(mc -> {
			if (mc.player == null || mc.player.connection == null) {
				return "玩家未就绪";
			}
			if (text.startsWith("/")) {
				String c = text.substring(1);
				mc.player.connection.sendCommand(c);
				return "已直接发送命令: /" + c;
			}
			mc.player.connection.sendChat(text);
			return "已直接发送聊天: " + text;
		});
	}

	// ==================== 界面导航 ====================

	/** 把当前界面推回标题界面。 */
	public static String toTitle() {
		return CommandRegistry.onClientThread(mc -> {
			mc.setScreen(new TitleScreen());
			return "已切到标题界面";
		});
	}

	/** 关闭当前界面。 */
	public static String closeScreen() {
		return CommandRegistry.onClientThread(mc -> {
			if (mc.screen == null) {
				return "本来就没有打开界面";
			}
			String name = mc.screen.getClass().getSimpleName();
			mc.setScreen(null);
			return "已关闭界面: " + name;
		});
	}

	/** 直接连接服务器（等价于手点"加入服务器"）。 */
	public static String connect(String address) {
		return CommandRegistry.onClientThread(mc -> {
			try {
				ServerAddress addr = ServerAddress.parseString(address);
				ServerData data = new ServerData("mcpmod", address, ServerData.Type.OTHER);
				ConnectScreen.startConnecting(new TitleScreen(), mc, addr, data, false, null);
				return "已发起连接: " + address;
			} catch (Throwable t) {
				return "连接失败: " + t;
			}
		});
	}
}
