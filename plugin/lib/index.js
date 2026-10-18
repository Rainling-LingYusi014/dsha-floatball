/**
 * dsh-floatball-bridge —— 把 agent 的流式输出与工具调用实时推给自制状态球。
 *
 * 分工：
 *   dsh-status-overlay（内置）  插件 → /app/overlay → App 原生 OverlayController（顶部长条）
 *   dsh-floatball-bridge（本件）插件 → 127.0.0.1:3091/update → com.dsha.floatball（头像球 + 卡片）
 *
 * 设计原则沿用内置那件的经验：
 *  1) 不写模块级 inject，避免整棵 plugin tree 因为缺服务挂掉；
 *  2) 只发"当前应显示的完整状态"，幂等，丢一次下次自然对上；
 *  3) 400ms 节流，流式 token 不逐字发；
 *  4) 一个异常都不许冒出去，球挂了绝不能影响 agent 干活。
 *
 * 卡片文案原则（用户要求）：只说人话进度，不写工具名、不写命令行。
 * 真正的步骤进度由 agent 侧 `ball plan` / `ball done` 推送，插件不带 steps/cur 参数，
 * 因此不会冲掉 agent 声明的计划。
 *
 * mode（1.10 新增）：这一轮"手机屏幕会不会自己动"。
 *   device = 发生了模拟触屏 / 拉起应用，用户需要避让，球呼吸变亮并展开卡片
 *   chat   = 只是写回复、读屏、改代码、查资料，球保持待机，不打扰用户
 * 判据只认真正会让屏幕动起来的动作；只读接口（dump / device / screenshot / export…）不算。
 * agent 也可以用 `ball mode device|chat` 写 /tmp/ball-mode 强制覆盖嗅探结果。
 *
 * 停止打断（1.11 合并版）：球上的「停止操作」不再只是标记——本插件在回合运行期间
 * 每 500ms 轮询球的 /state，命中 stop=1 就调用 cordis 的 agent.cancel 真正掐掉当前
 * 回合（与用户在 DSHA 界面点停止等效），随后清标记、把终态推回球。
 * 实测：/stop 发出到回合中止 ≤1 秒。
 * 另含：插件上线先推一次 idle（Web 在回合中途重启时球会冻在旧状态），热重载摘旧监听。
 */
import { readFileSync, unlinkSync } from 'node:fs'

export const name = 'dsh-floatball-bridge'

const API = 'http://127.0.0.1:3091'
const MODE_FILE = '/tmp/ball-mode'
const FLUSH_MS = 400
const TIMEOUT_MS = 1200
const STOP_POLL_MS = 500
const MAX_STEP = 80
const MAX_RECENT = 40

const TOOL_LABELS = [
  [/bash|shell|command|exec|terminal/i, '执行命令'],
  [/write|create.*file|edit|patch|apply/i, '修改文件'],
  [/read|cat|view|open.*file/i, '查看文件'],
  [/glob|grep|search|find/i, '查找内容'],
  [/fetch|web|http|browse|url/i, '联网查询'],
  [/todo|plan/i, '整理任务'],
  [/task|agent|subagent|dispatch/i, '拆分任务'],
  [/image|screenshot|vision/i, '查看屏幕'],
  [/tap|click|swipe|key|ui\//i, '操作界面'],
  [/notify|toast|share|clip|vibrate/i, '调用手机功能'],
]

const ARG_KEYS = ['text', 'query', 'description', 'name', 'pattern',
  'path', 'file_path', 'filePath', 'url', 'prompt']

/** /app 下会让屏幕真的动起来的接口；其余（dump/screenshot/device/apps/export…）只读。 */
const DEVICE_DOING = /\/app\/(ui\/(tap|swipe|input|key)|launch|open|share)\b/

/** 用户电脑上的 zcode-bridge（adb reverse 到 8787）里会动屏幕的端点。 */
const BRIDGE_DOING = /(8787\/(cmd|open|tap|swipe|key|text|unlock)\b|\bbridge\s+(cmd|open|tap|swipe|key|text|unlock)\b)/

function oneLine(s, max) {
  const t = String(s || '').replace(/\s+/g, ' ').trim()
  return t.length > max ? t.slice(0, max - 1) + '…' : t
}

function toolLabel(n) {
  const name = String(n || '').trim()
  if (!name) return '正在处理'
  for (const [re, label] of TOOL_LABELS) if (re.test(name)) return label
  return '正在操作'
}

function toolDetail(argsJson) {
  try {
    if (!argsJson) return ''
    const o = typeof argsJson === 'string' ? JSON.parse(argsJson) : argsJson
    if (!o || typeof o !== 'object') return ''
    for (const k of ARG_KEYS) {
      const v = o[k]
      if (typeof v === 'string' && v.trim()) return oneLine(v, 40)
    }
    return ''
  } catch {
    return ''
  }
}

/** 从 bash 调用里取出真正执行的命令串。 */
function shellCommand(argsJson) {
  try {
    const o = typeof argsJson === 'string' ? JSON.parse(argsJson) : argsJson
    return String(o?.command || '')
  } catch {
    return String(argsJson || '')
  }
}

/** 接管屏幕时卡片上要说的人话，比笼统的"执行命令"具体。 */
const DEVICE_VERBS = [
  [/\btap\b|\/app\/ui\/tap/, '点了一下屏幕'],
  [/\bswipe\b|\/app\/ui\/swipe/, '滑动屏幕'],
  [/\binput\s+text\b|\/app\/ui\/input/, '输入了一段文字'],
  [/\bkeyevent\b|\/app\/ui\/key/, '按了一下按键'],
  [/\bam\s+start\b|\/app\/(launch|open)/, '拉开了应用'],
  [/\/app\/share/, '分享内容'],
]

export function deviceVerb(cmd) {
  for (const [re, v] of DEVICE_VERBS) if (re.test(cmd)) return v
  return '在操作屏幕'
}

/** 这次工具调用有没有真的接管屏幕。导出只为方便本地测试，插件本身不使用。 */
export function isDeviceAction(name, argsJson) {
  const n = String(name || '').toLowerCase()
  // 直接调用设备接口的工具（MCP / adb 封装）
  if (/(android|adb|dsha-android)/.test(n)) return true
  // 其余只看 shell 里到底执行了什么
  if (!/(bash|shell|command|exec|terminal)/.test(n)) return false
  const cmd = shellCommand(argsJson)
  if (!cmd) return false
  // 先摘掉给球自己发指令的片段（ball guard / ball step …），那不算动手机
  const noBall = cmd.replace(/\bball\s+[^;&|\n]*/g, ' ')
  // 按命令段判断：只有 curl / adb-shell 自己那一段算数，
  // 避免 "grep -rn app/ui/tap src" 这种只是提到接口名就被误判
  for (const seg of noBall.split(/[;&|\n]+/)) {
    if (BRIDGE_DOING.test(seg)) return true
    if (!/curl|adb-shell/i.test(seg)) continue
    if (DEVICE_DOING.test(seg)) return true
    // 底层注入：adb-shell input tap / am start
    if (/adb-shell/i.test(seg) && /(\binput\b|\bam\s+start\b)/.test(seg)) return true
  }
  return false
}

// ---- 人工覆盖：agent 用 `ball mode device|chat` 写这个文件 ----
function manualMode() {
  try {
    const v = readFileSync(MODE_FILE, 'utf8').trim()
    return v === 'device' || v === 'chat' ? v : ''
  } catch {
    return ''
  }
}

function clearManual() {
  try { unlinkSync(MODE_FILE) } catch { /* 本来就没有 */ }
}

// ---- 全局单球状态 ----
let state = 'idle'
let mode = 'chat'
let task = ''
let step = ''
let textBuf = ''
const recent = []
let timer = null

async function flush() {
  timer = null
  const m = manualMode() || mode
  const url = `${API}/update`
    + `?state=${encodeURIComponent(state)}`
    + `&mode=${encodeURIComponent(m)}`
    + `&task=${encodeURIComponent(task)}`
    + `&step=${encodeURIComponent(step)}`
    + `&recent=${encodeURIComponent(recent.slice(-3).join('|'))}`
  try {
    await fetch(url, { signal: AbortSignal.timeout(TIMEOUT_MS) })
  } catch {
    // 球没启动 / 被卸载 / 超时：静默降级，绝不打断 agent
  }
}

function schedule(immediate) {
  if (immediate) {
    if (timer) { clearTimeout(timer); timer = null }
    void flush()
    return
  }
  if (timer) return
  timer = setTimeout(() => { void flush() }, FLUSH_MS)
  if (typeof timer?.unref === 'function') timer.unref()
}

/** @param {import('@deepseek-ai/cordis').Context} ctx */
let detach = null

export function apply(ctx) {
  // 热重载时先摘掉上一次的监听，避免叠加后重复推送
  if (detach) { try { detach() } catch { } detach = null }
  const d = ctx.on('session/event', onEvent)
  if (typeof d === 'function') detach = d

  // 上线先把球同步回 idle：Web 在回合中途重启时，没有任何人推 turn/end，
  // 球会冻在上一轮的旧状态（实测冻在 running）。插件初始值就是 idle，直接推。
  schedule(true)

  // ---- 停止按钮 → 真正中断当前 turn ----
  // 球上的「停止」只写一个 stop=1 标记；这里在 turn 运行期间每 500ms 查一次，
  // 命中就调 agent.cancel 把这一轮真的掐掉，然后清标记、通知球。
  let stopTimer = null
  let stopFiring = false
  let watchedSessionId = ''

  const clearStopTimer = () => {
    if (stopTimer) {
      clearInterval(stopTimer)
      stopTimer = null
    }
  }

  const stopUrl = (path, ms) => fetch(API + path, { signal: AbortSignal.timeout(ms) })

  /** 只取消一次：agent 真在 running、且这一轮还没被取消过。 */
  const fireStop = async () => {
    const sessionId = watchedSessionId
    // 1) 取消当前 turn，参数与用户点界面上的停止完全一致
    try {
      ctx.inject(['agents'], (c) => {
        try {
          const agent = c.agents?.get?.(sessionId)
          if (agent?.status === 'running') {
            agent.cancel({ kind: 'user' }, { keepInbox: true })
          }
        } catch {
          // 插件绝不能因为取不到 agent 而打断主流程
        }
      })
    } catch {
      // ctx.inject 本身抛错也吞掉
    }
    // 2) 清标记，避免下一轮又被同一个 stop=1 触发
    try { await stopUrl('/clearstop', 600) } catch { /* ignore */ }
    // 3) 把最终状态推给球
    try {
      const q = '?state=idle&mode=device&task=' + encodeURIComponent('已按你的要求停手')
      await fetch(API + '/update' + q, { signal: AbortSignal.timeout(600) })
    } catch { /* ignore */ }
  }

  const checkStop = async () => {
    if (stopFiring) return
    let text = ''
    try {
      const r = await stopUrl('/state', 400)
      text = await r.text()
    } catch {
      return
    }
    if (!text.includes('stop=1')) return
    stopFiring = true
    try {
      await fireStop()
    } catch {
      // 吞掉，球不能影响 agent
    } finally {
      stopFiring = false
    }
  }

  /** turn 开始才轮询；先把上一轮可能残留的标记清掉，免得一上来就误杀。 */
  const armStopWatch = (sessionId) => {
    watchedSessionId = sessionId || ''
    clearStopTimer()
    void (async () => {
      try {
        const r = await stopUrl('/state', 400)
        const text = await r.text()
        if (text.includes('stop=1')) await stopUrl('/clearstop', 400)
      } catch { /* ignore */ }
      // 异步清理期间这一轮可能已经结束，别再挂定时器
      if (stopTimer || !watchedSessionId) return
      stopTimer = setInterval(() => { void checkStop() }, STOP_POLL_MS)
      if (typeof stopTimer?.unref === 'function') stopTimer.unref()
    })()
  }

  const disarmStopWatch = () => {
    clearStopTimer()
    watchedSessionId = ''
  }

  const onEvent = (session, event) => {
    try {
      const type = event?.type
      const data = event?.data

      if (type === 'turn/start') {
        // 新的一轮：先按"只是说话"算，碰到真动作再升级
        armStopWatch(session?.header?.id)
        clearManual()
        mode = 'chat'
        state = 'running'
        task = ''
        step = '正在理解你的要求'
        textBuf = ''
        recent.length = 0
        schedule(true)
        return
      }

      if (type === 'assistant/chunk') {
        const chunk = data?.chunk
        if (chunk?.type !== 'text-delta') return
        const piece = chunk.text
        if (!piece) return
        state = 'running'
        textBuf = (textBuf + piece).slice(-400)
        step = '💬 ' + oneLine(textBuf, MAX_STEP)
        schedule(false)
        return
      }

      if (type === 'tool/call') {
        const rawName = String(data?.name || '').toLowerCase()
        const isShell = /bash|shell|command|exec|terminal/.test(rawName)
        const dev = isDeviceAction(data?.name, data?.arguments)
        if (dev) mode = 'device'
        // 接管屏幕时别只说"执行命令"，说清楚是点、滑、输入还是拉起应用
        const label = (dev && isShell) ? deviceVerb(shellCommand(data?.arguments)) : toolLabel(data?.name)
        const detail = isShell ? '' : toolDetail(data?.arguments)
        state = 'running'
        step = detail ? label + '：' + detail : label
        recent.push(oneLine(step, MAX_RECENT))
        if (recent.length > 3) recent.shift()
        schedule(true)
        return
      }

      if (type === 'turn/end') {
        // 完成文案由球按 mode 决定：device → 操作完成，chat → 回复已生成
        disarmStopWatch()
        mode = manualMode() || mode
        clearManual()
        state = 'idle'
        task = ''
        step = ''
        textBuf = ''
        schedule(true)
      }
    } catch {
      // 事件回调抛异常会影响 agent 主链路，一律吞掉
    }
  }

  ctx.on('session/event', onEvent)
}
