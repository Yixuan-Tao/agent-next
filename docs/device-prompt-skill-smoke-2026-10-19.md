# Prompt Skill 真机 smoke（2026-10-19）

状态：**PASS**。范围：app 读取 `files/model-config.json` → DeepSeek chat-completions（真实网络）→ `PromptSkillPolicy` → 语义动作 → FastLoop `RiskGate` → 本地 verifier，再用 `DeepSeekVisualModel` 做独立视觉 verify/extract。第一次把真实模型接入新 app 的 skill 执行路径。

## 环境

Redmi Pad Pro / Android 16 / API 36，Shizuku 13.6，京东 16.1.0。模型配置为旧项目的 `deepseek-flash`；密钥只放在 app 私有 `files/model-config.json` 和本机忽略目录，不提交、不打印。

## 流程

```sh
adb shell am start -n agent.next.app/.ShellActivity --es mode prompt
```

1. app 读取 `files/model-config.json`，创建 `ChatCompletionClient` + `DeepSeekSkillModel`。
2. app shell 先打开 JD（trusted prelude）：首页搜索栏不在 accessibility 树，仍用 `(360,180)` 受信任坐标进入搜索页；这个坐标**不在 skill/model 输出里**。
3. 搜索页上创建 `PromptSkillPolicy`，`SkillFrameSource` 每次从当前 JD target 抓 PNG。
4. 模型收到 prompt + 节点 + PNG，输出严格 JSON 语义动作：SetText / Tap / Wait / Done。
5. 每个动作映射为 `LoopAction`，经过 `DefaultRiskGate`（本次均为 LOW / REVERSIBLE_SIDE_EFFECT），再执行。
6. 结果页出现 `全部/新品/今日上新/店铺/奥莱/明日达`，`SkillVerifier` 通过，FastLoop `DONE`。
7. 对最终 PNG 调用 `DeepSeekVisualModel.verify`（独立核验）和 `extract`（阶段摘要/实体）；视觉结果不参与动作控制。

## 结果

```text
prompt: model=deepseek-flash base=https://api.deepseek.com
prompt: search page nodes=96 editable=true
prompt model step=0 -> SetText editable node text=短袖T恤 男款 XL risk=REVERSIBLE_SIDE_EFFECT
prompt model step=1 -> SetText viewId contains com.jd.lib.search.feature:id/zt text=短袖T恤 男款 XL risk=REVERSIBLE_SIDE_EFFECT
prompt model step=2 -> Tap clickable text contains 搜索 risk=LOW
prompt model step=3 -> Wait 700ms
prompt result: DONE actions=4 detail=goal complete
prompt visual verify: verified=true matched=2 missing=0
prompt visual extract: summaryChars=30 entities=7
PROMPT SKILL PASS
prompt target released
prompt display released
```

- 真实模型确实通过公网 API 返回了语义 JSON，没有返回坐标。
- 动作经过 RiskGate；本次没有确认/接管。
- 本地 verifier 看到结果页筛选项；视觉 verify 独立确认两个条件（匹配 2、缺失 0）；视觉 extract 返回 30 字摘要、7 个实体。
- 视觉调用只拿最终 PNG，不控制动作；extract 失败不会影响动作循环，但本次成功。
- 释放后无 `agent-phone` display，焦点回到 shell。

## 观察到的现象

- 模型连续输出两次 SetText；第二次用了 `viewId contains com.jd.lib.search.feature:id/zt`，仍是语义选择器；冗余但幂等，覆盖了输入框异步刷新。后续应加“已输入相同文本则跳过”的提示/策略。
- Wait 是必要的：点击搜索后结果页先是骨架屏；模型在 700ms 后 verifier 才看到筛选项。
- 首页搜索栏仍是 app 的 trusted prelude；模型输出没有坐标，skill 没有坐标。
- 视觉 verify 初始 extract 在 512 max_tokens 下 `finish_reason` 非 stop；提高到 2048 后成功。说明视觉调用要单独设置 token 预算并检查 finish_reason。

## 未覆盖

- 没有让模型自己从 JD 首页找到搜索入口；首页入口是 app 固定 prelude。
- 没有高风险/支付/登录/验证码场景；模型仍可能把高风险按钮声明为 LOW。
- 视觉 verify/extract 只拿最终单帧，不是多帧/视频；extract 的 entities 仍是自由键值，没有业务 schema。
- 没有多 App、回退、断网、限流、模型超时重试的完整验证。
- 模型请求/截图/API key 的隐私、脱敏和成本控制尚未产品化。

## 证据

本机忽略目录 `work/device-regression/app-shell/`：

- `prompt-skill-visual-pass.log`
- `prompt-skill-visual-pass.png`
- `prompt-skill-visual-results.png`

相关：[模型 API 移植](model-api.md)、[FastLoop 真机 smoke](device-fast-loop-smoke-2026-10-19.md)。
