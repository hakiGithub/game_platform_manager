/**
 * 部署向导「目标版本」控件与摘要行 + 提交载荷（MERC-21 · F-04 / F-05）
 *
 * 权威文本：docs/ui/MERC-3/ui-spec.md @ 62b49a9（§4.1 A–J + F2 + G2 / §5 P1·P2 / §6.1 词面 /
 * §6.4 不渲染清单 / §7 X-02·X-03·X-10·X-11）+ design.md @ 790ccd6 §7.4 F-04/F-05、§16.4。
 *
 * AC-01 的机械核对按 ui-spec §6.1「两句口径 + 三条执行前提」：
 *   ① 先展开控件、展开后再取选项集合（不把「收起 ⇒ 空集」写成断言）；
 *   ② 作用域只取「该锚点控件对应 popper 内的 item」（锚点 data-ext-version-select +
 *      aria-label="目标版本"；弹层被传送到 <body> 下，且页面另有 el-select）；
 *   ③ 相等 = 去重集合相等且每元素恰出现 1 次，不是序列。
 */

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { nextTick } from "vue";
import * as ElementPlusIcons from "@element-plus/icons-vue";

// ── API mocks ────────────────────────────────────────────────────────────────
const mockGetHostList = vi.fn();
const mockGetHostResources = vi.fn();
const mockGetGameList = vi.fn();
const mockGetDeployConfig = vi.fn();
const mockCreateInstance = vi.fn();
const mockCheckEnvironment = vi.fn();
const mockCheckPort = vi.fn();

vi.mock("@/api/host", () => ({
  getHostList: (...a) => mockGetHostList(...a),
  getHostResources: (...a) => mockGetHostResources(...a),
}));
vi.mock("@/api/game", () => ({
  getGameList: (...a) => mockGetGameList(...a),
  getDeployConfig: (...a) => mockGetDeployConfig(...a),
}));
vi.mock("@/api/instance", () => ({
  createInstance: (...a) => mockCreateInstance(...a),
  checkEnvironment: (...a) => mockCheckEnvironment(...a),
  checkPort: (...a) => mockCheckPort(...a),
  getDeployProgress: vi.fn(),
}));

const mockPush = vi.fn();
vi.mock("vue-router", () => ({
  useRouter: () => ({ push: mockPush }),
  useRoute: () => ({ query: { gameId: "7" } }),
}));

import { ElMessage } from "element-plus";
import Deploy from "@/views/instance/deploy.vue";

// ── 目录条目夹具（VersionEntryVO 形状，design §16.4）──────────────────────────
function entry(versionId, { displayName, isDefault = false, steps = [] } = {}) {
  return {
    versionId,
    displayName: displayName ?? versionId,
    isDefault,
    stepSummary: steps.map((s, i) => ({
      index: i + 1,
      label: s.label ?? null,
      type: s.type,
      fatal: s.fatal !== false,
    })),
  };
}
const PATCH = { type: "PATCH" };
const SCRIPT = { type: "SCRIPT" };

// 支①：目录存在 default = true 条目（默认条目带一条脚本 = 合法但永不执行的哨兵）
const CATALOG_DEFAULT = [
  entry("def-1", { isDefault: true, steps: [SCRIPT] }),
  entry("ver-a", {
    displayName: "展示名 A",
    steps: [PATCH, { ...SCRIPT, fatal: false }, PATCH],
  }),
  entry("ver-b", { steps: [SCRIPT] }),
];
// 支②：目录没有任何 default = true 条目（§8.1 校验＝「至多一条」⇒ 0 条合法）
const CATALOG_NODEFAULT = [
  entry("ver-a", { displayName: "展示名 A", steps: [PATCH, SCRIPT] }),
  entry("ver-b", { steps: [SCRIPT] }),
  entry("ver-c", { steps: [PATCH, SCRIPT] }),
];
// X-10：default = true 条目声明在非首位 ⇒ 渲染时上提
const CATALOG_UPLIFT = [
  entry("ver-a", { displayName: "展示名 A", steps: [PATCH] }),
  entry("ver-b", { steps: [SCRIPT] }),
  entry("def-z", { isDefault: true, steps: [SCRIPT] }),
];
// 单值目录（只有默认条目）
const CATALOG_SINGLE = [entry("only-1", { isDefault: true, steps: [PATCH] })];
// 已选条目两栈皆空 ⇒ 步数词面走「不执行」档（W4b / §6.1 0 值规则）
const CATALOG_NOSTEPS = [
  entry("def-1", { isDefault: true }),
  entry("ver-empty", { displayName: "空步骤条目", steps: [] }),
];

const GAME = {
  id: 7,
  gameCode: "stub",
  gameName: "验收资产",
  supportedDeployTypes: ["docker-compose"],
  defaultPort: 27015,
  deployConfig: { defaultPorts: { game: 27015 } },
};
const GAME_BOTH_TYPES = {
  ...GAME,
  supportedDeployTypes: ["docker-compose", "linuxgsm-docker"],
};
const HOST = {
  id: 1,
  name: "node-1",
  ip: "10.0.0.1",
  status: 1,
  resources: { cpu: { cores: 4 }, memory: { total: 8 }, disk: { total: 100 } },
};

// ── 挂载与渲染探针 ───────────────────────────────────────────────────────────
async function mountDeploy({ catalog = [], game = GAME, pending = false } = {}) {
  mockGetGameList.mockResolvedValue([game]);
  if (pending) {
    // 状态 D：目录 GET 挂起不返回（§4.1 D / D-01b / X-01）。
    // 传 true = 永不落定；传一个 Promise = 由用例自己控制落定时机。
    mockGetDeployConfig.mockReturnValue(
      pending === true ? new Promise(() => {}) : pending,
    );
  } else if (catalog instanceof Error) {
    mockGetDeployConfig.mockRejectedValue(catalog);
  } else {
    // versionCatalogState 按 design §16.4 原样喂入：前端**有意**不读它（三态渲染相同），
    // P1 谓词只由 deployVersions.length 决定——这里保留它只为让桩响应保持真实形状。
    mockGetDeployConfig.mockResolvedValue({
      deployVersions: catalog,
      versionCatalogState: catalog.length ? "AVAILABLE" : "EMPTY",
      variables: [],
    });
  }
  const wrapper = mount(Deploy, {
    attachTo: document.body,
    global: { components: { ...ElementPlusIcons } },
  });
  await flushPromises();
  return wrapper;
}

function versionAnchor() {
  return document.querySelector("[data-ext-version-select]");
}
/** 目标版本控件对应的组件实例：`:loading` 没有 DOM 落点，只能在 prop 层核对。 */
function versionSelectComponent(wrapper) {
  return wrapper
    .findAllComponents({ name: "ElSelect" })
    .find((c) => c.attributes("data-ext-version-select") !== undefined);
}
/** §4.1 D 支①「禁用」的 DOM 落点：根元素只有 `el-select`，`is-disabled` 在内部 wrapper 上。 */
function versionSelectDisabled() {
  return (
    versionAnchor()
      ?.querySelector(".el-select__wrapper")
      ?.classList.contains("is-disabled") ?? false
  );
}
function sectionTitle() {
  return document.getElementById("ext-version-section-title");
}
/** §6.1 前提① + 前提②：先展开，再按该锚点控件对应 popper 内的 item 取集合。 */
async function expandVersionSelect() {
  const wrapperEl = versionAnchor()?.querySelector(".el-select__wrapper");
  if (!wrapperEl) return [];
  wrapperEl.dispatchEvent(new MouseEvent("click", { bubbles: true }));
  await nextTick();
  await new Promise((r) => setTimeout(r, 30));
  const anchor = document.querySelector('[aria-label="目标版本"]');
  const listId = anchor?.getAttribute("aria-controls");
  const list = listId ? document.getElementById(listId) : null;
  if (!list) return [];
  return Array.from(list.querySelectorAll(".el-select-dropdown__item"));
}
function optionMain(item) {
  return item.querySelector(".version-option-text")?.textContent?.trim() ?? "";
}
function optionSub(item) {
  return item.querySelector(".version-id-sub")?.textContent?.trim() ?? null;
}
function optionNote(item) {
  return item.querySelector(".version-option-note")?.textContent?.trim() ?? "";
}
/** 目标版本摘要行（步骤 5）；行不存在时返回 null。 */
function summaryRow() {
  const cell = document.querySelector(".version-summary");
  return cell ? cell.textContent.replace(/\s+/g, " ").trim() : null;
}
function summaryBadge() {
  const el = document.querySelector(".version-summary-badge");
  return el ? el.textContent.trim() : null;
}

/** 满足 canDeploy（X-06：仍只看 envCheckPassed）后真正提交一次。 */
async function submit(wrapper) {
  wrapper.vm.selectedHost = HOST;
  wrapper.vm.deployForm.name = wrapper.vm.deployForm.name || "stub-1";
  wrapper.vm.envCheckPassed = true;
  await flushPromises();
  await wrapper.vm.handleDeploy();
  await flushPromises();
}
function submittedPayload(index = 0) {
  return mockCreateInstance.mock.calls[index][0];
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.spyOn(ElMessage, "info").mockImplementation(() => {});
  mockGetHostList.mockResolvedValue({ records: [HOST] });
  mockGetHostResources.mockResolvedValue(HOST.resources);
  mockGetGameList.mockResolvedValue([GAME]);
  mockGetDeployConfig.mockResolvedValue({
    deployVersions: [],
    versionCatalogState: "EMPTY",
    variables: [],
  });
  mockCheckPort.mockResolvedValue({ available: true });
  mockCheckEnvironment.mockResolvedValue({ passed: true, checks: [] });
  mockCreateInstance.mockResolvedValue({ id: 1 });
});

afterEach(() => {
  document.body.innerHTML = "";
  vi.restoreAllMocks();
});

// ════════════════════════════════════════════════════════════════════════════
describe("F-04 步骤 2 目标版本控件 · P1 谓词（ui-spec §5 / §6.4）", () => {
  it("目录可用（条目数 ≥ 1）⇒ 区块与控件渲染，两个稳定锚点齐备（AC-01 前提②）", async () => {
    await mountDeploy({ catalog: CATALOG_DEFAULT });
    expect(sectionTitle()?.textContent?.trim()).toBe("目标版本");
    const anchor = versionAnchor();
    expect(anchor).toBeTruthy();
    expect(anchor.tagName).toBe("DIV");
    expect(document.querySelector('[aria-label="目标版本"]')).toBeTruthy();
    // 锚点归属可反查 popper：不得靠「向导容器内」这一作用域取（弹层传送到 <body> 下）
    const input = document.querySelector('[aria-label="目标版本"]');
    expect(input.getAttribute("aria-controls")).toBeTruthy();
  });

  it("目录读取成功但条目数 = 0（dnf-tw 缺口期 EMPTY）⇒ 整块不渲染（AC-24 ①）", async () => {
    await mountDeploy({ catalog: [] });
    expect(versionAnchor()).toBeNull();
    expect(sectionTitle()).toBeNull();
    expect(document.querySelector(".version-preview")).toBeNull();
  });

  it("目录读取失败 ⇒ 与前项同形，且不把它当「声明不合法」输出提示（RISK-13）", async () => {
    const wrapper = await mountDeploy({ catalog: new Error("boom") });
    expect(versionAnchor()).toBeNull();
    expect(sectionTitle()).toBeNull();
    expect(wrapper.html()).not.toContain("不合法");
  });

  it("单值目录不隐藏控件（§2-①）", async () => {
    await mountDeploy({ catalog: CATALOG_SINGLE });
    expect(versionAnchor()).toBeTruthy();
    const items = await expandVersionSelect();
    expect(items).toHaveLength(1);
    expect(optionMain(items[0])).toBe("默认版本");
    expect(optionSub(items[0])).toBe("only-1");
  });
});

describe("F-04 状态 D（§4.1 D / D-01b / X-01：目录读取未返回）", () => {
  it("三支齐备（禁用 + loading + 登记词面占位），且读取中不渲染选择值 /「默认版本」", async () => {
    const wrapper = await mountDeploy({ pending: true });
    const anchor = versionAnchor();
    // versionBlockVisible = P1 ∨ 读取中：读取中该区块仍占位渲染（D-01b）
    expect(anchor).toBeTruthy();
    expect(sectionTitle()?.textContent?.trim()).toBe("目标版本");
    // 支① disabled（§4.1 D 逐字：`el-select` 禁用）
    expect(versionSelectDisabled()).toBe(true);
    expect(anchor.querySelector("input").disabled).toBe(true);
    // 支② loading（`:loading` 只影响下拉，无 DOM 落点 ⇒ 判到 prop 层）
    expect(versionSelectComponent(wrapper).props("loading")).toBe(true);
    // 支③ 占位文案 = §4.1 D 登记词面逐字，落在组件自己的 placeholder 节点内
    const placeholderEl = anchor.querySelector(".el-select__placeholder");
    expect(placeholderEl?.textContent?.trim()).toBe("版本目录读取中…");

    // 读取中不得渲染选择值 /「默认版本」（D-01b：不得出现空选项的假定性呈现）
    // §8.2 两段式值区整体让位给占位，不先亮出哨位值
    expect(anchor.querySelector(".version-value")).toBeNull();
    expect(anchor.textContent).not.toContain("默认版本");
    expect(anchor.textContent).not.toContain("::default::");
    // 值区文案就是唯一那句登记词面，没有第二个取值来源
    expect(anchor.textContent.replace(/\s+/g, "")).toContain("版本目录读取中…");
  });

  it("读取落定后回到稳态：占位让位、恢复「默认版本」+ 默认条目 versionId 的两段式值区", async () => {
    let settle;
    const pendingConfig = new Promise((resolve) => {
      settle = resolve;
    });
    const wrapper = await mountDeploy({ pending: pendingConfig });
    expect(versionAnchor().querySelector(".version-value")).toBeNull();

    settle({
      deployVersions: CATALOG_DEFAULT,
      versionCatalogState: "AVAILABLE",
      variables: [],
    });
    await flushPromises();

    const valueArea = versionAnchor().querySelector(".version-value");
    expect(valueArea.textContent).toContain("默认版本");
    expect(valueArea.querySelector(".version-id-sub").textContent.trim()).toBe(
      "def-1",
    );
    expect(versionSelectComponent(wrapper).props("loading")).toBe(false);
    expect(versionSelectDisabled()).toBe(false);
  });
});

describe("F-04 控件交互（现由 `:model-value` + `@update:model-value` 承载 v-model 语义）", () => {
  it("展开后点选非默认条目 ⇒ 选择值写回，值区与步骤 5 摘要随之改口径", async () => {
    const wrapper = await mountDeploy({ catalog: CATALOG_DEFAULT });
    expect(versionAnchor().querySelector(".version-value").textContent).toContain(
      "默认版本",
    );

    const items = await expandVersionSelect();
    const picked = items.find((i) => optionMain(i) === "展示名 A");
    picked.dispatchEvent(new MouseEvent("click", { bubbles: true }));
    await flushPromises();

    expect(wrapper.vm.selectedVersionId).toBe("ver-a");
    const valueArea = versionAnchor().querySelector(".version-value");
    expect(valueArea.textContent).toContain("展示名 A");
    expect(valueArea.querySelector(".version-id-sub").textContent.trim()).toBe(
      "ver-a",
    );
    expect(summaryRow()).toContain("ver-a");
    expect(summaryBadge()).toBe("将执行 3 个部署扩展步骤");
  });
});

describe("F-04 选项文本 / 副标 / 排序（§6.1 AC-01 两句口径 · X-10）", () => {
  it("句一：非默认选项的 versionId 副标集合 ≡ 目录非默认条目的 versionId 集合（去重、各恰 1 次）", async () => {
    await mountDeploy({ catalog: CATALOG_DEFAULT });
    const items = await expandVersionSelect();
    // 非默认选项的机械锚点 = 主文本不为「默认版本」的那些选项行（内容式，§6.1 S-16）
    const nonDefault = items.filter((i) => optionMain(i) !== "默认版本");
    const seen = nonDefault.map(optionSub);
    expect(new Set(seen).size).toBe(seen.length); // 每元素恰出现 1 次
    expect(new Set(seen)).toEqual(
      new Set(CATALOG_DEFAULT.filter((e) => !e.isDefault).map((e) => e.versionId)),
    );
    // displayName 生效时主文本与 versionId 不同值，副标仍恒为该条目的 versionId
    expect(optionMain(nonDefault[0])).toBe("展示名 A");
    expect(optionSub(nonDefault[0])).toBe("ver-a");
  });

  it("句一：displayName 缺省时主文本与副标同值，照常双行、不合并不省略", async () => {
    await mountDeploy({ catalog: CATALOG_DEFAULT });
    const items = await expandVersionSelect();
    const verB = items.find((i) => optionMain(i) === "ver-b");
    expect(verB).toBeTruthy();
    expect(optionSub(verB)).toBe("ver-b");
  });

  it("句二（支①）：首位「默认版本」+ 该默认条目的 versionId 副标；displayName 被有意吞掉", async () => {
    await mountDeploy({
      catalog: [
        entry("def-1", {
          displayName: "不该出现",
          isDefault: true,
          steps: [SCRIPT],
        }),
        entry("ver-a", { steps: [PATCH] }),
      ],
    });
    const items = await expandVersionSelect();
    expect(optionMain(items[0])).toBe("默认版本");
    expect(optionSub(items[0])).toBe("def-1");
    expect(items[0].textContent).not.toContain("不该出现");
  });

  it("句二（支②）：目录无 default = true 条目 ⇒ 首位合成项只显四字、不渲染副标、不编造字符串", async () => {
    await mountDeploy({ catalog: CATALOG_NODEFAULT });
    const items = await expandVersionSelect();
    expect(items).toHaveLength(CATALOG_NODEFAULT.length + 1);
    expect(optionMain(items[0])).toBe("默认版本");
    expect(optionSub(items[0])).toBeNull();
    expect(new Set(items.slice(1).map(optionSub))).toEqual(
      new Set(CATALOG_NODEFAULT.map((e) => e.versionId)),
    );
    const text = document.body.textContent;
    for (const banned of ["沿用模板版本", "最新版", "推荐版"]) {
      expect(text).not.toContain(banned);
    }
  });

  it("X-10：「默认版本」恒为第一项；default 条目声明在非首位时渲染层上提，其余保持声明序", async () => {
    await mountDeploy({ catalog: CATALOG_UPLIFT });
    const items = await expandVersionSelect();
    expect(items.map(optionMain)).toEqual(["默认版本", "展示名 A", "ver-b"]);
    expect(optionSub(items[0])).toBe("def-z");
  });

  it("AC-01 前提②：集合只按锚点控件的 popper 取；全量扫副标类名会把取值区/摘要重复计入", async () => {
    await mountDeploy({ catalog: CATALOG_DEFAULT });
    const scopedSubs = (await expandVersionSelect()).map(optionSub).filter(Boolean);
    expect(scopedSubs).toHaveLength(CATALOG_DEFAULT.length);
    // 全量扫（含取值区与步骤 5 摘要）会把同一串重复计入 ⇒ 必须按锚点归属取
    expect(document.querySelectorAll(".version-id-sub").length).toBeGreaterThan(
      scopedSubs.length,
    );
    // 收起态值区（§8.2 两段式）不在 popper 内
    expect(
      versionAnchor().querySelector(".version-value .version-id-sub").textContent.trim(),
    ).toBe("def-1");
  });
});

describe("F-04 步数徽标与选项行右侧步数（§6.1 · 0 值一律走「不执行」档）", () => {
  it("目录内非默认条目 3 步 ⇒ 选项行「含 3 个部署扩展步骤」", async () => {
    await mountDeploy({ catalog: CATALOG_DEFAULT });
    const items = await expandVersionSelect();
    const picked = items.find((i) => optionMain(i) === "展示名 A");
    expect(optionNote(picked)).toBe("含 3 个部署扩展步骤");
  });

  it("停在默认版本 ⇒ 徽标走「不执行」档（默认条目即使声明了步骤也不执行）", async () => {
    await mountDeploy({ catalog: CATALOG_DEFAULT });
    const badge = versionAnchor().parentElement.querySelector(".version-badge");
    expect(badge.textContent.trim()).toBe("不执行部署扩展步骤");
  });

  it("选到非默认条目 ⇒ 徽标「将执行 N 个部署扩展步骤」", async () => {
    const wrapper = await mountDeploy({ catalog: CATALOG_DEFAULT });
    wrapper.vm.selectedVersionId = "ver-a";
    await flushPromises();
    const badge = document.querySelector(".version-badge");
    expect(badge.textContent.trim()).toBe("将执行 3 个部署扩展步骤");
  });

  it("W4b：已选条目两栈皆空 ⇒ 徽标与选项行一律「不执行」档、预览不渲染、不出现 0 值提示", async () => {
    const wrapper = await mountDeploy({ catalog: CATALOG_NOSTEPS });
    wrapper.vm.selectedVersionId = "ver-empty";
    await flushPromises();
    expect(document.querySelector(".version-badge").textContent.trim()).toBe(
      "不执行部署扩展步骤",
    );
    expect(document.querySelector(".version-preview")).toBeNull();
    const items = await expandVersionSelect();
    expect(optionNote(items.find((i) => optionMain(i) === "空步骤条目"))).toBe(
      "不执行部署扩展",
    );
    expect(wrapper.html()).not.toContain("0 个");
  });
});

describe("F-04 步骤预览（X-03 / X-04 · §6.2 label 缺省回退）", () => {
  it("按声明序渲染：序号/总数 + 种类中文词 + label 缺省回退 + 致命性词面", async () => {
    const wrapper = await mountDeploy({
      catalog: [
        entry("def-1", { isDefault: true }),
        entry("ver-a", {
          steps: [
            { type: "PATCH", label: "替换版本标记" },
            { type: "SCRIPT", fatal: false },
          ],
        }),
      ],
    });
    wrapper.vm.selectedVersionId = "ver-a";
    await flushPromises();

    const preview = document.querySelector(".version-preview");
    expect(preview).toBeTruthy();
    const rows = Array.from(preview.querySelectorAll("li"));
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toContain("1/2");
    expect(rows[0].textContent).toContain("补丁替换");
    expect(rows[0].textContent).toContain("替换版本标记");
    expect(rows[0].textContent).toContain("失败即终止");
    expect(rows[1].textContent).toContain("2/2");
    expect(rows[1].textContent).toContain("脚本执行");
    expect(rows[1].textContent).toContain("脚本执行 2"); // label 缺省回退用「种类 + 序号」
    expect(rows[1].textContent).toContain("失败可继续");
    for (const banned of ["PATCH", "SCRIPT", "fatal"]) {
      expect(preview.textContent).not.toContain(banned);
    }
  });

  it("停在默认版本（S2）⇒ 不渲染预览", async () => {
    await mountDeploy({ catalog: CATALOG_DEFAULT });
    expect(document.querySelector(".version-preview")).toBeNull();
  });
});

describe("F-04 / §7 X-02 · 切换部署方式重取目录与回落", () => {
  it("新目录不含旧选择 ⇒ 回落「默认版本」并提示，不静默改选", async () => {
    const wrapper = await mountDeploy({
      catalog: CATALOG_DEFAULT,
      game: GAME_BOTH_TYPES,
    });
    wrapper.vm.selectedVersionId = "ver-a";
    await flushPromises();
    expect(wrapper.vm.selectedVersionId).toBe("ver-a");

    mockGetDeployConfig.mockResolvedValue({
      deployVersions: CATALOG_SINGLE,
      versionCatalogState: "AVAILABLE",
      variables: [],
    });
    wrapper.vm.selectedDeployMethod = "linuxgsm-docker";
    await flushPromises();

    expect(wrapper.vm.selectedVersionId).toBe("::default::");
    expect(ElMessage.info).toHaveBeenCalledWith(
      "部署方式已切换，目标版本已重置为默认版本",
    );
  });

  it("重取后新版号仍存在 ⇒ 保留选择、不提示", async () => {
    const wrapper = await mountDeploy({
      catalog: CATALOG_UPLIFT,
      game: GAME_BOTH_TYPES,
    });
    wrapper.vm.selectedVersionId = "ver-b";
    await flushPromises();

    wrapper.vm.selectedDeployMethod = "linuxgsm-docker";
    await flushPromises();

    expect(wrapper.vm.selectedVersionId).toBe("ver-b");
    expect(ElMessage.info).not.toHaveBeenCalled();
  });
});

describe("F-04 / §7 X-01 · 重叠读取的序列号守卫", () => {
  it("快速连换游戏：先返回的过期响应不落地，loading 由最新那次请求的落定决定", async () => {
    const GAME_B = {
      ...GAME,
      id: 8,
      gameCode: "stub-b",
      gameName: "验收资产 B",
    };
    mockGetGameList.mockResolvedValue([GAME, GAME_B]);
    const settled = [];
    mockGetDeployConfig.mockImplementation(
      () => new Promise((resolve) => settled.push(resolve)),
    );
    const wrapper = mount(Deploy, {
      attachTo: document.body,
      global: { components: { ...ElementPlusIcons } },
    });
    // route.query.gameId = "7" ⇒ 自动选定 GAME ⇒ 第 1 次读取（在途）
    await flushPromises();
    expect(settled).toHaveLength(1);

    wrapper.vm.selectGame(GAME_B); // 连换游戏 ⇒ 第 2 次读取（在途）
    await flushPromises();
    expect(settled).toHaveLength(2);

    // 过期的那次先返回：目录 / 变量 / loading 都不得被它写
    settled[0]({
      deployVersions: CATALOG_DEFAULT,
      versionCatalogState: "AVAILABLE",
      variables: [{ name: "STALE_VAR", defaultValue: "1" }],
    });
    await flushPromises();
    expect(wrapper.vm.deployVersions).toHaveLength(0);
    expect(wrapper.vm.deployVariables).toHaveLength(0);
    expect(wrapper.vm.deployVariablesValues.STALE_VAR).toBeUndefined();
    expect(wrapper.vm.loadingDeployConfig).toBe(true);
    // X-01：未返回前不渲染选项列表（状态 D）在重叠读取下同样成立
    expect(versionAnchor().querySelector(".version-value")).toBeNull();

    // 最新那次落定：这一次才写目录与变量、才收 loading
    settled[1]({
      deployVersions: CATALOG_SINGLE,
      versionCatalogState: "AVAILABLE",
      variables: [{ name: "LATEST_VAR", defaultValue: "2" }],
    });
    await flushPromises();
    expect(wrapper.vm.deployVersions.map((e) => e.versionId)).toEqual(["only-1"]);
    expect(wrapper.vm.deployVariables.map((v) => v.name)).toEqual(["LATEST_VAR"]);
    expect(wrapper.vm.deployVariablesValues.LATEST_VAR).toBe("2");
    expect(wrapper.vm.loadingDeployConfig).toBe(false);
    expect(
      versionAnchor().querySelector(".version-value").textContent,
    ).toContain("默认版本");
  });

  it("在途读取被切到「不产生读取」的部署方式取代 ⇒ loading 当场收掉，不停留在状态 D", async () => {
    const GAME_NATIVE = {
      ...GAME,
      id: 9,
      gameCode: "stub-native",
      gameName: "验收资产 native",
      supportedDeployTypes: ["docker-compose", "native"],
    };
    mockGetGameList.mockResolvedValue([GAME_NATIVE]);
    let staleSettle;
    mockGetDeployConfig.mockReturnValue(
      new Promise((resolve) => {
        staleSettle = resolve;
      }),
    );
    const wrapper = mount(Deploy, {
      attachTo: document.body,
      global: { components: { ...ElementPlusIcons } },
    });
    await flushPromises();
    wrapper.vm.selectGame(GAME_NATIVE); // compose 类型 ⇒ 发起读取（在途）
    await flushPromises();
    expect(wrapper.vm.loadingDeployConfig).toBe(true);

    // 切到非 compose 类型：本次（最新）请求不发起 GET，loading 必须由它收掉
    wrapper.vm.selectedDeployMethod = "native";
    await flushPromises();
    expect(wrapper.vm.loadingDeployConfig).toBe(false);

    // 被取代的那次响应随后返回：已被判过期，不得把 loading 再翻回去
    staleSettle({
      deployVersions: CATALOG_DEFAULT,
      versionCatalogState: "AVAILABLE",
      variables: [],
    });
    await flushPromises();
    expect(wrapper.vm.deployVersions).toHaveLength(0);
    expect(wrapper.vm.loadingDeployConfig).toBe(false);
    expect(versionAnchor()).toBeNull(); // P1 假且非读取中 ⇒ 整块不渲染
  });
});

describe("F-04 · G2（§4.1 G2 / §7 X-11：目录可用但载荷 versionId 不在条目中）", () => {
  async function intoG2() {
    const wrapper = await mountDeploy({ catalog: CATALOG_DEFAULT });
    wrapper.vm.selectedVersionId = "off-catalog-9";
    await flushPromises();
    return wrapper;
  }

  it("控件仍渲染；目录内选项集合不因载荷值而增删（AC-01 句一不受影响）", async () => {
    await intoG2();
    expect(versionAnchor()).toBeTruthy();
    const items = await expandVersionSelect();
    expect(items).toHaveLength(CATALOG_DEFAULT.length);
    expect(items.some((i) => optionSub(i) === "off-catalog-9")).toBe(false);
    const nonDefault = items.filter((i) => optionMain(i) !== "默认版本");
    expect(new Set(nonDefault.map(optionSub))).toEqual(
      new Set(CATALOG_DEFAULT.filter((e) => !e.isDefault).map((e) => e.versionId)),
    );
  });

  it("值区显示该 versionId 本体；「不在当前可选版本中」与其同段可见（承载机制 (a)）", async () => {
    await intoG2();
    const valueArea = versionAnchor().querySelector(".version-value");
    expect(valueArea.textContent).toContain("off-catalog-9");
    // 不给该值渲染 versionId mono 副标（值区只有那一个值，不把异常值伪装成正常取值）
    expect(valueArea.querySelector(".version-id-sub")).toBeNull();

    const desc = document.querySelector(".version-off-catalog");
    expect(desc).toBeTruthy();
    expect(desc.textContent).toContain("off-catalog-9");
    expect(desc.textContent).toContain("不在当前可选版本中");
    expect(desc.querySelector(".version-off-icon")).toBeTruthy();
  });

  it("不回落「默认版本」、不新增合成选项、徽标与步骤预览都不渲染", async () => {
    const wrapper = await intoG2();
    expect(wrapper.vm.selectedVersionId).toBe("off-catalog-9");
    expect(document.querySelector(".version-badge")).toBeNull();
    expect(document.querySelector(".version-preview")).toBeNull();
    const items = await expandVersionSelect();
    expect(items.filter((i) => optionMain(i) === "默认版本")).toHaveLength(1);
    expect(items.filter((i) => optionSub(i) === "off-catalog-9")).toHaveLength(0);
  });

  it("步骤 5 摘要同态：versionId + 「沿用实例配置」+「不在当前可选版本中」，不回落不省略", async () => {
    await intoG2();
    const row = summaryRow();
    expect(row).toContain("off-catalog-9");
    expect(row).toContain("沿用实例配置");
    expect(row).toContain("不在当前可选版本中");
    expect(row).not.toContain("默认版本");
  });

  it("提交载荷原样携带该键（本票只判到载荷构造层；BR-12 端到端归 MERC-24）", async () => {
    const wrapper = await intoG2();
    await submit(wrapper);
    expect(submittedPayload().configInfo.deployVersion).toBe("off-catalog-9");
  });
});

describe("F-04 步骤 5 摘要行（§5 P2 六态取值）", () => {
  it("S1（P1 假且无键）⇒ 不渲染该行、也不显示「默认版本」（W7）", async () => {
    await mountDeploy({ catalog: [] });
    expect(summaryRow()).toBeNull();
    expect(document.body.textContent).not.toContain("默认版本");
  });

  it("S2 支①（无键 + 目录有 default 条目）⇒ 「默认版本」+ 该条目的 versionId；无步骤数徽标（W8b）", async () => {
    await mountDeploy({ catalog: CATALOG_DEFAULT });
    const row = summaryRow();
    expect(row).toContain("默认版本");
    expect(row).toContain("def-1");
    expect(summaryBadge()).toBeNull();
  });

  it("S2 支②（无键 + 无 default 条目）⇒「默认版本」四字、不渲染副标（W8c）", async () => {
    await mountDeploy({ catalog: CATALOG_NODEFAULT });
    const row = summaryRow();
    expect(row).toContain("默认版本");
    expect(document.querySelector(".version-summary .version-id-sub")).toBeNull();
    expect(summaryBadge()).toBeNull();
  });

  it("S3（目录可用 + 选非默认）⇒ 展示名 + versionId 副标 + 步骤数徽标（W8）", async () => {
    const wrapper = await mountDeploy({ catalog: CATALOG_DEFAULT });
    wrapper.vm.selectedVersionId = "ver-a";
    await flushPromises();
    const row = summaryRow();
    expect(row).toContain("展示名 A");
    expect(row).toContain("ver-a");
    expect(summaryBadge()).toBe("将执行 3 个部署扩展步骤");
  });

  it("H（目录不可用 + 键既存）⇒ 摘要行渲染、报 versionId + 「沿用实例配置」，控件不渲染（W9）", async () => {
    const wrapper = await mountDeploy({ catalog: [] });
    // 既存键的来源在本向导内不存在（design §14.10）：此处直接构造该选择值，判到取值层
    wrapper.vm.selectedVersionId = "inherited-3";
    await flushPromises();
    expect(versionAnchor()).toBeNull();
    const row = summaryRow();
    expect(row).toContain("inherited-3");
    expect(row).toContain("沿用实例配置");
    expect(row).not.toContain("默认版本");
    expect(row).not.toContain("不在当前可选版本中");
  });
});

describe("F-05 提交载荷（AC-02 / AC-19 前端半 / 只多这一个键）", () => {
  it("AC-02：停在默认版本 ⇒ 载荷不存在 deployVersion 键，其余键与现状一致", async () => {
    const wrapper = await mountDeploy({ catalog: CATALOG_DEFAULT });
    wrapper.vm.deployForm.name = "stub-1";
    wrapper.vm.deployForm.deployPath = "~/games/stub";
    await submit(wrapper);

    const payload = submittedPayload();
    expect("deployVersion" in payload.configInfo).toBe(false);
    expect(payload.configInfo.gameCode).toBe("stub");
    expect(payload.configInfo.autoRestart).toBe(1);
    expect(payload.configInfo.mountHostCerts).toBe(false);
    expect(payload.configInfo).toHaveProperty("resources");
    expect(payload.deployType).toBe("docker-compose");
  });

  it("AC-19 前端半：键值精确等于所选条目的 versionId（displayName 不进载荷）", async () => {
    const wrapper = await mountDeploy({ catalog: CATALOG_DEFAULT });
    wrapper.vm.deployForm.name = "stub-1";
    wrapper.vm.selectedVersionId = "ver-a";
    await flushPromises();
    await submit(wrapper);
    expect(submittedPayload().configInfo.deployVersion).toBe("ver-a");
  });

  it("AC-24 ①②：dnf-tw 形态（目录 EMPTY）⇒ 无控件、载荷无该键、界面无示例版号/URL/路径", async () => {
    const wrapper = await mountDeploy({ catalog: [] });
    wrapper.vm.deployForm.name = "stub-1";
    await submit(wrapper);
    expect("deployVersion" in submittedPayload().configInfo).toBe(false);
    expect(versionAnchor()).toBeNull();
    const html = wrapper.html();
    for (const banned of [
      "data-ext-version-select",
      "〈versionId",
      "〈展示名",
      "〈默认条目",
    ]) {
      expect(html).not.toContain(banned);
    }
    // 界面文字里不得出现示例版号 / 补丁 URL / 目标路径（SVG 命名空间不算界面文字）
    const text = wrapper.text();
    for (const banned of ["http://", "https://", "/opt", "/data/", "/var/lib"]) {
      expect(text).not.toContain(banned);
    }
  });

  it("载荷与现状的差异只多 deployVersion 这一个键", async () => {
    const wrapper = await mountDeploy({ catalog: CATALOG_DEFAULT });
    wrapper.vm.deployForm.name = "stub-1";
    await submit(wrapper); // 默认版本
    wrapper.vm.selectedVersionId = "ver-a";
    await flushPromises();
    await submit(wrapper); // 非默认

    const base = submittedPayload(0).configInfo;
    const withVersion = submittedPayload(1).configInfo;
    expect("deployVersion" in base).toBe(false);
    expect(withVersion.deployVersion).toBe("ver-a");
    expect(
      Object.keys(withVersion)
        .sort()
        .filter((k) => !(k in base)),
    ).toEqual(["deployVersion"]);
    expect(
      Object.keys(base)
        .sort()
        .filter((k) => !(k in withVersion)),
    ).toEqual([]);
  });
});
