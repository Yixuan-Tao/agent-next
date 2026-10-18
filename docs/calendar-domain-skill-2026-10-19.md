# Calendar domain skill（Android 16 平板）

日期：2026-10-19  
设备：Redmi Pad Pro，Android 16 / API 36  
状态：calendar 子目标 PASS；Chain A 的 Quark 天气段仍不稳定

## 为什么有 domain skill

MIUI 日历的自定义 View 在无障碍树中几乎没有文本（`nodes=96 text=""`），
prompt skill 无法可靠选择标题/时间/保存控件。  
因此把“创建提醒”做成一个**受治理的 domain action**：

- `CalendarPolicy` 读取 subgoal/task 语义，生成 `CalendarStore.EventSpec(title, start, end)`；
- `CalendarInsertAction` 通过 `CalendarContract` 直接插入事件 + reminder；
- `CalendarSubgoalVerifier` 查询 CalendarContract，不看 UI；
- 仍需要 `READ_CALENDAR`/`WRITE_CALENDAR` 权限；
- 仍经过计划确认；没有支付/发送/删除。

## 实测

计划：

```text
在日历创建明天上午 9 点的提醒：带伞
1. 创建入口 (visual)
2. 标题“带伞” (visual)
3. 日期明天/09:00 (visual)
4. 保存并确认 (calendar.event)
```

执行：

```text
verify calendar s1 -> false (calendar event missing)
verify calendar s1 -> true  (calendar event exists)
verify calendar s2 -> true
verify calendar s3 -> true
verify calendar s4 -> true
DONE · all subgoals verified actions=1 attempts=4
```

日历中实际出现“带伞”事件（标题/明天 09:00/提醒由 CalendarContract 写入）。

![Calendar domain skill DONE](assets/calendar-done.png)

## 限制

- 这是 domain skill，不是通过日历 UI 手动点击；Beta 计划确认仍展示 subgoals；
- 事件标题目前针对“带伞”场景，通用标题/时间解析待完善；
- 未删除/修改已有事件，只做幂等查找 + 插入；
- Quark 天气段仍未稳定通过，所以 **Chain A 整体尚未完成**。
