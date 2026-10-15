# Midscene.js 作为 E2E AI 断言层 —— 调研报告

> **文件说明**：本仓库原本没有 research 目录，测试类文档统一放在 `docs/testing/`（已有 `docs/testing/ui-testing/` 子目录与 `docs/testing/deploy-task-status-machine-ui-test-cases.md`）。因此本报告落在此处，与既有测试文档同域。

- 调研日期：2026-10-16
- 调研对象：`web-infra-dev/midscene`（Midscene.js），版本线 `1.15.4` / `1.15.5-beta`
- 证据等级约定：
  - **[文档]** = Midscene 官方文档原文（源文件即仓库内 `apps/site/docs/en/**`，是这些文档的 primary source；`midscenejs.com` 站点在本机 WebFetch 被网络策略拦截，故直接引用仓库原文）
  - **[源码]** = 仓库 `packages/**` 源码或 npm 已发布产物（`npm pack` 下载解包后 grep）
  - **[元数据]** = npm registry / GitHub API 直查
  - **[推断]** = 由上述证据推导，非某单一来源明说
  - **[未证实]** = 无法从 primary source 确认

---

## 1. Midscene 是什么 / 核心 API

### 1.1 官方包名与版本

**[元数据]** 官方包全部为 `@midscene/*` scope，当前均为 `1.15.4`，license MIT：

| 包 | 版本 | 作用 |
|---|---|---|
| `@midscene/core` | 1.15.4 | 内核（Agent 基类、模型调用、报告） |
| `@midscene/shared` | 1.15.4 | DOM 抽取、工具 |
| `@midscene/web` | 1.15.4 | 浏览器集成：Playwright / Puppeteer / Chrome 扩展 / Bridge |
| `@midscene/cli` | 1.15.4 | CLI + 旧 YAML 脚本运行器 |
| `@midscene/test` | 1.15.4 | 新一代 YAML 测试框架（**Beta**，见 §7） |
| `@midscene/android` / `ios` / `harmony` / `computer` | 1.15.4 | 各平台 Agent |

来源：`npm view <pkg> version license`（逐个查询）。

**[元数据] 重要陷阱**：npm 上存在一个**非官方**的裸名包 `midscene`，version `0.1.1`、描述是 `"Hello, It's MidScene"`、最后发布 `2024-07-09`、无 repository 字段。**这是无关的占位包，不要安装。**

**[源码]** `@midscene/web` 的构建产物导出映射（`packages/web-integration/package.json` `exports`）确认了集成入口（注意没有 `@midscene/playwright` 这样的包，一切从 `@midscene/web/*` 子路径进）：

```
"./playwright", "./playwright/agent", "./playwright-reporter",
"./puppeteer", "./bridge-mode", "./chrome-extension", "./yaml", "./static"
```

- Playwright Agent：`import { PlaywrightAgent, PlaywrightPageAgent } from '@midscene/web/playwright'`
- Playwright fixture：`import { PlaywrightAiFixture, type PlayWrightAiFixtureType } from '@midscene/web/playwright'`

`PlaywrightPageAgent` 是正式名，`PlaywrightAgent` 是向后兼容别名 —— `apps/site/docs/en/reference/index.mdx:2101-2105`。

### 1.2 核心 API（精确签名 + 分类）

**[文档]** `apps/site/docs/en/reference/index.mdx`。三类分法（该文档自身的分节标题即 `Planning and interaction` / `Extraction, location, and assertions` / `Observation and waiting`）：

**A. 动作类（Actions）** — 会操作页面

| API | 签名 | 行号 |
|---|---|---|
| `aiAct( )` / `ai( )` | `aiAct(prompt: string \| object, options?: {cacheable?, deepThink?, deepLocate?, fileChooserAccept?, fileChooserAllowedDir?, abortSignal?, context?}): Promise<string \| undefined>` | :179-191 |
| `aiTap` | `(locate: string \| object, options?): Promise<void>` | :288 |
| `aiHover` | `:323` |
| `aiInput` | `:354` |
| `aiClearInput` | `:446` |
| `aiKeyboardPress` | `:493` |
| `aiScroll` | `:559` |
| `aiPinch` | `:656` |
| `aiLongPress` | `:714` |
| `aiDoubleClick` | `:762` |
| `aiRightClick` | `:796` |

关键点：`aiAct` 是**"规划+执行+内嵌断言"**三合一 —— "[the prompt] can also include assertions. Midscene verifies these assertions during execution and throws an error promptly if one fails."（`reference/index.mdx:168`，`basics.mdx:15`）。已废弃别名：`aiAction()`。

**B. 断言 / 取值 / 定位类** — 只读页面

| API | 签名 | 分类 | 行号 |
|---|---|---|---|
| `aiAssert` | `(assertion: string \| object, errorMsg?: string, options?: {domIncluded?, screenshotIncluded?, context?}): Promise<void>` | **断言**，失败抛异常 | :1072-1077 |
| `aiQuery<T>` | `(dataDemand: string \| object, options?): Promise<T>` | **查询**，返回结构化数据 | :876 |
| `aiBoolean` | `(prompt, options?): Promise<boolean>` | 查询 | :926 |
| `aiNumber` | `prompt → Promise<number>` | 查询 | :957 |
| `aiString` | `prompt → Promise<string>` | 查询 | :991 |
| `aiAsk` | `prompt → Promise<string>` | 查询（与 `aiString` 行为相同） | :845 |
| `aiLocate` | `locate → Promise<{rect:{left,top,width,height}, center:[number,number], dpr?:number}>` | 定位（返回坐标，不断言） | :1024-1036 |

**C. 等待 / 观测**

| API | 签名 | 行号 |
|---|---|---|
| `aiWaitFor` | `(assertion: string, options?: {timeoutMs?: number; checkIntervalMs?: number; context?}): Promise<void>`，默认 `timeoutMs=15000`、`checkIntervalMs=3000` | :1178-1185, :1192-1193 |
| `startObserving` | 连续录制屏幕帧，可对录制帧做断言（用于 toast/过渡态） | :1133-1137 |

**分类结论**：真正的**断言**只有两个 —— `aiAssert`（严格判定，失败抛错）与 `aiWaitFor`（轮询等待条件成立，超时抛错）。`aiQuery/aiBoolean/aiNumber/aiString/aiAsk` 是**查询**（返回数据，不判定）。其余是**动作**。

> **[文档] 官方自己推荐的"确定性断言"写法不是 `aiAssert`**（见 §2）。
> 文档原文旁注：*"Assertions are critical in test scripts. To reduce the risk of model false positives or false negatives, combine `.aiQuery` with standard JavaScript assertions when you need deterministic checks."* — `reference/index.mdx:1099-1101`，并给出了 `aiQuery` + `expect(...)` 的替代样例（:1106-1112）。

---

## 2. 确定性（本调研的核心）

### 2.1 结论先行

**Midscene 的断言层在设计上就不是确定性的，且其缓存机制明确不覆盖断言/查询。**

### 2.2 缓存机制确实存在，但**从不缓存断言与查询**

**[文档]** `apps/site/docs/en/caching.mdx`。缓存分两类：

1. **任务指令缓存**（`ai` / `aiAct`）：以 prompt 为 key 缓存 AI 返回的**执行计划**
2. **元素定位缓存**（`aiLocate` / `aiTap` 等）：以定位 prompt 为 key 缓存**元素 XPath**，仅 web 可用

而原文明确列出（`caching.mdx:26`）：

> **"Never cache query results**: The query results like `aiBoolean`, `aiQuery`, `aiAssert` will never be cached."

**这是决定性的**：`aiAssert` / `aiQuery` 每次执行**必然调用模型**，不存在"二次运行回放"的可能。缓存只能加速**动作**（`aiAct`/`aiTap`），救不了断言。

### 2.3 缓存策略与"CI 命中"

**[文档]** `caching.mdx:32-146`。`cache` 选项四态：

- `false`（**默认**，不配置即禁用）或省略
- `{ id }` / `{ strategy: 'read-write', id }` —— 默认策略
- `{ strategy: 'read-only', id }` —— 只读，需手动 `agent.flushCache()`
- `{ strategy: 'write-only', id }` —— 只写

缓存文件落 `./midscene_run/cache/*.cache.yaml`（:30）。`MIDSCENE_RUN_DIR` 可改目录（`faq.md:188-204`，含 `report/ log/ cache/` 三子目录）。

**[文档] 关于 CI 的官方问答**（`caching.mdx:310-312`）：

> **"Why the cache is missed on CI?" —— "You need to commit the cache files to the repository in CI and recheck the cache hit conditions."**

即：官方对 CI 的建议是把缓存文件**提交进仓库**。但结合 2.2，这**对断言无效**——断言的缓存命中率恒为 0。

### 2.4 项目自己如何描述可靠性 —— 明确说缓存不等于稳定性

**[文档] 这是最有价值的一段**（`caching.mdx:314-316`）：

> **"Does it mean that AI services are no longer needed after using cache?"**
> **"No. Caching is the way to accelerate the execution, but it's not a tool for ensuring long-term script stability. We have noticed many scenarios where the cache may miss when the DOM structure changes. AI services are still needed to reevaluate the task when the cache miss occurs."**

（中译：缓存只是加速手段，**不是保证脚本长期稳定的工具**；DOM 结构一变就会 miss，miss 后仍要回到模型重算。）

XPath 定位缓存的失效条件被刻意做得很严（`caching.mdx:328-333`）：同 XPath 元素文本变了、或页面 DOM 结构变了，都判为 miss。

### 2.5 缓存**完全不可用**的场景

**[文档]** `caching.mdx:335-341`，XPath 定位缓存在以下场景**根本不可用**（直接回落模型）：

1. **Canvas** —— 内部图形无 DOM 节点，无法 XPath 定位
2. **跨域 iframe** —— 浏览器安全策略禁止访问其内部 DOM
3. **Shadow DOM（closed 模式）** —— 外部无法访问
4. **WebGL / 动态 SVG** —— 无稳定 DOM 结构

> 注意：第 3 条只说明"**定位缓存**"不可用（因为缓存载体是 XPath），**不等于**"视觉定位不可用"。见 §6。

### 2.6 重试机制（模型层）

**[文档]** `apps/site/docs/en/model-config.mdx:50-51`：

- `MIDSCENE_MODEL_RETRY_COUNT` 默认 `1`（失败后重试一次）；触发条件是 HTTP 错误**或模型响应无法结构化解析**
- `MIDSCENE_MODEL_RETRY_INTERVAL` 默认 `2000` ms
- `MIDSCENE_MODEL_TIMEOUT` 默认 `180000` ms（180s，每次重试独立计时）

**[文档]** Midscene 自己的 CI 把它调高（见 §4.3）：`.github/workflows/ai.yml:37-38` 设 `MIDSCENE_MODEL_RETRY_COUNT: "2"`、`MIDSCENE_MODEL_RETRY_INTERVAL: "60000"`。**一个模型请求失败要等 60 秒才重试**——这本身就说明官方预期模型调用会不稳。

### 2.7 是否存在"录制 & 回放"（record & replay）？

**[文档]** 没有找到任何将**记录下来的模型响应回放**给断言的机制。

- `MIDSCENE_RECORD_MODEL_CALL=true` 会把模型请求/响应/流式分片写入本地 JSONL（`model-config.mdx:195`）—— 但定位是**调试与可观测性**（"model diagnostics, tracing, and local call recording"），文档未描述任何 replay 回灌路径。
- 唯一近似"回放"的是 **cache 重放**，而它明确排除 `aiAssert`/`aiQuery`（§2.2）。

**结论：断言层不存在 record & replay。[未证实·否定]** 我尽可能检索了 `caching.mdx` / `model-config.mdx` / `model-debugging-observability.mdx` / `faq.md` / `reference/index.mdx`，未发现此类特性。

### 2.8 官方给出的唯一"确定性"路径 = 代码编排，而非 AI 断言

**[文档]** `basics.mdx:175-185`：

> *"JavaScript orchestration has an explicit execution path, allowing developers to control the behavior of each branch precisely. **This makes the approach highly deterministic.**"*

并给出决策准则（:183-185）：默认用 `aiAct`；**只有流程稳定可预期时才用 JS 编排**。

**[推断]** 换言之：Midscene 官方认可的确定性来源是"用 `aiQuery` 取值 + 自己写 `expect`"，本质是把不确定性收敛到一次取值上。这与本仓库现有 Playwright 断言的定位其实相近，AI 只负责"取值"这一步。

### 2.9 基准测试数据是否证明断言确定性？

**[文档]** 不是。官方公布的三个 benchmark（`introduction.mdx:45-49`）测的是 **agent 任务成功率**（AndroidWorld Pass@1 93.10%、MobileWorld 78.63%、AppControlBench 96.7%），**不是断言判定的稳定性**，且均为**项目自测、自报**。

- 注意 `AppControlBench` 下有一条 **Fail** 用例（`apps/site/theme/components/app-control-bench-data.ts`：`bsky-25` status `Fail`），即同模型下并非 100% 通过 —— 这是自报数据里就能看到的非确定性证据。
- AndroidWorld 的 Pass@2/Pass@3（95.69%/97.41%）**比 Pass@1 高**，即靠重试才收敛 —— 同样是"单次不确定性"的间接证据。

---

## 3. 成本与模型选项

### 3.1 支持的模型供应商

**[文档]** `apps/site/docs/en/model-common-config.mdx:16` 列出的家族：

`Doubao Seed` · `Qwen` · `DeepSeek` · `Google Gemini` · `OpenAI GPT` · `Moonshot Kimi` · `Xiaomi MiMo` · `Zhipu GLM-V`，以及 `AutoGLM`、`UI-TARS`。

**注意：列表中没有 Anthropic / Claude。** [未证实] 是否能经 OpenAI 兼容代理接入 Claude —— 文档未提及，无法证实。

配置四要素（`model-common-config.mdx:18`）：`MIDSCENE_MODEL_BASE_URL` + `MIDSCENE_MODEL_API_KEY` + `MIDSCENE_MODEL_NAME` + `MIDSCENE_MODEL_FAMILY`（family 决定坐标系处理逻辑）。

### 3.2 每次断言是否都要调模型？

**[文档·确证]** 是。§2.2 已证：`aiAssert`/`aiQuery` **永不缓存**，故每次调用都是一次模型请求。

**[文档]** 默认一次请求就带**截图**：*"The screenshot will be sent to the AI model."*，只有设 `domIncluded: true` 时才额外带（简化后的）DOM —— `faq.md:97-99`。`aiQuery`/`aiAssert` 的 `domIncluded` **默认 false**（`reference/index.mdx:883, :1085`）。

### 3.3 是否有 token / 成本的 per-assertion 指导？

**[文档·部分]** 有量级数据，但**不是 per-assertion**：

- `AppControlBench` 60 个任务、Doubao Seed 2.1 Turbo，**总成本 $0.59 / ¥4.02**，`passAt1` 96.67%，**平均每任务 51.98 秒**（`apps/site/theme/components/app-control-bench-data.ts`：`totalUsd: 0.591189`、`averageDurationSeconds: 51.982133`；`app-control-bench-report.mdx:9` 给出单价：输入 ¥3/M、缓存输入 ¥0.6/M、输出 ¥15/M）。
- 单任务成本区间：约 **$0.0037 ~ $0.024**（同数据源逐条 `costUsd`，最小 `bsky-22` 0.003740，最大 `bsky-16` 0.023987）。

> **[推断·重要边界]** 上述是 **app-control 多步操作任务**的成本，**不是单条 `aiAssert` 的成本**。单靠一条断言的成本**没有官方数字**。若要本仓库落地，必须实测 —— 见 Unknowns。

**[文档]** 降本手段（`faq.md:178-184`）：用 instant action（`aiTap`）替 `aiAct`；降分辨率；换更快的模型；用缓存。另：CI 里 DPR 常为 1，提到 2 能改善小元素定位但**更耗 token**（`faq.md:284-288`）。

**[文档]** 成本可观测：`agent.metrics`（`totalTokens` / `byIntent` / `byModel`，`reference/index.mdx:1557-1591`）与 `onLLMUsage` 回调（:1594-1606）。

**[文档]** 可配 Planning / Insight 双模型分离（复杂任务时），但**会增加延迟与 token**（`model-strategy.mdx:36`）。

### 3.4 能否不依赖云模型（本地 / 自托管）？

**[文档·部分]** 可以自托管**开源 VLM**，但仍需**一个模型服务**，只是 endpoint 指向自建：

- `GLM-4.6V` 开源（GitHub `zai-org/GLM-V` / HF `zai-org/GLM-4.6V`）—— `model-common-config.mdx:245, :260-263`
- `AutoGLM-Phone-9B` 开源 —— `model-common-config.mdx:369, :394-396`
- vLLM 自托管被提及（`MIDSCENE_MODEL_EXTRA_BODY_JSON` 示例用 `chat_template_kwargs`，`model-config.mdx:60`）
- 通用：任何 **OpenAI 兼容** endpoint 都能接（协议 `openai-chat` 默认 / `openai-responses`，`model-config.mdx:19-21`）

**没有任何"无模型"模式**。纯视觉方案的前提就是必须有视觉理解模型：*"Pure-vision localization requires models with visual understanding capabilities — only designated models that are stable for GUI operations can be used, not any arbitrary LLM."*（`model-strategy.mdx:18`）

**[文档]** 数据隐私：*"your page data (including the screenshot) is sent directly to the AI model provider you choose."*（`data-privacy.md:5`）

---

## 4. CI / 无头可行性

### 4.1 无头运行

**[文档·确证]** 官方示例就是无头：

```ts
const browser = await chromium.launch({
  headless: true,
  args: ['--no-sandbox', '--disable-setuid-sandbox'],
});
```
—— `apps/site/docs/en/integrate-with-playwright.mdx:43-46`

前置：`npx playwright install chromium`（:375-389）。

**[文档] 仅 Chromium 得到完整支持**：*"Midscene web automation features rely on Chrome DevTools Protocol (CDP)... Chromium is the recommended browser engine. Firefox and WebKit may work for basic Playwright-native operations, but Midscene features that depend on CDP may report errors on those engines."* —— `integrate-with-playwright.mdx:286-288`

### 4.2 环境变量与密钥

**[文档]** `MIDSCENE_MODEL_API_KEY` / `_BASE_URL` / `_NAME` / `_FAMILY`（`model-common-config.mdx:265-319`）。加载方式三选一：shell `export`、CLI 自动读 `.env`、SDK 用 `dotenv` 注入 `process.env`。

**[文档]** 连通性自检：`npx @midscene/cli@latest model verify`，会依次校验 text / vision / AI locate，**退出码 0/1**（`model-debugging-observability.mdx:44-70`）。这对 CI 前置健康检查很有用。

### 4.3 官方自己的 CI 怎么跑（**最硬的证据**）

**[源码]** 仓库 `.github/workflows/ai.yml` —— Midscene 自建 e2e 工作流（GitHub Actions）：

```yaml
# ai.yml:32-42
env:
  MIDSCENE_MODEL_API_KEY: ${{ secrets.MIDSCENE_MODEL_API_KEY }}
  MIDSCENE_MODEL_BASE_URL: ${{ vars.MIDSCENE_MODEL_BASE_URL }}
  MIDSCENE_MODEL_NAME: ${{ vars.MIDSCENE_MODEL_NAME }}
  MIDSCENE_MODEL_FAMILY: ${{ vars.MIDSCENE_MODEL_FAMILY }}
  MIDSCENE_MODEL_RETRY_COUNT: "2"
  MIDSCENE_MODEL_RETRY_INTERVAL: "60000"
  MIDSCENE_REPORT_QUIET: "true"
  CI: 1
```

关键事实（`ai.yml:44-150`）：

- runner：`fromJSON(vars.LINUX_SELF_HOSTED_RUNNER_LABELS || '"ubuntu-22.04"')` —— **默认托管 runner 存在，但官方实际用自托管 runner**
- `runs-on` 上的 e2e 步骤：`continue-on-error: true` + `timeout-minutes: 20`（:93-94），**最后再单独一步判定失败并 `exit 1`**（:143-150）
- 浏览器：`playwright install chromium`（:78-79）
- 产物：上传 `midscene_run/report`（:120-126）
- 触发：push main / PR（:2-16）—— 即**确实以门禁方式跑 AI e2e**

**[源码]** 复合 action `.github/actions/ai-ci/action.yml` 做了一件事：为 Node 加 `--network-family-autoselection-attempt-timeout=2000`，注释称 *"Ark IPv4 connections can exceed Node's default 250ms address attempt"* —— 即**模型 endpoint 连接本身需要网络层调参**。

### 4.4 CI 特有的坑

**[文档]** 1. **截图卡在等字体加载**：

```
page.screenshot: Timeout 10000ms exceeded. ... waiting for fonts to load...
```
> *"In some CI, container, or restricted network environments, font resources may load very slowly or never finish"* → 解法 `export PW_TEST_SCREENSHOT_NO_FONTS_READY=1`（`integrate-with-playwright.mdx:432-457`）

**[文档]** 2. **CI 里 DPR 常为 1，小元素定位变差**，可提到 2（代价是更多 token）（`faq.md:284-288`）

**[文档]** 3. 定位精度问题有一整节排障指引（`faq.md:221-288`）：升级版本 / 换更强视觉模型 / 检查 `MIDSCENE_MODEL_FAMILY` / **改 prompt 为视觉化描述**（`aiTap('profile center')` ❌ → `aiTap('person avatar icon')` ✅）/ 开 `deepLocate`。**注意最后一条：定位结果会在多次运行间随机漂移（"varies significantly between runs"），官方给的解法是改 prompt —— 这是不稳定的直接自述。**

**[文档]** 4. 未发现 GitLab CI 的官方示例。官方只用 GitHub Actions。[未证实] GitLab CI 无 primary source 参考。

### 4.5 报告

**[文档]** Playwright reporter：`["@midscene/web/playwright-reporter", { type: "merged" | "separate" }]`，`outputFormat` 可 `single-html`（默认，base64 内嵌）或 `html-and-external-assets`（需 HTTP 服务，不能 `file://` 打开）（`integrate-with-playwright.mdx:147-161`）。也可脱离 reporter 直接看 `midscene_run/report/*.html`。

---

## 5. 与 Playwright 的集成形态

### 5.1 两种形态：fixture **和** agent 对象（两种都提供）

**[源码]** `packages/web-integration/src/playwright/ai-fixture.ts:77-636`：

1. **Fixtures**：`PlaywrightAiFixture(options)` 返回一个可传给 `base.extend<T>()` 的对象，注入这些 fixture：`ai`、`aiAct`、`aiTap`、`aiQuery`、`aiAssert`、`aiWaitFor`、`aiLocate`、`aiBoolean`、`aiInput`、`aiScroll`、`recordToReport`、`runYaml`、`agentForPage` …（:332-634）
2. **Agent 对象**：fixture 内部**为每个 page 惰性创建并复用**一个 `PlaywrightAgent`/`PlaywrightBrowserAgent`（`createOrReuseAgentForPage`，:136-204）；用 `agentForPage(page)` 取底层实例调任意 API（:287-331）
3. 也支持**完全不走 fixture**：直接 `new PlaywrightAgent(page)`（`integrate-with-playwright.mdx:55`）

**[源码] 一个有用的细节**：fixture 把每次 AI 调用包进 `test.step(\`ai-${aiActionType} - ${prompt}\`)`（:245），所以 AI 步骤会出现在 Playwright 自己的 trace/report 里。

### 5.2 官方 canonical 集成片段

**[文档]** `integrate-with-playwright.mdx:163-252`：

```ts
// ./e2e/fixture.ts
import { test as base } from '@playwright/test';
import type { PlayWrightAiFixtureType } from '@midscene/web/playwright';
import { PlaywrightAiFixture } from '@midscene/web/playwright';

export const test = base.extend<PlayWrightAiFixtureType>(
  PlaywrightAiFixture({
    waitForNetworkIdleTimeout: 2000, // 可选，默认 2000ms
    replanningCycleLimit: 30,        // 可选
  }),
);
```

```ts
// ./e2e/ebay-search.spec.ts
import { expect } from '@playwright/test';
import { test } from './fixture';

test('search headphone on ebay', async ({ aiQuery, aiAssert, aiTap, aiWaitFor }) => {
  await aiTap('search button');
  await aiWaitFor('search results list loaded', { timeoutMs: 5000 });

  const items = await aiQuery<Array<{ title: string; price: number }>>(
    'get product titles and prices from search results',
  );
  expect(items?.length).toBeGreaterThan(0);   // ← 与原生 Playwright 断言并存
  await aiAssert('category filter exists on the left side');
});
```

playwright.config 需加（:147-153）：

```diff
+ timeout: 90 * 1000,
+ reporter: [["list"], ["@midscene/web/playwright-reporter", { type: "merged" }]],
```

### 5.3 版本下限与共存

**[源码]** `packages/web-integration/package.json`：

```json
"engines": { "node": ">=18.19.0" },
"peerDependencies": {
  "@playwright/test": "^1.45.0",
  "playwright": "^1.45.0",
  "puppeteer": ">=20.0.0"
},
"peerDependenciesMeta": { "@playwright/test": {"optional": true}, "puppeteer": {"optional": true} }
```

- **Playwright 下限 `^1.45.0`** → 本仓库 `^1.61.0` **满足**。
- 另有 `forceChromeSelectRendering` 要求 **Playwright ≥ 1.52.0**（`reference/index.mdx:2128`）→ 也满足。
- `@midscene/web` 要求 Node `>=18.19.0` → 本仓库 CI `node:20` **满足**。
- **[源码] 但 `@midscene/test` 要求 Node `^20.19.0 || ^22.12.0 || >=24.0.0`**（`npm view @midscene/test`）→ `node:20` **镜像未必满足**（取决于 20.x 具体小版本）。

**共存：可以。** fixture 是 `@playwright/test` 自己的 `test.extend`，同一个文件里 `page.locator()`、`expect()` 与 `aiAssert()` 并用无冲突（官方示例即如此，:198-251）。

### 5.4 Midscene 自带的独立 YAML runner（与 Playwright 集成有何不同）

存在**两代**，别混淆：

**[文档] (a) 旧版 YAML 脚本运行器（`@midscene/cli`）**
- 命令 `midscene`，YAML 写 `tasks` / `flow`；`agent.runYaml()` 也能在 Playwright 里跑（`reference/index.mdx:1225-1232`）
- 依赖 `@midscene/yaml`，走 CLI 而非 Playwright
- **现状：维护模式**（见 (b)）

**[文档] (b) `@midscene/test`（新一代，Beta）** —— `apps/site/docs/en/midscene-test/overview.mdx`
- 命令 `midscene-test`，配置文件 `midscene.config.ts`，YAML 写 `cases/steps`（`aiAct:` / `aiAssert:` 直接作为 step 名）
- 用 TypeScript 写**自定义 Node**（Zod schema）承接 API 调用、造数、清理 —— 官方称为"Declarative Semantics + Deterministic Engineering"
- 并发模型：**调度单位是 execution project**，`test.maxConcurrency` 默认 1；**同一 project 内的文件/case/step 是顺序执行的**，想并发必须拆成多 project + 各自资源（`midscene-test/configuration.mdx:417-446`）
- `retry` 默认 0（:342）；可 `midscene-test nodes` 导出 Node Spec 供人和 AI 共维护
- **原文声明**：*"It is currently in **Beta**, and its test protocol and APIs continue to evolve. The legacy `tasks/flow` YAML path is now in maintenance mode"*（`mt/overview.mdx:7-8`）

**与 Playwright 集成的差异（[推断]整理自上述文档）**：独立 runner 不继承 Playwright 的 test 语义 —— 没有 `expect`、没有 Playwright 的 `workers`/`retries`/`projects`/`reporter`/trace、没有 `--attach` 这类自定义 runner 能力；它自带生命周期、并发与报告。Playwright fixture 则**留在现有 Playwright 体系内**。

---

## 6. 微前端 / Shadow DOM / iframe

### 6.1 定位原理：默认**纯视觉**（screenshot-based）

**[文档·关键]** `apps/site/docs/en/model-strategy.mdx:7`：

> *"Midscene uses **pure vision**, with the model analyzing the UI screenshot and locating target elements directly. **UI actions and element localization do not depend on DOM data or extra annotations.**"*

并给出"跨渲染栈一致"的理由（:11-14）：同一套做法可覆盖 Canvas / Android / iOS / 桌面；token 消耗只与**页面分辨率与任务复杂度**相关，**不随 DOM 节点数膨胀**。

**这直接意味着**：`aiAssert` / `aiQuery` 的**输入默认就是截图**（`domIncluded` 默认 false，`reference/index.mdx:883, :1085`）。截图是一张平面位图 —— **只要内容被渲染到屏幕上（含 iframe 内容、open shadow DOM 内容、Canvas 绘制内容），视觉上就可判**。

### 6.2 文档对跨域 iframe 的明确声明

**[文档]** `apps/site/docs/en/introduction.mdx:30`：

> *"You can target icon-only buttons, custom controls, `<canvas>`, and **elements inside cross-origin iframes** without writing selectors or adding semantic annotations."*

`model-strategy.mdx:11-12` 亦称可跨 Canvas/Android/iOS/桌面一致工作。

> ⚠️ **[文档·需谨慎]** 这是**官方文档的主张**，我未找到对应的可复现实验报告或测试用例佐证。按本报告约定标为"docs 声称"而非"已验证为真"。

### 6.3 Shadow DOM：文档**没有任何**"视觉可穿透 shadow DOM"的表述

**[源码/文档] 穷举 shadow DOM 出现的位置**（对全部已获取文档 + 已发布包产物做 grep）：

| 位置 | 内容 | 性质 |
|---|---|---|
| `apps/site/docs/en/caching.mdx:339` | "Shadow DOM (closed mode): Closed Shadow DOM cannot be accessed from outside" | **仅**讨论 XPath **定位缓存**不可用 |
| `@midscene/web` 发布产物 `dist/**/common/input-scripts.*` | `while (activeElement?.shadowRoot?.activeElement) { activeElement = activeElement.shadowRoot.activeElement; }` | 输入框 **select-all** 脚本会穿透 `shadowRoot` 找 activeElement（运行时细节） |
| `@midscene/core` 发布产物 `report-html-template` | `ShadowRoot` / `shadowRoot` | **报告 HTML 模板自身**的实现，与被测页面无关 |

**关键否定证据**：在仓库 DOM 抽取器源码中**完全没有 shadow 相关处理** —— 我对 `packages/shared/src/extractor/{web-extractor,tree,locator,dom-util}.ts`（已下载全文）grep `shadow`，**0 命中**；`locator.ts` 的 XPath 构造只处理 **iframe 路径拼接**（`SUB_XPATH_SEPARATOR = '|>>|'`，:12-13，跨 iframe 复合 XPath，:186-214）。

**[推断]** 因此：
- **视觉模式（默认，`domIncluded: false`）**：shadow DOM 内容只要被渲染出来就在截图里 → `aiAssert`/`aiQuery` **可判**（不依赖 DOM 访问，closed shadow 也一样）。这一条是**推断**，但建立在"纯视觉 + 截图即输入"这一已确证的机制上。
- **DOM 模式（`domIncluded: true`）**：走 Midscene 自己的 DOM 抽取器，而该抽取器**未见 shadow DOM 穿透逻辑** → **很可能取不到 shadow DOM 内的节点**。[未证实] 文档未说明；仅有源码缺失作为旁证。

### 6.4 与本仓库 Wujie 容器的关系

**[源码]** 本仓库 `frontend/src/plugins/components/PluginContainer.vue` 用 `wujie-vue3` 加载插件子应用（注释原文：*"插件容器组件（Wujie 版）… 替代原有的 iframe 方案"*，:1-4）；`frontend/src/main.js:6,26` 注册 `app.use(WujieVue)`。现有 E2E（如 `frontend/e2e/plugins/l4d2/plugin-subapp.spec.js`）已能直接在容器内选取 `.dashboard-page` 之类选择器。

**[推断]** 对本仓库而言，Midscene 的**视觉模式反而是优势**：Wujie 子应用最终会渲染成屏幕上的像素，`aiAssert('仪表盘显示 N 个指标卡')` 这类断言**无需关心 Wujie 内部是 iframe 还是 shadow DOM**。这恰是 §6.3 中"视觉模式可判"的适用场景。

**[未证实]** 本仓库 Wujie 的实际 DOM 形态（iframe / shadow DOM / 直接注入主文档）与是否同源 —— 未从 Wujie primary source 核实，超出本次调研范围。若将来要依赖 `domIncluded`，必须先实测。

---

## 7. 成熟度信号

**[元数据]** GitHub API `GET /repos/web-infra-dev/midscene`（2026-10-15 抓取）：

| 项 | 值 |
|---|---|
| 仓库 | `web-infra-dev/midscene` |
| 描述 | "See interfaces like a human. E2E testing in natural language." |
| Stars | **15,293** |
| Forks | 1,199 |
| Open issues | 117 |
| License | **MIT** |
| 创建 | 2024-07-23 |
| 最后 push | 2026-10-15（**调研前一天**，活跃） |
| Archived | false |
| 主页 | https://midscenejs.com |

**[元数据]** 最新正式版：**`v1.15.4`，发布于 2026-10-14**（GitHub Releases API，`prerelease: false`）。
**[元数据]** npm `@midscene/web` 同时挂着 `beta: 1.15.5-beta-20261015030228.0`，发布时间 2026-10-15 —— **beta 版本近乎每日多次**（`npm view @midscene/web time` 中可见同一天多个 beta 号）。

**[文档·稳定性自述]**

- **`@midscene/test` 明确是 Beta**：*"It is currently in Beta, and its test protocol and APIs continue to evolve"*（`mt/overview.mdx:7-8`）。正式版号 1.x 但框架层自称 Beta。
- 存在显式 unstable 接口：`_unstableLogContent()` —— *"The structure of the log object may change in future versions."*（`reference/index.mdx:1528-1530`）
- 存在仍在迭代中的重命名/废弃：`aiAction`→`aiAct`；`deepThink` 语义在 v1.5.1 才被拆分为 `deepThink`(规划) vs `deepLocate`(定位)（`reference/index.mdx:1698-1708`）
- 兼容层大量存在：`cacheId`、`MIDSCENE_CACHE=1`、`MIDSCENE_USE_VLM_UI_TARS` 均标 deprecated

**[推断] 定性**：核心 Agent API（`aiTap`/`aiQuery`/`aiAssert`）已相当稳定并围绕其构建了完整生态（示例仓库、Playground、Chrome 扩展、多平台），可视为**生产可用**；但**测试框架层（`@midscene/test`）与部分选项仍在 Beta/演进中**。许可证 MIT，商用无障碍，但**模型调用是第三方付费依赖**。

---

## What this means for this repo（逐问结论：支持 / 削弱 CI 冒烟门禁）

> 背景事实（已核对）：`.gitlab-ci.yml` 只有 `mvn -B test` 与 `npm run test:run`（vitest），**无 Playwright job**；`CONTEXT.md:210-211` 定义了"冒烟集 = CI 门禁自动执行的**无主机** E2E 子集，红线是**CI 环境可重复执行**"，但 `frontend/e2e/` 中**无对应实现**。也就是说：**这条 lane 目前是文档里的空承诺，谁先落地谁定标准。**

| # | 问题 | 对"Midscene 进 CI 冒烟 lane"的结论 |
|---|---|---|
| 1 | API 能力 | **支持**。`aiAssert` 一行断言、`aiQuery`+`expect` 组合，API 干净，且能直接复用现有 Playwright suite。 |
| 2 | **确定性** | **强烈削弱（最大否决项）**。`aiAssert`/`aiQuery` **永不缓存**（`caching.mdx:26`）→ 每次运行都打模型；项目自己说缓存"**不是保证脚本长期稳定的工具**"（`caching.mdx:314-316`）；**断言层无 record & replay**（§2.7）。这与 CONTEXT.md 的"CI 环境可重复执行"红线**正面冲突**。官方对"CI 里缓存 miss"的答案（把 cache 文件提交进仓库）**对断言无效**。 |
| 3 | 成本 / 模型 | **削弱**。断言 100% 依赖外部付费模型；**无离线模式**；per-assertion 成本官方无数字（只有 per-task 的 $0.0037~$0.024 量级参考）。门禁意味着**每次 CI 流水线都烧钱 + 吃延迟**。另：截图会上传第三方模型厂商（`data-privacy.md:5`）—— 本平台页面含**主机 IP、凭据表单等**，需做数据合规评估。 |
| 4 | CI / 无头 | **支持（技术上）但引入新依赖**。官方自建 GH Actions 就是无头 Chromium 跑 AI e2e（`ai.yml`），技术上成立；但 CI 需要**新的 secret**（`MIDSCENE_MODEL_API_KEY`）、**出网到模型服务**、通常还要 `MIDSCENE_MODEL_RETRY_COUNT=2` / 60s 重试间隔、`PW_TEST_SCREENSHOT_NO_FONTS_READY=1`、Node 网络调参。GitLab CI **无官方示例**，需自行摸索。 |
| 5 | Playwright 集成 | **支持**。`^1.45.0` 下限被本仓库 `^1.61.0` 满足；fixture 与原生 `expect`/`locator` 同文件共存；`test.step` 包裹使 AI 步骤进入 Playwright report。`@midscene/web` 的 Node `>=18.19.0` 与 CI `node:20` 兼容（**但 `@midscene/test` 需 Node ≥ 20.19，选型时注意**）。 |
| 6 | 微前端 / Shadow DOM / iframe | **支持（且是整个提案里最站得住的一点）**。默认纯视觉 → Wujie 子应用渲染成像素即可被 `aiAssert`/`aiQuery` 判定，**绕开 Wujie 容器内部 DOM 形态**。但**不要用 `domIncluded: true`** —— 抽取器源码无 shadow DOM 处理（§6.3）。 |
| 7 | 成熟度 | **混合**。MIT、15.3k stars、日更、正式版 1.15.4（2026-10-14）—— 核心层生产可用；但 `@midscene/test` 自称 **Beta**、存在 `_unstable*` 接口、beta 号每日多次 → **不建议把 CI 门禁压在仍在演进的框架层上**。 |

### 综合判断（[推断]）

**不建议把 Midscene 放进 CI 冒烟门禁。** 决定性理由是 **#2 确定性**：一个要求"可重复执行"的门禁，不能建立在"每次都要重新问一次 VLM"的断言上，而 Midscene 对断言**明确不提供缓存或回放兜底**。

**更合理的落点是"全量回归集"**（`CONTEXT.md:213-214`：打真实牺牲主机、发版前手动触发、不进 CI）。在那里：
- 外部模型依赖与延迟可接受（本来就不是每次 push 都跑）；
- 纯视觉定位对 **Wujie 插件子应用**（`frontend/e2e/plugins/l4d2/plugin-subapp.spec.js` 那条最复杂、最依赖 DOM 细节的链路）反而能降低选择器维护成本（#6）；
- 可作为**补充断言层**与现有 Playwright 断言并存，而非替换。

**若仍要在 CI 里试水，最小可行边界**（[推断]）：
1. 只用 `aiQuery` + `expect`，**不用 `aiAssert`**（贴官方自己的建议，`reference/index.mdx:1099-1112`）——把不确定性收敛到"取值"一步，判定仍由确定性代码做；
2. 限制在**无主机**用例（`skipWithoutTestHost()` 之外的那批），断言目标选**高对比度、大字号、低歧义**的稳定元素（避开 `faq.md:251-268` 点名的 icon 按钮类）；
3. 设 `MIDSCENE_MODEL_RETRY_COUNT=2` / `RETRY_INTERVAL=60000`（照抄官方）、`PW_TEST_SCREENSHOT_NO_FONTS_READY=1`、`MIDSCENE_REPORT_QUIET=true`，并加 `test.setTimeout` 放宽；
4. 先跑一次 **成本实测**（用 `agent.metrics.totalTokens` 计量），确认单次全量冒烟的成本与耗时**可接受**，再决定是否长期保留；
5. 明确接受它**不是硬门禁**：官方自己在 CI 里对 e2e 步骤用 `continue-on-error: true` + 单独判定（`ai.yml:93, :143-150`）——**这个姿态值得直接照搬**。

---

## Unknowns（未能证实的事项 —— 比已证实的更重要）

1. **`aiAssert` 的真实稳定性 / 误报误漏率**：官方无任何 per-assertion 的准确率或 flakiness 数据。三个 benchmark 都是 **agent 任务**成功率（自报），不能外推到断言层。
2. **单条 `aiAssert` 的 token 与金钱成本**：无官方数字。只有 per-**task** 的 $0.0037~$0.024 量级（AppControlBench，Doubao Seed 2.1 Turbo，2026-08 单价）。**要落地必须自测。**
3. **`domIncluded: true` 能否取到 open shadow DOM 内容**：文档完全未提。我的旁证是**抽取器源码 grep `shadow` 零命中**，属**反向证据**，不等于已证伪（可能 shadow 穿透在别处、或依赖 Playwright 侧的能力）。
4. **官方"跨域 iframe 可定位"（`introduction.mdx:30`）的实证**：未找到可复现报告或测试用例，仅文档主张。
5. **本仓库 Wujie 容器的实际 DOM 形态与同源性**（iframe / shadow DOM / 直接注入）：未从 Wujie primary source 核实。
6. **`aiAssert` 失败时 Midscene 自身是否会重试该断言**：只查到**模型请求层**重试（`MIDSCENE_MODEL_RETRY_COUNT`，仅 HTTP 错误或响应无法解析时触发）。断言"判为 false"是否重试，未查实 —— 语义上应属 Playwright 的 test retry 职责，但未获确证。
7. **GitLab CI 的官方示例**：未找到（官方只用 GitHub Actions）。`MIDSCENE_RUN_DIR` / 报告产物在 GitLab artifacts 下的最佳实践属空白。
8. **Anthropic / Claude 能否接入**：官方模型家族列表**不含 Anthropic**；是否可经 OpenAI 兼容代理接入，文档未提。
9. **官方 AI e2e 在 GitHub **托管** runner 上是否真的绿**：工作流默认值是 `ubuntu-22.04`，但实际经 `vars.LINUX_SELF_HOSTED_RUNNER_LABELS` 走自托管 runner。托管 runner 上的可用性/耗时未证实。
10. **模型侧的并发/限流约束**：未见文档说明最大并发模型调用数或 rate limit 应对策略（§5.4 提到 `@midscene/test` 的并发是 project 级、默认 1）。
11. **`midscenejs.com` 站点未能直连**：本机对被该域名的 WebFetch 被网络策略拦截（"Unable to verify if domain is safe to fetch"）。本报告**全部文档证据改用仓库内 `apps/site/docs/en/**` 原文**（即该站点的 source of truth），非二手转述。但若站点上有**仓库里没有的**内容，本报告未覆盖。
12. **2026 年模型定价的时效性**：benchmark 成本基于 2026-08 的单价（`app-control-bench-report.mdx:9` 明确标注），模型价格变动频繁，成本结论需重算。
