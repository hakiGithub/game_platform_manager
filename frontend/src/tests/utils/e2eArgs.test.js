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
      playwrightArgs: ["e2e/main-app/auth"],
    });
  });

  it("无额外参数时保持默认全量运行", () => {
    expect(parseE2eArgs([])).toEqual({
      attach: false,
      round: false,
      playwrightArgs: [],
    });
  });
});
