#!/bin/bash
# ball — 控制 DSHA 状态球（悬浮球）
# 用法：
#   ball ensure                  确保球在跑：没响应就自动唤醒（失败退出码 2）
#   ball plan "读屏|找会话|点开|发送"  声明本次任务的步骤，卡片右上角显示 1/N
#   ball start "<任务名>"        标记开始操作（清空步骤与最近动作）
#   ball step  "<当前在做什么>"   只改当前这一步的文字（不推进）
#   ball done  ["刚完成的事"]     推进到下一步，并记入最近动作
#   ball ok    "<刚完成的动作>"   只记入最近动作，不推进
#   ball idle                    回到空闲（清空步骤与停止标记）
#   ball error "<原因>"           标记异常（卡片标题变红）
#   ball guard                   确保球在跑 + 检查是否被要求停止；被要求则退出码 1
#   ball state                   打印当前状态
#
# 约定：每执行一次会改动手机的动作之前，先跑一次 `ball guard`，
#       它会在球被杀掉时自动拉起，退出码非 0 就立刻停手并告诉用户。

API="http://127.0.0.1:3091"
BRIDGE="http://127.0.0.1:3090"
HEADERS="/root/.dsh/.bridge_headers"
RECENT="/tmp/ball-recent.txt"
CUR="/tmp/ball-cur.txt"
PLAN="/tmp/ball-plan.txt"
mkdir -p /tmp 2>/dev/null

_ping() {
  curl -s -m 2 "$API/state" >/dev/null 2>&1
}

_plan_steps() {
  [ -f "$PLAN" ] && sed -n '1p' "$PLAN"
}

_plan_cur() {
  if [ -f "$PLAN" ]; then sed -n '2p' "$PLAN"; else echo 0; fi
}

_push() {
  local steps cur
  steps=$(_plan_steps)
  cur=$(_plan_cur)
  curl -s -m 3 -G "$API/update" \
    --data-urlencode "state=$1" \
    --data-urlencode "task=$2" \
    --data-urlencode "step=$3" \
    --data-urlencode "recent=$4" \
    --data-urlencode "steps=$steps" \
    --data-urlencode "cur=${cur:-0}" \
    --data-urlencode "op=1" >/dev/null 2>&1
}

# 唤醒：球没响应就拉起 App（App 里 onResume 会自动起服务）
_ensure() {
  _ping && return 0
  [ -f "$HEADERS" ] || return 1
  curl -s -m 6 -G -H @"$HEADERS" "$BRIDGE/app/launch" \
    --data-urlencode "pkg=com.dsha.floatball" >/dev/null 2>&1
  local i=0
  while [ "$i" -lt 15 ]; do
    sleep 0.4
    _ping && return 0
    i=$((i+1))
  done
  return 1
}

_load() {
  TASK=""; STEP=""
  if [ -f "$CUR" ]; then
    TASK=$(sed -n '1p' "$CUR")
    STEP=$(sed -n '2p' "$CUR")
  fi
}

_save() {
  printf '%s\n%s\n' "$TASK" "$STEP" > "$CUR"
}

_recent() {
  if [ -f "$RECENT" ]; then tail -3 "$RECENT" | paste -sd'|' -; fi
}

_set_plan() {
  printf '%s\n%s\n' "$1" "$2" > "$PLAN"
}

cmd="$1"; shift 2>/dev/null

case "$cmd" in
  ensure)
    if _ping; then
      echo "✓ 状态球在运行"
    elif _ensure; then
      echo "✓ 状态球已被唤醒"
    else
      echo "WARN 状态球唤醒失败（App 可能被卸载或悬浮窗权限被撤销）" >&2
      exit 2
    fi
    ;;
  plan)
    _ensure
    _set_plan "$*" 1
    _load
    _push running "$TASK" "$STEP" "$(_recent)"
    echo "▶ 计划：$*"
    ;;
  start)
    _ensure
    TASK="$*"; STEP=""; : > "$RECENT"
    _save
    _set_plan "" 0
    _push running "$TASK" "$STEP" "$(_recent)"
    echo "▶ 开始：$TASK"
    ;;
  step)
    _ensure
    _load; STEP="$*"; _save
    _push running "$TASK" "$STEP" "$(_recent)"
    echo "→ $STEP"
    ;;
  done)
    _ensure
    _load
    [ -n "$*" ] && echo "$*" >> "$RECENT"
    tail -3 "$RECENT" > "$RECENT.tmp" 2>/dev/null && mv "$RECENT.tmp" "$RECENT"
    steps=$(_plan_steps)
    if [ -n "$steps" ]; then
      c=$(_plan_cur)
      total=$(printf '%s' "$steps" | awk -F'|' '{print NF}')
      c=$(( ${c:-0} + 1 ))
      [ "$c" -gt "$total" ] && c=$total
      _set_plan "$steps" "$c"
    fi
    _push running "$TASK" "$STEP" "$(_recent)"
    echo "✓ 完成：$*"
    ;;
  ok)
    _load
    [ -n "$*" ] && echo "$*" >> "$RECENT"
    tail -3 "$RECENT" > "$RECENT.tmp" 2>/dev/null && mv "$RECENT.tmp" "$RECENT"
    _push running "$TASK" "$STEP" "$(_recent)"
    echo "✓ 完成：$*"
    ;;
  idle)
    curl -s -m 3 "$API/clearstop" >/dev/null 2>&1
    : > "$RECENT"; : > "$CUR"
    _set_plan "" 0
    _push idle "" "" ""
    echo "■ 空闲"
    ;;
  error)
    _ensure
    _load
    _push error "$*" "$STEP" "$(_recent)"
    echo "⚠ 异常：$*"
    ;;
  guard)
    if ! _ping; then
      if _ensure; then
        echo "INFO 状态球原先没响应，已自动唤醒" >&2
      else
        echo "WARN 状态球未响应且唤醒失败，继续执行" >&2
        exit 0
      fi
    fi
    st=$(curl -s -m 3 "$API/state")
    case "$st" in
      *";stop=1;"*)
        echo "STOP 用户已按停止：$st" >&2
        exit 1
        ;;
    esac
    exit 0
    ;;
  state)
    curl -s -m 3 "$API/state"; echo
    ;;
  *)
    sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
