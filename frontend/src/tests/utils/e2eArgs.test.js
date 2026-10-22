import { describe, expect, it } from "vitest";
import { parseE2eArgs } from "../../../e2e/support/e2e-args.js";

describe("parseE2eArgs", () => {
  it("npm 已消费 -- 时仍透传 spec 与 reporter", () => {
    const parsed = parseE2eArgs([
      "--attach",
      "e2e/main-app/instance/drill-legs.spec.js",
      "--reporter=list",
    ]);

    expect(parsed).toEqual({
      attach: true,
      round: false,
      group: null,
      playwrightArgs: [
        "e2e/main-app/instance/drill-legs.spec.js",
        "--reporter=list",
      ],
    });
  });

  it("直接 node 调用时兼容显式 -- 分隔符", () => {
    const parsed = parseE2eArgs([
      "--round",
      "--",
      "e2e/main-app/auth",
    ]);

    expect(parsed).toEqual({
      attach: false,
      round: true,
      group: null,
      playwrightArgs: ["e2e/main-app/auth"],
    });
  });

  it("无额外参数时保持默认全量运行", () => {
    expect(parseE2eArgs([])).toEqual({
      attach: false,
      round: false,
      group: null,
      playwrightArgs: [],
    });
  });

  it("--group smoke 消费分组名，不留给 Playwright", () => {
    expect(parseE2eArgs(["--group", "smoke", "--reporter=list"])).toEqual({
      attach: false,
      round: false,
      group: "smoke",
      playwrightArgs: ["--reporter=list"],
    });
  });

  it("--group=smoke 等价写法", () => {
    expect(parseE2eArgs(["--group=smoke"])).toEqual({
      attach: false,
      round: false,
      group: "smoke",
      playwrightArgs: [],
    });
  });

  it("--group 缺值时不吞掉后续 Playwright 参数", () => {
    expect(parseE2eArgs(["--group", "--reporter=list"])).toEqual({
      attach: false,
      round: false,
      group: null,
      playwrightArgs: ["--reporter=list"],
    });
  });
});
