# MERC-21 交付证据包（F-04 / F-05）

## 0. 提交与分支

| 项 | 值 |
| --- | --- |
| 任务分支 | `agent/frontenddev/merc-21` = `2c79940` |
| 交付落点 | `release/MERC-8-extension-steps-dnf-tw` = `2c79940`（**快进合入**，未 force-push） |
| 交付提交 | `a32ee3f` feat(frontend): 部署向导目标版本控件与确认摘要 + 提交载荷（F-04 / F-05）<br>`a387fc0` fix(frontend): 自检修正——下拉选项行高随两行内容撑开（ui-spec §8.2）<br>`2c79940` fix(frontend): 自检修正——部署配置单次读取、G2 值区 amber mono、徽标同源 |
| 基线 | `3ade4d5`（合入前 origin/release 的 head） |

变更文件（2 个）：

```
frontend/src/views/instance/deploy.vue          | 627 ++++++++++++++++++--
frontend/src/tests/views/deploy.test.js         | 622 ++++++++++++++++++++++++（新增）
```

## 1. 组件单测与构建（命令 + 完整输出见附件 01/02/03）

```
$ cd frontend && npm run test:run
 Test Files  30 passed (30)
      Tests  601 passed (601)

$ cd frontend && npx vitest run src/tests/views/deploy.test.js
 Test Files  1 passed (1)
      Tests  32 passed (32)

$ cd frontend && npm run build
 ✓ built in 12.23s
```

> 注：改造前的全量基线也曾出现 `src/tests/views/host.test.js > 点击测试应该调用测试连接API`
> 超时（10s 上限 + 并行负载），该文件单跑必过、与本票无关；本次全量跑恰好通过。

## 2. 后端契约实测（真后端 + 真插件，核对期）

启动与投放（MERC-14 的桩资产，AC-26 ② 口径：只投放到外置目录，不进发布物）：

```bash
cd backend && mvn -Pacceptance-assets -pl plugin-stub -am install -DskipTests   # BUILD SUCCESS
mkdir -p games plugins && cp plugin-stub/acceptance/games/stub.yml games/
cp plugin-stub/target/plugin-stub-1.0.0-SNAPSHOT.jar plugins/
cd .. && bash scripts/start-all.sh --skip-plugins   # 后端 8080 + 前端 3000 均就绪
```

登录后读契约（`admin/admin123` 种子账号，见 `db/data-sqlite.sql`）：

```
$ curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/games/140/deploy-config/docker-compose
versionCatalogState = AVAILABLE
versionCatalogReason = None
deployVersions count = 3
 - 1.0.0-default  | displayName= 1.0.0-default | isDefault= True  | steps= 1
 - 2.0.0-patched  | displayName= 桩改造版       | isDefault= False | steps= 2
 - 2.1.0-selective| displayName= 桩选装版       | isDefault= False | steps= 3

$ curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/games/41/deploy-config/docker-compose
  state = ABSENT | entries = 0 | reason = None            # dnf_tw（plugin-dnf-tw 未加载）

$ curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/pf4j/plugins
  pluginId=plugin-stub | state=STARTED | running=true
```

## 3. 真向导手工核对（Playwright 驱动真前后端，脚本一次性、跑完即删）

完整日志见附件 `merc21-manual-log.txt`；三张屏见附件 PNG。

```
=== W3/W4 桩游戏（gameId=140，目录 AVAILABLE）===
STEP 登录成功 → http://localhost:3000/workspace/overview
STEP 主机已选中（is-selected 计数=1）
  deploy-config 请求次数（本次进步骤 2 全程）= 1 /games/140/deploy-config/docker-compose
AC-01 控件出现（data-ext-version-select 可见）
  区块标题 = 目标版本
  aria-label=目标版本 节点数 = 2
  data-ext-version-select 节点标签 = DIV.el-select version-select
  P1 收起态值区 = 默认版本 1.0.0-default
  默认态徽标 = 不执行部署扩展步骤
  锚点 input aria-controls = el-id-2990-40
  锚点弹层 listbox 计数 = 1
  选项行 = [{"main":"默认版本","sub":"1.0.0-default","note":"不执行部署扩展"},
            {"main":"桩改造版","sub":"2.0.0-patched","note":"含 2 个部署扩展步骤"},
            {"main":"桩选装版","sub":"2.1.0-selective","note":"含 3 个部署扩展步骤"}]
  句一 非默认选项副标去重集合 = ["2.0.0-patched","2.1.0-selective"]
  句一 每元素恰出现 1 次 = true
  句二 默认项 = {"main":"默认版本","sub":"1.0.0-default","note":"不执行部署扩展"}
  选项数 = 3 （目录条目数 3）
  G 态徽标 = 将执行 2 个部署扩展步骤
  G 态步骤预览 = ["1/2 补丁替换 补丁替换 1 失败即终止","2/2 脚本执行 桩版本改造脚本 失败即终止"]
=== W1 dnf_tw（gameId=41，目录 ABSENT）===
AC-24① [data-ext-version-select] 计数 = 0
AC-24① #ext-version-section-title 计数 = 0
AC-24① body 文本含「默认版本」 = false
AC-24① body 文本含「目标版本」 = false
=== 浏览器控制台 error 计数 = 0 ===
```

说明：
- 预览第一行的 `补丁替换 1` 即 §6.2 的 label 缺省回退（桩声明里那条 PATCH 不写 `label`），
  界面上没有出现 `PATCH` / `SCRIPT` / `fatal` 字面量。
- dnf_tw 本期后端态是 `ABSENT`（plugin-dnf-tw 未加载）而非 `EMPTY`；两者同属 P1 假，
  向导渲染同一形态（§4.1 A/B 三态渲染相同），故 AC-24 ① 的界面结论可直接由本屏读出。
- 步骤 5 的摘要行在本环境**走不到**：进入步骤 3 需要过一次 SSH 端口检查（宿主机无 SSH），
  `canDeploy` 因而恒假，提交载荷也发不出去。摘要行六态与载荷构造改由组件单测覆盖
  （见 §1 的 `deploy.test.js`：`S1/S2 支①/支②/S3/H/G2` 六态 + `AC-02/AC-19/AC-24②` 载荷断言）。

## 4. AC-26 ② 回收核对（验收资产不留痕）

```
=== [回收前] target 外的桩 jar ===            ./plugins/plugin-stub-1.0.0-SNAPSHOT.jar
=== [回收前] games/ 下的 stub.yml ===         stub.yml

（删除外置投放物 + 停后端/前端）

=== [回收后] target 外的桩 jar ===            （无输出）
=== [回收后] games/ 下的 stub.yml ===         grep exit=1 → 非 0 即通过
=== [回收后] plugins/ 目录 ===                （plugins/ 已不存在）
```

停服：只按 PID 结束本次启动的后端（java PID 9684）与前端（node vite PID 16136），
并按 `multica daemon status --output json` 核对（daemon pid = 7052，未被波及）。
