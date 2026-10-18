// 生成代表性大插件归档（965 个文件，解压后约 165 MiB）。
//
// 设计约束：
// - 输出固定到 e2e/.tmp/（gitignored），不把大二进制提交进仓库。
// - 归档必须真实超过当前 multipart 100MB 上限，用于验证大包上传链路。
// - 文件内容使用可压缩填充；压缩后仍保持在数百 MB 内，便于本地 E2E。
import { randomFillSync } from "node:crypto";
import {
  readFileSync,
  mkdirSync,
  openSync,
  closeSync,
  statSync,
  writeSync,
} from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { crc32 as zlibCrc32, deflateRawSync } from "node:zlib";

const E2E_DIR = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const OUTPUT_DIR = join(E2E_DIR, ".tmp", "fixtures");
const OUTPUT_PATH = join(OUTPUT_DIR, "e2e-large-plugin.zip");

export const LARGE_PLUGIN = Object.freeze({
  name: "e2e-large-plugin",
  fileCount: 965,
  targetBytes: 165 * 1024 * 1024,
  minimumArchiveBytes: 100 * 1024 * 1024 + 1,
  executableFileNames: [],
});

function crc32Fallback(buffer) {
  const table = [];
  for (let value = 0; value < 256; value += 1) {
    let crc = value;
    for (let bit = 0; bit < 8; bit += 1) {
      crc = crc & 1 ? (crc >>> 1) ^ 0xedb88320 : crc >>> 1;
    }
    table.push(crc >>> 0);
  }
  let crc = 0xffffffff;
  for (const byte of buffer) {
    crc = table[(crc ^ byte) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

const crc32 = typeof zlibCrc32 === "function" ? zlibCrc32 : crc32Fallback;

function makeEntry(name, data, executable = false) {
  const nameBuffer = Buffer.from(name, "utf8");
  const compressed = deflateRawSync(data, { level: 1 });
  const crc = crc32(data);
  const local = Buffer.alloc(30);
  local.writeUInt32LE(0x04034b50, 0);
  local.writeUInt16LE(20, 4);
  local.writeUInt16LE(0x0800, 6);
  local.writeUInt16LE(8, 8);
  local.writeUInt16LE(0, 10);
  local.writeUInt16LE(0, 12);
  local.writeUInt32LE(crc, 14);
  local.writeUInt32LE(compressed.length, 18);
  local.writeUInt32LE(data.length, 22);
  local.writeUInt16LE(nameBuffer.length, 26);
  local.writeUInt16LE(0, 28);

  const central = Buffer.alloc(46);
  central.writeUInt32LE(0x02014b50, 0);
  central.writeUInt16LE(0x0314, 4);
  central.writeUInt16LE(20, 6);
  central.writeUInt16LE(0x0800, 8);
  central.writeUInt16LE(8, 10);
  central.writeUInt16LE(0, 12);
  central.writeUInt16LE(0, 14);
  central.writeUInt32LE(crc, 16);
  central.writeUInt32LE(compressed.length, 20);
  central.writeUInt32LE(data.length, 24);
  central.writeUInt16LE(nameBuffer.length, 28);
  central.writeUInt16LE(0, 30);
  central.writeUInt16LE(0, 32);
  central.writeUInt16LE(0, 34);
  // external attributes 是 4 字节小端整数：高 16 位为 Unix mode，
  // 低 16 位为 DOS attributes。
  central.writeUInt16LE(0, 36);
  central.writeUInt32LE(
    ((executable ? 0o100755 : 0o100644) << 16) >>> 0,
    38,
  );
  central.writeUInt32LE(0, 42);

  return { nameBuffer, compressed, local, central, size: data.length };
}

function buildEntries() {
  const root = `${LARGE_PLUGIN.name}/left4dead2`;
  const primarySmx = Buffer.concat([
    Buffer.from([0x53, 0x50, 0x46, 0x46]),
    Buffer.from("\0".repeat(4092), "latin1"),
  ]);
  const entries = [
    makeEntry(
      `${root}/addons/sourcemod/plugins/e2e-large-plugin.smx`,
      primarySmx,
    ),
    makeEntry(
      `${root}/cfg/sourcemod/e2e-large-plugin.cfg`,
      Buffer.from("sm_e2e_large_plugin 1\n", "latin1"),
    ),
  ];

  const remainingBytes =
    LARGE_PLUGIN.targetBytes -
    entries.reduce((sum, entry) => sum + entry.size, 0);
  const fillerCount = LARGE_PLUGIN.fileCount - entries.length;
  LARGE_PLUGIN.executableFileNames.length = 0;
  const baseSize = Math.floor(remainingBytes / fillerCount);
  let remainder = remainingBytes - baseSize * fillerCount;

  for (let index = 0; index < fillerCount; index += 1) {
    const size = baseSize + (index < remainder ? 1 : 0);
    const relativeName = `addons/sourcemod/data/e2e-large/${String(
      index + 1,
    ).padStart(4, "0")}.dat`;
    if (index % 32 === 0) {
      LARGE_PLUGIN.executableFileNames.push(relativeName);
    }
    // 不可压缩填充确保归档体积真实超过 multipart 上限，而不是只在
    // 中央目录里声明 165MiB、实际压缩到几 MB 的“伪大包”。
    const chunk = randomFillSync(Buffer.allocUnsafe(size));
    entries.push(
      makeEntry(
        `${root}/${relativeName}`,
        chunk,
        index % 32 === 0,
      ),
    );
  }

  return entries;
}

export function verifyLargePluginFixture(zipPath = OUTPUT_PATH) {
  const archive = readFileSync(zipPath);
  const eocdOffset = archive.lastIndexOf(
    Buffer.from([0x50, 0x4b, 0x05, 0x06]),
  );
  if (eocdOffset < 0) {
    throw new Error(
      `[CHECK][FAIL] 大归档 fixture 缺少 ZIP EOCD: path=${zipPath}`,
    );
  }
  const entryCount = archive.readUInt16LE(eocdOffset + 10);
  const centralOffset = archive.readUInt32LE(eocdOffset + 16);
  let cursor = centralOffset;
  const executablePaths = [];
  let nonExecutableEntries = 0;
  for (let index = 0; index < entryCount; index += 1) {
    if (archive.readUInt32LE(cursor) !== 0x02014b50) {
      throw new Error(
        `[CHECK][FAIL] 大归档 fixture 中央目录损坏: entry=${index}, offset=${cursor}; remediation=重新生成 fixture`,
      );
    }
    const externalAttributes = archive.readUInt32LE(cursor + 38);
    const unixMode = externalAttributes >>> 16;
    if ((unixMode & 0o170000) !== 0o100000) {
      throw new Error(
        `[CHECK][FAIL] 大归档 fixture 条目不包含 Unix 普通文件 mode: entry=${index}, mode=0${unixMode.toString(8)}; remediation=检查 central external attributes 偏移 38`,
      );
    }
    if ((unixMode & 0o111) === 0) {
      nonExecutableEntries += 1;
    }
    const nameLength = archive.readUInt16LE(cursor + 28);
    const extraLength = archive.readUInt16LE(cursor + 30);
    const commentLength = archive.readUInt16LE(cursor + 32);
    const name = archive
      .subarray(cursor + 46, cursor + 46 + nameLength)
      .toString("utf8");
    if ((unixMode & 0o111) !== 0) {
      executablePaths.push(
        name.slice(name.indexOf("/left4dead2/") + "/left4dead2/".length),
      );
    }
    cursor += 46 + nameLength + extraLength + commentLength;
  }
  if (entryCount !== LARGE_PLUGIN.fileCount) {
    throw new Error(
      `[CHECK][FAIL] 大归档 fixture 中央目录条目数异常: expected=${LARGE_PLUGIN.fileCount}, actual=${entryCount}`,
    );
  }
  if (executablePaths.length === 0 || nonExecutableEntries === 0) {
    throw new Error(
      `[CHECK][FAIL] 大归档 fixture 权限覆盖不足: executable=${executablePaths.length}, nonExecutable=${nonExecutableEntries}; remediation=保留可执行与不可执行两类样例`,
    );
  }
  const expectedExecutable = [...LARGE_PLUGIN.executableFileNames].sort();
  const actualExecutable = executablePaths.sort();
  if (JSON.stringify(actualExecutable) !== JSON.stringify(expectedExecutable)) {
    throw new Error(
      `[CHECK][FAIL] 大归档 fixture 权限清单与生成清单不一致: expected=${expectedExecutable.length}, actual=${actualExecutable.length}; firstDiff=${actualExecutable.find((name, index) => name !== expectedExecutable[index]) ?? "none"}`,
    );
  }
  console.log(
    `[CHECK][PASS] 大归档 fixture 中央目录权限元数据: entries=${entryCount}, executable=${executablePaths.length}, nonExecutable=${nonExecutableEntries}`,
  );
  return {
    entryCount,
    executableEntries: executablePaths.length,
    nonExecutableEntries,
    executablePaths,
  };
}

function writeZip(entries) {
  const fd = openSync(OUTPUT_PATH, "w");
  const centralParts = [];
  let offset = 0;
  try {
    for (const entry of entries) {
      entry.central.writeUInt32LE(offset, 42);
      writeSync(fd, entry.local);
      writeSync(fd, entry.nameBuffer);
      writeSync(fd, entry.compressed);
      centralParts.push(entry.central, entry.nameBuffer);
      offset +=
        entry.local.length + entry.nameBuffer.length + entry.compressed.length;
    }
    const central = Buffer.concat(centralParts);
    const end = Buffer.alloc(22);
    end.writeUInt32LE(0x06054b50, 0);
    end.writeUInt16LE(entries.length, 8);
    end.writeUInt16LE(entries.length, 10);
    end.writeUInt32LE(central.length, 12);
    end.writeUInt32LE(offset, 16);
    writeSync(fd, central);
    writeSync(fd, end);
  } finally {
    closeSync(fd);
  }
}

export function ensureLargePluginFixture() {
  mkdirSync(OUTPUT_DIR, { recursive: true });
  const entries = buildEntries();
  const extractedBytes = entries.reduce(
    (sum, entry) => sum + entry.size,
    0,
  );
  const existingSize = statSync(OUTPUT_PATH, { throwIfNoEntry: false })?.size;
  if (existingSize && existingSize >= LARGE_PLUGIN.minimumArchiveBytes) {
    verifyLargePluginFixture();
    console.log(
      `[CHECK][PASS] 大归档 fixture 已存在: ${OUTPUT_PATH}, archive=${existingSize}B, extracted=${extractedBytes}B, files=${entries.length}`,
    );
    return OUTPUT_PATH;
  }

  writeZip(entries);
  const archiveBytes = statSync(OUTPUT_PATH).size;
  if (entries.length !== LARGE_PLUGIN.fileCount) {
    throw new Error(
      `[CHECK][FAIL] 大归档文件数异常: expected=${LARGE_PLUGIN.fileCount}, actual=${entries.length}`,
    );
  }
  if (extractedBytes !== LARGE_PLUGIN.targetBytes) {
    throw new Error(
      `[CHECK][FAIL] 大归档解压字节异常: expected=${LARGE_PLUGIN.targetBytes}, actual=${extractedBytes}`,
    );
  }
  if (archiveBytes <= 100 * 1024 * 1024) {
    throw new Error(
      `[CHECK][FAIL] 大归档未超过 100MB 上传上限: actual=${archiveBytes}B; remediation=提高填充不可压缩度后重新生成`,
    );
  }
  verifyLargePluginFixture();
  console.log(
    `[CHECK][PASS] 大归档 fixture 生成完成: ${OUTPUT_PATH}, archive=${archiveBytes}B, extracted=${extractedBytes}B, files=${entries.length}`,
  );
  return OUTPUT_PATH;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  ensureLargePluginFixture();
}
