// 实例文件夹具：通过平台文件 API 在实例内造/删文件。
//
// 为什么不让用例 SSH 或 docker exec：断言通道统一走平台自身 REST（见
// .scratch/ui-e2e-automation/spec.md）。平台文件 API 的 relativePath 语义
// 与插件读取路径完全一致（Native = installPath，Docker = containerWorkDir），
// 因此"用 API 写的文件插件一定能读到"，夹具和被测路径天然同源。

function encodePath(path) {
  return encodeURIComponent(path);
}

/**
 * 在实例内写入文本文件（父目录不存在时自动创建）。
 *
 * @param {object} api createApi() 客户端
 * @param {number} instanceId 实例 ID
 * @param {string} relativePath 相对实例根目录的路径（正斜杠）
 * @param {string} content 文件内容
 */
export async function writeInstanceFile(api, instanceId, relativePath, content) {
  const dir = relativePath.includes("/")
    ? relativePath.slice(0, relativePath.lastIndexOf("/"))
    : "";
  if (dir) {
    // Docker 路由下 writeTextFile 自己会 mkdir -p，Native（SFTP）不会；
    // 统一先建目录，写入路径对两种部署类型都成立。
    await api.post(`/api/instances/${instanceId}/files/directory`, { path: dir });
  }
  const res = await api.put(`/api/instances/${instanceId}/files/content`, {
    path: relativePath,
    content,
  });
  if (res.status >= 400 || (res.body?.code ?? 200) !== 200) {
    throw new Error(
      `[CHECK][FAIL] 写入实例文件失败 path=${relativePath}: HTTP ${res.status} ${JSON.stringify(
        res.body,
      ).slice(0, 300)}; remediation: 确认实例仍在运行且文件 API 可用`,
    );
  }
}

/**
 * 删除实例内文件（不存在时静默）。
 */
export async function removeInstanceFile(api, instanceId, relativePath) {
  await api
    .delete(
      `/api/instances/${instanceId}/files?path=${encodePath(relativePath)}`,
    )
    .catch(() => {});
}

/**
 * 列出实例目录，返回文件名数组（用于验证写入确实落盘）。
 */
export async function listInstanceFileNames(api, instanceId, dir) {
  const res = await api.get(
    `/api/instances/${instanceId}/files?path=${encodePath(dir)}`,
  );
  return (res.body?.data?.files ?? []).map((item) => item.name);
}
