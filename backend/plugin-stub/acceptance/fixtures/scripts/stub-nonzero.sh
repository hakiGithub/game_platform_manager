#!/usr/bin/env bash
# 验收夹具：以「远端 URL + sha256」来源拉取的非致命脚本步骤正文。
#
# 固定以 3 退出，用来核对两件事（design.md §10 V-22 判据块 1）：
#   · 该步终态行 exitCode == 3（退出码有独立承载位，机械判据）
#   · 声明为非致命的步骤失败后，后续步骤照常执行、部署照常完成（PRD FR-18 / AC-10）
# 只打印，不读写宿主机任何路径 ⇒ 重复执行无副作用（BR-06 幂等）。
set -u
echo "STUB-FIXTURE-NONZERO stdout 行（承载 stdout 的 NOTE 行应包含本标记）" >&1
echo "STUB-FIXTURE-NONZERO stderr 行（承载 stderr 的 NOTE 行应包含本标记）" >&2
exit 3
