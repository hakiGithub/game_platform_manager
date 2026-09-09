// 测试资产合成器：重新生成二进制 fixtures 并做结构自检。
// 用法: node build-fixtures.mjs  （提交生成的二进制；本脚本保证可复现）
// 红线: 用例运行时禁止临时外网下载资产——一切资产来自本目录。
import { writeFileSync, readFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const FIXTURES_DIR = dirname(fileURLToPath(import.meta.url));

const u32 = (n) => {
  const b = Buffer.alloc(4);
  b.writeUInt32LE(n >>> 0);
  return b;
};
const u16 = (n) => {
  const b = Buffer.alloc(2);
  b.writeUInt16LE(n & 0xffff);
  return b;
};
const cstr = (s) => Buffer.concat([Buffer.from(s, "latin1"), Buffer.from([0])]);

// ---------- 最小合法 VPK v1（含一个 mission txt 条目） ----------
// 结构对齐后端 VpkParser（plugin-l4d2 util/VpkParser）：header(12) + 目录树 + 数据段。
// 条目 preloadBytes=0，此时布局与标准 VPK v1 逐字节一致。
const MISSION_TXT = [
  '"mission"',
  "{",
  '\t"DisplayTitle"\t"E2E Test Campaign"',
  '\t"modes"',
  "\t{",
  '\t\t"coop"',
  "\t\t{",
  '\t\t\t"1"',
  "\t\t\t{",
  '\t\t\t\t"Map"\t\t"e2e_test_map"',
  '\t\t\t\t"DisplayName"\t"E2E Chapter 1"',
  "\t\t\t}",
  "\t\t}",
  "\t}",
  "}",
].join("\n");

function buildVpk() {
  const missionData = Buffer.from(MISSION_TXT, "latin1");
  // 目录树条目元数据：crc(4) preloadBytes(2)=0 archiveIndex(2)=0xFFFF(主包)
  //                  fileOffset(4)=0 fileSize(4) terminator(2)
  const entry = Buffer.concat([
    u32(0),
    u16(0),
    u16(0xffff),
    u32(0),
    u32(missionData.length),
    u16(0),
  ]);
  const tree = Buffer.concat([
    cstr("txt"),
    cstr("missions"),
    cstr("e2e_test_mission"),
    entry,
    Buffer.from([0]), // 文件名层结束
    Buffer.from([0]), // 路径层结束
    Buffer.from([0]), // 扩展名层结束
  ]);
  const header = Buffer.concat([u32(0x55aa1234), u32(1), u32(tree.length)]);
  return Buffer.concat([header, tree, missionData]);
}

// 自检：按 VpkParser 同样的树遍历规则解出条目并核对数据段
function selfCheckVpk(buf) {
  if (buf.readUInt32LE(0) !== 0x55aa1234) throw new Error("VPK magic 不符");
  if (buf.readUInt32LE(4) !== 1) throw new Error("VPK 版本应为 1");
  const treeSize = buf.readUInt32LE(8);
  let p = 12;
  const readCstr = () => {
    const end = buf.indexOf(0, p);
    const s = buf.subarray(p, end).toString("latin1");
    p = end + 1;
    return s;
  };
  const entries = [];
  for (let ext = readCstr(); ext !== ""; ext = readCstr()) {
    for (let path = readCstr(); path !== ""; path = readCstr()) {
      for (let name = readCstr(); name !== ""; name = readCstr()) {
        const crc = buf.readUInt32LE(p);
        const preloadBytes = buf.readUInt16LE(p + 4);
        const archiveIndex = buf.readUInt16LE(p + 6);
        const fileOffset = buf.readUInt32LE(p + 8);
        const fileSize = buf.readUInt32LE(p + 12);
        p += 18 + preloadBytes; // 元数据 + preload + terminator(2)
        entries.push({
          ext,
          path,
          name,
          crc,
          archiveIndex,
          fileOffset,
          fileSize,
        });
      }
    }
  }
  if (p - 12 !== treeSize)
    throw new Error(`treeSize 不符: 实际 ${p - 12} vs 声明 ${treeSize}`);
  const mission = entries.find((e) => e.path === "missions" && e.ext === "txt");
  if (!mission) throw new Error("目录树缺少 missions/*.txt 条目");
  if (mission.archiveIndex !== 0xffff)
    throw new Error("mission 条目应在主包(0xFFFF)");
  const data = buf.subarray(
    12 + treeSize + mission.fileOffset,
    12 + treeSize + mission.fileOffset + mission.fileSize,
  );
  if (!data.toString("latin1").includes('"Map"\t\t"e2e_test_map"'))
    throw new Error("数据段缺少 mission Map 键值");
  return entries.length;
}

// ---------- 最小 .smx 样例（SourceMod 插件占位二进制） ----------
// 平台上传通道只做扩展名/名称处理（PluginInstallService SMX_SUFFIX），
// 本样例是带 magic 风格头的占位文件，不是可被 SourceMod 加载的真实插件——
// E2E 断言的是"上传成功 + 文件落位"，不依赖 SourceMod 加载它。
function buildSmx() {
  return Buffer.concat([
    Buffer.from([0x53, 0x50, 0x46, 0x46]), // "SPFF" 风格 magic 占位
    u16(0x0110), // 版本占位
    Buffer.from(
      "e2e sample sourcemod plugin fixture - not a real binary".repeat(2),
      "latin1",
    ),
  ]);
}

function selfCheckSmx(buf) {
  if (buf.subarray(0, 4).toString("latin1") !== "SPFF")
    throw new Error("smx magic 占位不符");
  if (buf.length < 32) throw new Error("smx 样例过小");
}

// ---------- 生成 ----------
mkdirSync(join(FIXTURES_DIR, "vpk"), { recursive: true });
mkdirSync(join(FIXTURES_DIR, "sourcemod"), { recursive: true });

const vpk = buildVpk();
writeFileSync(join(FIXTURES_DIR, "vpk", "e2e-test-map.vpk"), vpk);
console.log(
  `vpk/e2e-test-map.vpk  ${vpk.length}B  条目 ${selfCheckVpk(vpk)} 个 ✓`,
);

const smx = buildSmx();
writeFileSync(join(FIXTURES_DIR, "sourcemod", "e2e-sample-plugin.smx"), smx);
selfCheckSmx(smx);
console.log(`sourcemod/e2e-sample-plugin.smx  ${smx.length}B  ✓`);

// YAML 资产自检：解析断言关键字段（与 GameYamlConfig.isValid 的强校验项一致）
const yml = readFileSync(
  join(FIXTURES_DIR, "games", "e2e-drill-game.yml"),
  "utf8",
);
if (
  !/^game:/.test(yml) ||
  !/code:\s*e2edrill/.test(yml) ||
  !/name:\s*\S+/.test(yml)
) {
  throw new Error("YAML 缺少 game.code / game.name 强校验字段");
}
console.log("games/e2e-drill-game.yml  ✓");
console.log("全部资产自检通过");
