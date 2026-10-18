# 状态球本机接口

App 在前台服务里跑一个极简 HTTP 服务，只监听 `127.0.0.1:3091`。
无鉴权、不联网、不落盘——目的是让本机的 agent 进程推状态，所以只绑回环地址。

## `GET /update`

推送当前应显示的完整状态（幂等，丢一次下一次自然对上）。

| 参数 | 说明 |
|---|---|
| `state` | `running` / `idle` / `error` |
| `task` | 卡片标题下的大字，一句话说清在干什么 |
| `step` | 当前这一步的细节；有 `steps` 时覆盖当前步的文案 |
| `recent` | 最近动作，`a\|b\|c`，卡片最多显示 3 条 |
| `steps` | 本次任务的步骤列表，`一\|二\|三`；**不传则保留上次的值** |
| `cur` | 当前进行到第几步（1 起）。`steps` 为空时忽略 |
| `op` | `1` 表示这一轮 agent 真的在操作手机（有工具调用）。用于收尾时判断要不要自动展开卡片 |
| `mode` | `chat` / `device`：这一轮是否会接管手机屏幕（插件嗅探，`ball mode` 可覆盖）。device 收尾球展开卡片，chat 不打扰 |

`steps` / `cur` 是「有则覆盖、无则保留」，这样自动推送插件（只推 `state`/`task`/`step`）
不会冲掉 agent 用 `plan` 声明的计划。

示例：

```bash
curl -G "http://127.0.0.1:3091/update" \
  --data-urlencode "state=running" \
  --data-urlencode "task=正在操作 QQ" \
  --data-urlencode "step=正在找到要点的会话" \
  --data-urlencode "recent=读了一遍屏幕|找到会话列表" \
  --data-urlencode "steps=看屏幕|找会话|点进去|输入内容|发送" \
  --data-urlencode "cur=2"
```

## `GET /state`

查询当前状态，`;` 分隔的 `k=v`：

```
state=running;stop=0;task=正在操作 QQ;step=正在找到要点的会话;progress=2/5
```

`stop=1` 表示用户按过「停止操作」。agent 在每次改动手机的动作前应当检查这一项。
注意 `task` / `step` 的值做了百分号转义（`;` `=` `%` → `%3B` `%3D` `%25`），
解析时按 `;` 切分、按 `=` 取值即可，内容不会破坏结构；需要原文时做一次 URL 反解码。

## `GET /stop` / `GET /clearstop`

手工设置 / 清除停止标记。卡片上的「停止操作」按钮切换的就是它——
注意停止是**可逆**的，用户再按一次会清掉标记。

## `GET /shutdown`

让服务自己 `stopSelf()` 并移除悬浮窗，返回 `bye`。

App 里「停止状态球」按钮走的就是这条路：先调 `/shutdown`，再 `stopService` 兜底。
直接调外部 `stopService` 在某些 ROM（如 MIUI）上会被吞掉，服务停不下来——
让服务自己停才是可靠的。

## 行为约定

- `state` 从 `running` 变为非 `running` 时，App 认为一次任务结束：球短暂切到「点按」档透明度。
  **自动展开「已完成 ✓」卡片只在真实任务收尾时发生**——即本次带过 `steps` 计划，
  或最后一次推送 `op=1`（agent 动过手）。纯文字问答（插件推送、无计划、没调用工具）不弹卡片。
- 处于 `idle` 时卡片里不显示停止按钮，「返回 DSHA」占满整行。
- 卡片点击「返回 DSHA」会拉起 `com.dsh.client` 的主界面。

## 安全边界

- 只绑 `127.0.0.1`，不监听外部接口
- 不读取任何设备数据，只负责显示推过来的字符串
- 不执行任何命令
