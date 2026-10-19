# 计算器：视觉截图 + 模拟点击能实现吗？

日期：2026-10-20  
设备：Redmi Pad Pro / Android 16，私有 display 720×1280  
结论：**能，但纯视觉不是最稳的方案**。已实现“截图 → 视觉定位按钮 → 点击”的链路；真正稳定的是
“可访问性控件坐标优先，视觉定位兜底”的混合方案。

## 为什么最初失败

1. **表达式解析 bug**：`1234×5678` 的 `×` 没进 operator 分支，domain 没被选中，实际跑的是旧 prompt skill；
2. **旧 skill 不会点“=”**：它只按 selector 找，`viewId contains eq` 命中的不是等号键；
3. **快循环 3 次无进展就停**：`maxConsecutiveNoProgress=3`，连续 3 个 tap 后即使还能继续，也会进 slow loop 停止；
4. **InputReceipt 约束**：`INJECTED` + `verified=true` 是非法的，会让动作直接失败；
5. **纯视觉坐标不稳**：720×1280 的计算器键盘上，模型会把顶部显示区/历史里的数字当成按键，例如
   `1` 在 y≈1073，`2` 却被定位到 y≈819。

## 已实现的链路

```text
CalculatorDomainSkill
  -> fixedPlan: 单个 calculator.result 子目标
  -> CalculatorSequenceAction: 一个受治理动作内完成整串按键
       for each label:
         observe -> 先根据 UiNode 文本/contentDescription/bounds 找按键
                  -> 找不到才 captureFrame + VisualButtonLocator
         tap(center)
  -> CalculatorSubgoalVerifier: 从 observation 文本确认结果 7006652
```

关键文件：

- [VisualButtonLocator.java](../app/src/main/java/agent/next/app/skill/VisualButtonLocator.java)
- [CalculatorSequenceAction.java](../app/src/main/java/agent/next/app/skill/CalculatorSequenceAction.java)
- [CalculatorPolicy.java](../app/src/main/java/agent/next/app/skill/CalculatorPolicy.java)
- [CalculatorDomainSkill.java](../app/src/main/java/agent/next/app/skill/CalculatorDomainSkill.java)
- [CalculatorExpression.java](../app/src/main/java/agent/next/app/skill/CalculatorExpression.java)

## 实测

混合坐标后，键盘坐标变得一致：

```text
C  359,833
1  359,1067   2  440,1067   3  521,1067
4  359,989    5  440,989    6  521,989
7  359,911    8  440,911    9  521,911
×  603,911
=  603,1145
```

整串按键作为一个 action 执行，不再被 3 次 no-progress 截断。

目前状态：

- 有一次 verifier 通过，但界面里已经留着上一轮的 `7006652`，因此出现 `DONE actions=0`，
  不能作为“本轮真的算出来”的证据；
- 清空计算器数据（`pm clear com.miui.calculator`）后，计算器首次启动在私有 display 上
  `observe TIMEOUT`，干净状态的端到端验证被这个首次启动问题挡住。

## 结论

- **视觉 + 模拟点击可以实现计算器**，但纯视觉在密集小键盘上容易把显示区数字当按键；
- 对带文本/描述的按键，用 accessibility 的 bounds 点击更稳；
- 视觉定位应该留给无文本控件、图标或广告浮层；
- 多步点击必须封装成一个 composite action，否则会被 3 次 no-progress 的 slow loop 截断；
- 下一步应先解决计算器 `pm clear` 后首次启动的 observe 超时（等待首帧/跳过引导），再做一次
  `actions=1` 的干净 PASS。
