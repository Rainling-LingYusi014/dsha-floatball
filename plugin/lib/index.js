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
 */
export const name = 'dsh-floatball-bridge'

const API = 'http://127.0.0.1:3091'
const FLUSH_MS = 400
const TIMEOUT_MS = 1200
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

// ---- 全局单球状态 ----
let state = 'idle'
let task = ''
let step = ''
let textBuf = ''
const recent = []
let timer = null

async function flush() {
  timer = null
  const url = `${API}/update`
    + `?state=${encodeURIComponent(state)}`
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
export function apply(ctx) {
  const onEvent = (_session, event) => {
    try {
      const type = event?.type
      const data = event?.data

      if (type === 'turn/start') {
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
        if (!task) task = '正在整理答复'
        textBuf = (textBuf + piece).slice(-400)
        step = '💬 ' + oneLine(textBuf, MAX_STEP)
        schedule(false)
        return
      }

      if (type === 'tool/call') {
        const rawName = String(data?.name || '').toLowerCase()
        const isShell = /bash|shell|command|exec|terminal/.test(rawName)
        const label = toolLabel(data?.name)
        const detail = isShell ? '' : toolDetail(data?.arguments)
        state = 'running'
        if (!task) task = '正在操作你的手机'
        step = detail ? label + '：' + detail : label
        recent.push(oneLine(step, MAX_RECENT))
        if (recent.length > 3) recent.shift()
        schedule(true)
        return
      }

      if (type === 'turn/end') {
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
