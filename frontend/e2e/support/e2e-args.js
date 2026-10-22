/**
 * 从 runner 进程参数中分离自身的模式开关与 Playwright 参数。
 *
 * npm 会在调用脚本前消费 `--`，因此 runner 不能依赖 process.argv 中还存在
 * 分隔符。这里只把明确属于 runner 的 `--attach` / `--round` / `--group <name>`
 * 视为开关，其余参数（包括 spec 路径和 `--reporter=list`）全部透传给 Playwright。
 *
 * `--group` 支持 `--group smoke` 与 `--group=smoke` 两种写法；缺值时不吞掉后面的
 * Playwright 参数，交由 runner 报错。
 */
export function parseE2eArgs(argv = []) {
  const attach = argv.includes("--attach");
  const round = argv.includes("--round");
  const playwrightArgs = [];
  let group = null;
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--attach" || arg === "--round" || arg === "--") continue;
    if (arg === "--group") {
      const value = argv[i + 1];
      if (typeof value === "string" && !value.startsWith("-")) {
        group = value;
        i += 1;
      }
      continue;
    }
    if (arg.startsWith("--group=")) {
      group = arg.slice("--group=".length) || null;
      continue;
    }
    playwrightArgs.push(arg);
  }
  return { attach, round, group, playwrightArgs };
}
