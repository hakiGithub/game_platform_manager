#!/usr/bin/env bash
# V-10 / AC-15 的逐值比对：同一份采样文件在改造前 / 改造后两棵树上各跑一次，
# 比对五项比对面（顶层 progress 值序列 / 阶段序列 / 日志行 stage+level 原值 / 终态 / 状态转移）。
#
# 判据：两份 target/evidence/v10-no-extension.json 逐字节相同。
# 不含 CSS class 与图标名（design.md SUG-9）；不含 message 文本（§14.6 承载位分工表）。
#
# 用法：bash scripts/v10-compare-baseline.sh [基线提交，默认 origin/release/MERC-8-extension-steps-dnf-tw]
#
# 基线侧唯一的差别：DeployProgressSamplingBaselineTest.java 里 BASELINE-STRIP-START/END
# 之间的 mock 装配被整块删除（那是改造后新增的接线位，改造前的 DeployService 没有这个字段）。
# 删的是装配，不是判据，也不是采样点。
set -euo pipefail

BASE_REF="${1:-origin/release/MERC-8-extension-steps-dnf-tw}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASELINE_DIR="${TMPDIR:-/tmp}/merc22-v10-baseline"
SAMPLER="backend/core/src/test/java/com/gameplatform/service/DeployProgressSamplingBaselineTest.java"
EVIDENCE="backend/core/target/evidence/v10-no-extension.json"

cd "$REPO_ROOT"

echo "== 1/4 改造后采样 =="
( cd backend && mvn -q -pl core test -Dtest=DeployProgressSamplingBaselineTest > /dev/null )
cp "$EVIDENCE" "${EVIDENCE}.after"

echo "== 2/4 准备基线工作树（$BASE_REF） =="
if [ -d "$BASELINE_DIR" ]; then
  git worktree remove --force "$BASELINE_DIR" >/dev/null 2>&1 || rm -rf "$BASELINE_DIR"
fi
git worktree add --detach "$BASELINE_DIR" "$BASE_REF" >/dev/null

echo "== 3/4 基线侧采样（剥掉 BASELINE-STRIP 块后跑同一份文件） =="
sed '/BASELINE-STRIP-START/,/BASELINE-STRIP-END/d' "$SAMPLER" > "$BASELINE_DIR/$SAMPLER"
( cd "$BASELINE_DIR/backend" && mvn -q -pl core test -Dtest=DeployProgressSamplingBaselineTest > /dev/null )
cp "$BASELINE_DIR/$EVIDENCE" "${EVIDENCE}.before"

echo "== 4/4 逐字节比对 =="
if diff -u "${EVIDENCE}.before" "${EVIDENCE}.after"; then
  echo "V-10 无扩展分支：PASS —— 五项比对面逐值相同（AC-15 / KPI-04 的分母对象未被改动）"
  status=0
else
  echo "V-10 无扩展分支：FAIL —— 存在回归差异，逐条归因后再交验收"
  status=1
fi

git worktree remove --force "$BASELINE_DIR" >/dev/null 2>&1 || true
exit $status
