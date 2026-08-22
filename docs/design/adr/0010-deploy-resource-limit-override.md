# ADR-0010: 部署资源限制生效链路（override 文件方案）

## 状态

Accepted

## 背景（Context）

部署向导允许用户选择资源限制（CPU 核数 / 内存 GB / 磁盘 GB），前端将其作为
`configInfo.resources = { cpuLimit, memoryLimit, diskLimit }` 提交，但链路在多个环节断裂：

1. **docker-compose 部署（dnf_tw、l4d2）**：`DockerComposeAdapter` 完全不消费
   `resources`；dnf_tw 模板硬编码 `mem_limit: 1g` / `cpus: '1.0'`，用户选择无效。
2. **docker 部署（l4d2、minecraft、palworld、rust、valheim 等）**：`DockerAdapter`
   读取**顶层** `memoryLimit`（字符串，如 "2g"），而前端提交的是**嵌套**
   `resources.memoryLimit`（数字，单位 GB）——键路径与值格式双错，限制从未生效。
3. **linuxgsm-docker 部署（minecraft 等 133+ 游戏）**：`LinuxGsmDockerAdapter`
   同样不消费 `resources`。
4. **diskLimit** 无任何后端消费方（Docker 无法对 bind mount 直接限磁盘）。
5. **前端默认值硬编码**（2 核 / 4GB / 10GB），与游戏 yml `dependencies`
   声明（如 dnf_tw: 1 核 / 2G）无关联。

## 决策（Decision）

### D1. compose 类部署采用 `docker-compose.override.yml` 自动合并

平台在部署/更新时于实例 workDir 生成 `docker-compose.override.yml`（仅含
`mem_limit` / `cpus`），利用 Compose 的 **override 文件自动合并机制**：
`docker compose up/start/stop/ps/logs/config` 未显式指定 `-f` 时自动加载
`docker-compose.yml` + `docker-compose.override.yml`，标量按后者覆盖。

理由：
- **零命令改动**：三个 compose 适配器的全部命令构造点（up/down/start/stop/
  restart/ps/logs/pull/config）无需加 `-f` 参数，V1（docker-compose）与
  V2（docker compose）均支持自动合并。
- **零模板改动**：138+ 游戏 yml 模板不动，模板注释完整保留。
- **用户值天然优先**：标量覆盖语义使向导选择覆盖 dnf_tw 模板硬编码值。
- **可移除**：删除 override 文件即回到模板原生行为。

覆盖文件内容由后端从渲染后的 compose 模板解析服务名（snakeyaml 只读解析，
不回写主文件），对**全部服务**应用同一组限制（当前所有游戏均为单服务模板）。

### D2. 生成与清除时机

- `preDeploy`：上传主 compose 文件后生成 override（`resources` 中
  cpuLimit/memoryLimit 任一 > 0 时）；两者均缺省/为 0 时**主动删除**远端
  override（`rm -f`），保证"取消限制后更新"能回到无限制状态。
- `update`：重建容器前重新同步 override（`up -d --force-recreate` 是限制
  生效点——容器创建时烙入限制，start/stop/restart 不重建容器、无需关心）。
- 解析模板失败时**跳过 override 并 warn**（fail-open，不阻塞部署）。

### D3. 纯 docker 部署修复键路径与单位

`DockerAdapter` 改读嵌套 `resources.memoryLimit` / `resources.cpuLimit`
（Number 类型），单位转换：内存 GB 数字 → `--memory {n}g`；CPU 核数 →
`--cpus {n}`。移除无人使用的顶层字符串读取路径。

### D4. 前端默认值来自游戏 dependencies

选择游戏时从 `game.environmentDeps`（yml `dependencies`）解析初始值：
`cpu: "1核"` → 1、`memory: "2G"` → 2、`disk: "20G"` → 20；解析失败回退
全局默认（2/4/10）。

### D5. diskLimit 语义降级为"磁盘占用预估"

无容器级消费方，保留为**部署前环境校验**输入（对照主机剩余磁盘），前端
label 明确其非硬限制。

### D6. native（linuxgsm 进程）部署不支持

本次不引入 systemd MemoryMax/CPUQuota；前端在非 docker 类部署时提示
"资源限制仅对 Docker 类部署生效"。

## 后果（Consequences）

- 用户向导中的资源选择在 docker / docker-compose / linuxgsm-docker 三类
  部署上真实生效；dnf_tw 硬编码 1g/1.0 默认值仍作为模板兜底。
- 实例 workDir 多一个平台生成的 `docker-compose.override.yml`；用户手动
  修改会在下次部署/更新时被覆盖（文件头注释已声明）。
- 多服务模板（未来）当前按"全部服务同一组限制"处理，如需按服务差异化
  需扩展向导交互。
- 主机上手工执行 `docker compose` 命令也会自动合并 override——与平台
  行为一致，无歧义。

## 备选方案（Alternatives）

- **变量池注入**（`${MEM_LIMIT}` 写入 .env）：需改全部模板，空值语义
  （`mem_limit:` 空值）在部分 compose 版本报错，弃用。
- **渲染后 YAML 后处理重写主文件**：snakeyaml 重序列化丢失 dnf_tw 模板
  大量注释，弃用。
- **命令级 `-f` 拼接**：需改动 3 个适配器 30+ 处命令构造，遗漏一处即
  行为不一致，被 override 自动合并方案完全替代。
