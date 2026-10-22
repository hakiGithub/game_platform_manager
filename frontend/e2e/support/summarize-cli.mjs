// 命令行入口：给不经过 runner.mjs 的执行路径（容器化 e2e-docker-pw.sh）落盘结果。
//
// 用法：node e2e/support/summarize-cli.mjs --mode docker --executor wsl --started-at 123 --exit-code 1
//
// 退出码永远是 0：summary 写不出来不能改变被测结果。
import { DEFAULT_JSON_REPORT, finalizeSummary } from "./run-summary.mjs";

function arg(name, fallback) {
  const idx = process.argv.indexOf(`--${name}`);
  return idx >= 0 ? process.argv[idx + 1] : fallback;
}

const startedAt = Number(arg("started-at", Date.now()));
const exitCode = Number(arg("exit-code", 0));
finalizeSummary({
  reports: [{ jsonPath: arg("json", DEFAULT_JSON_REPORT) }],
  mode: arg("mode", "docker"),
  executor: arg("executor", "wsl"),
  startedAt,
  exitCode,
});
process.exitCode = 0;
