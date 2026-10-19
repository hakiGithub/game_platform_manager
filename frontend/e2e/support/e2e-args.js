/**
 * 从 runner 进程参数中分离自身的模式开关与 Playwright 参数。
 *
 * npm 会在调用脚本前消费 `--`，因此 runner 不能依赖 process.argv 中还存在
 * 分隔符。这里只把明确属于 runner 的 `--attach` / `--round` 视为开关，
 * 其余参数（包括 spec 路径和 `--reporter=list`）全部透传给 Playwright。
 */
export function parseE2eArgs(argv = []) {
  const attach = argv.includes("--attach");
  const round = argv.includes("--round");
  const playwrightArgs = argv.filter(
    (arg) => arg !== "--attach" && arg !== "--round" && arg !== "--",
  );
  return { attach, round, playwrightArgs };
}
