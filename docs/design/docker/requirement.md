# Docker 实例管理模块 - 需求文档

> 版本: 1.0  
> 日期: 2026-03-24  
> 状态: 已确认

---

## 一、模块概述

### 1.1 背景

当前游戏平台管理器已有 Docker 部署适配器（DockerAdapter、DockerComposeAdapter），但这些仅用于游戏服务器的部署。需要新增独立的 Docker 实例管理模块，用于管理主机上的 Docker 容器资源。

### 1.2 目标

- 提供独立的 Docker 容器管理能力
- 支持容器与游戏实例的关联管理
- 提供容器文件管理、终端、监控等功能
- 与现有游戏实例管理模块形成互补

---

## 二、功能需求

### 2.1 容器管理

#### 2.1.1 容器列表与状态监控（P0）

**功能描述**: 查看主机上所有容器列表及运行状态

**功能要点**:
- 展示容器列表，包含：容器ID、名称、镜像、状态、创建时间、端口映射
- 支持按状态筛选（运行中/已停止/全部）
- 支持按名称/镜像名称搜索
- 支持按关联状态筛选（已关联/未关联）
- 显示容器资源占用概览（CPU/内存）

**数据字段**:
| 字段 | 类型 | 说明 |
|------|------|------|
| containerId | String | 容器ID |
| containerName | String | 容器名称 |
| imageName | String | 镜像名称 |
| imageTag | String | 镜像标签 |
| status | String | 状态：running/stopped/paused/restarting |
| state | String | 详细状态信息 |
| createdAt | DateTime | 创建时间 |
| ports | Array | 端口映射列表 |
| cpuUsage | Double | CPU使用率(%) |
| memoryUsage | Double | 内存使用率(%) |
| memoryUsed | Long | 已用内存(MB) |
| memoryLimit | Long | 内存限制(MB) |
| isLinked | Boolean | 是否已关联到实例 |
| linkedInstanceId | Long | 关联的实例ID |
| linkedInstanceName | String | 关联的实例名称 |

#### 2.1.2 容器生命周期操作（P0）

**功能描述**: 对容器进行启动、停止、重启、删除操作

**操作说明**:
| 操作 | 说明 | 参数 |
|------|------|------|
| 启动 | 启动已停止的容器 | 无 |
| 停止 | 停止运行中的容器 | force: 是否强制停止 |
| 重启 | 重启容器 | timeout: 超时时间(秒) |
| 删除 | 删除容器 | force: 是否强制删除, volumes: 是否删除关联卷 |

**权限控制**: 只能操作自己创建的游戏实例关联的容器

#### 2.1.3 容器终端（P0）

**功能描述**: 通过 Web 终端进入容器执行命令

**功能要点**:
- 支持 exec 模式：在容器内启动新进程执行命令
- 支持 attach 模式：连接到容器主进程
- 支持多种 shell：/bin/bash、/bin/sh 等
- WebSocket 实时通信
- 终端大小自适应

**技术要求**:
- 使用 WebSocket 实现
- 复用现有 XTerm.js 组件
- 支持 resize 事件

#### 2.1.4 容器日志查看（P0）

**功能描述**: 查看容器运行日志

**功能要点**:
- 实时日志流（WebSocket）
- 历史日志查询
- 支持日志行数限制
- 支持关键词过滤
- 支持时间范围筛选
- 日志下载

**接口参数**:
| 参数 | 类型 | 说明 |
|------|------|------|
| lines | Integer | 日志行数，默认100 |
| follow | Boolean | 是否实时跟踪 |
| since | DateTime | 开始时间 |
| until | DateTime | 结束时间 |
| timestamps | Boolean | 是否显示时间戳 |

#### 2.1.5 容器资源监控（P1）

**功能描述**: 实时监控容器资源使用情况

**监控指标**:
| 指标 | 说明 |
|------|------|
| CPU使用率 | 百分比 |
| 内存使用量/限制 | MB |
| 内存使用率 | 百分比 |
| 网络接收/发送 | 字节/秒 |
| 磁盘读取/写入 | 字节/秒 |
| 健康检查状态 | healthy/unhealthy/starting/none |

**展示方式**:
- 实时数据刷新（可配置刷新间隔）
- 图表展示（可选）

---

### 2.2 容器文件管理

#### 2.2.1 目录浏览（P0）

**功能描述**: 浏览容器内指定目录的文件列表

**功能要点**:
- 显示文件/目录列表
- 显示文件大小、修改时间、权限
- 支持目录导航（进入/返回上级）
- 支持路径输入直接跳转

**数据字段**:
| 字段 | 类型 | 说明 |
|------|------|------|
| name | String | 文件/目录名称 |
| path | String | 完整路径 |
| isDirectory | Boolean | 是否为目录 |
| size | Long | 文件大小(字节) |
| modifiedTime | DateTime | 修改时间 |
| permissions | String | 权限字符串 |
| owner | String | 所有者 |
| group | String | 所属组 |

#### 2.2.2 文件查看/编辑/删除（P1）

**功能描述**: 对容器内文件进行查看、编辑、删除操作

**功能要点**:
- 文件查看：支持文本文件预览，支持编码选择
- 文件编辑：在线编辑文本文件，保存后写入容器
- 文件删除：删除文件或空目录
- 文件重命名

**限制**:
- 文件大小限制：预览最大 10MB，编辑最大 1MB
- 支持的编码：UTF-8、GBK、ISO-8859-1

#### 2.2.3 文件上传/下载（P0）

**功能描述**: 容器与主机间的文件传输

**功能要点**:
- 上传：从本地主机上传文件到容器指定目录
- 下载：从容器下载文件到本地
- 支持拖拽上传
- 显示传输进度

#### 2.2.4 批量拷贝（P1）

**功能描述**: 批量拷贝文件/目录

**功能要点**:
- 容器到主机：将容器内文件/目录拷贝到主机指定路径
- 主机到容器：将主机文件/目录拷贝到容器指定路径
- 支持多文件/目录选择
- 显示拷贝进度
- 支持覆盖/跳过策略

---

### 2.3 镜像管理

#### 2.3.1 镜像列表查看（P1）

**功能描述**: 查看主机上的 Docker 镜像列表

**数据字段**:
| 字段 | 类型 | 说明 |
|------|------|------|
| imageId | String | 镜像ID |
| repoTags | Array | 仓库标签列表 |
| size | Long | 镜像大小(MB) |
| createdAt | DateTime | 创建时间 |
| usedByContainers | Integer | 被使用的容器数量 |
| isDangling | Boolean | 是否为悬空镜像 |

#### 2.3.2 删除指定镜像（P1）

**功能描述**: 删除指定的镜像

**参数**:
| 参数 | 类型 | 说明 |
|------|------|------|
| imageId | String | 镜像ID |
| force | Boolean | 是否强制删除 |

**限制**: 正在被容器使用的镜像不能删除（除非强制）

#### 2.3.3 清理悬空镜像（P2）

**功能描述**: 清理无标签的悬空镜像

**功能要点**:
- 列出所有悬空镜像
- 批量清理
- 显示可释放空间

---

### 2.4 容器关联管理

#### 2.4.1 自动关联（P0）

**功能描述**: 容器自动关联到匹配的游戏实例

**关联规则**:
- 根据容器镜像名称与游戏元数据的镜像配置进行匹配
- 匹配成功后自动创建关联关系
- 版本号可以不一致，只需镜像名称匹配

**匹配逻辑**:
```
容器镜像: mygame/palworld:latest
游戏元数据镜像配置: mygame/palworld
→ 匹配成功，自动关联
```

#### 2.4.2 手动关联（P1）

**功能描述**: 手动将容器关联到游戏实例或主机

**功能要点**:
- 支持关联到游戏实例
- 支持关联到主机（不关联具体实例）
- 支持解除关联

---

### 2.5 权限控制

**规则**: 按实例归属控制操作权限

| 用户角色 | 权限范围 |
|---------|---------|
| 普通用户 | 只能操作自己创建的游戏实例关联的容器 |
| 主机管理员 | 可管理该主机上所有容器 |
| 系统管理员 | 可管理所有主机上的容器 |

---

## 三、界面需求

### 3.1 独立 Docker 管理页面

**路由**: `/docker`

**页面结构**:
```
Docker 管理页面
├── 主机选择器（下拉选择主机）
├── Tab 切换
│   ├── 容器列表
│   ├── 镜像列表
│   └── 资源监控
└── 操作区域
```

**容器列表页**:
- 表格展示容器列表
- 状态筛选器
- 搜索框
- 操作按钮（启动/停止/重启/删除）
- 点击行进入容器详情

**容器详情页**:
- 容器基本信息
- Tab 切换：日志/终端/文件/监控/设置
- 关联管理区域

### 3.2 实例详情页集成

**位置**: 实例详情页新增 "关联容器" 区域

**展示内容**:
- 关联的容器列表
- 容器状态快捷查看
- 快捷操作按钮

---

## 四、数据模型

### 4.1 新增表：docker_container_link

存储容器与实例/主机的关联关系

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 主键 |
| host_id | Long | 主机ID |
| container_id | String | 容器ID |
| container_name | String | 容器名称 |
| instance_id | Long | 关联的实例ID（可为空） |
| link_type | String | 关联类型：instance/host |
| image_name | String | 镜像名称 |
| image_tag | String | 镜像标签 |
| auto_linked | Boolean | 是否自动关联 |
| create_time | DateTime | 创建时间 |
| update_time | DateTime | 更新时间 |
| create_by | Long | 创建人ID |

### 4.2 索引

- idx_host_id: 主机ID索引
- idx_container_id: 容器ID索引
- idx_instance_id: 实例ID索引

---

## 五、API 接口规划

### 5.1 容器管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/docker/hosts/{hostId}/containers | 获取容器列表 |
| GET | /api/docker/hosts/{hostId}/containers/{containerId} | 获取容器详情 |
| POST | /api/docker/hosts/{hostId}/containers/{containerId}/start | 启动容器 |
| POST | /api/docker/hosts/{hostId}/containers/{containerId}/stop | 停止容器 |
| POST | /api/docker/hosts/{hostId}/containers/{containerId}/restart | 重启容器 |
| DELETE | /api/docker/hosts/{hostId}/containers/{containerId} | 删除容器 |
| GET | /api/docker/hosts/{hostId}/containers/{containerId}/stats | 获取容器资源统计 |
| GET | /api/docker/hosts/{hostId}/containers/{containerId}/health | 获取健康检查状态 |

### 5.2 容器终端与日志

| 方法 | 路径 | 说明 |
|------|------|------|
| WebSocket | /ws/docker/exec | 容器 exec 终端 |
| WebSocket | /ws/docker/attach | 容器 attach 终端 |
| GET | /api/docker/hosts/{hostId}/containers/{containerId}/logs | 获取容器日志 |
| WebSocket | /ws/docker/logs | 实时日志流 |

### 5.3 文件管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/docker/hosts/{hostId}/containers/{containerId}/files | 获取文件列表 |
| GET | /api/docker/hosts/{hostId}/containers/{containerId}/files/content | 获取文件内容 |
| PUT | /api/docker/hosts/{hostId}/containers/{containerId}/files/content | 更新文件内容 |
| DELETE | /api/docker/hosts/{hostId}/containers/{containerId}/files | 删除文件 |
| POST | /api/docker/hosts/{hostId}/containers/{containerId}/files/upload | 上传文件 |
| GET | /api/docker/hosts/{hostId}/containers/{containerId}/files/download | 下载文件 |
| POST | /api/docker/hosts/{hostId}/containers/{containerId}/copy | 拷贝文件 |

### 5.4 镜像管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/docker/hosts/{hostId}/images | 获取镜像列表 |
| DELETE | /api/docker/hosts/{hostId}/images/{imageId} | 删除镜像 |
| POST | /api/docker/hosts/{hostId}/images/prune | 清理悬空镜像 |

### 5.5 关联管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/docker/links | 创建关联 |
| PUT | /api/docker/links/{id} | 更新关联 |
| DELETE | /api/docker/links/{id} | 删除关联 |
| GET | /api/docker/links | 获取关联列表 |
| POST | /api/docker/links/auto | 执行自动关联 |

---

## 六、非功能需求

### 6.1 性能要求

- 容器列表加载时间 < 3秒
- 终端响应延迟 < 100ms
- 文件上传/下载支持大文件（>100MB）

### 6.2 安全要求

- 所有操作需要 JWT 认证
- 按实例归属进行权限控制
- 敏感操作记录操作日志

### 6.3 兼容性

- 支持 Docker API 1.40+
- 支持主流浏览器（Chrome、Firefox、Edge）

---

## 七、优先级排序

### P0 - 核心功能（第一阶段）

1. 容器列表与状态监控
2. 容器生命周期操作
3. 容器终端（exec + attach）
4. 容器日志查看
5. 目录浏览
6. 文件上传/下载
7. 自动关联

### P1 - 重要功能（第二阶段）

1. 容器资源监控
2. 文件查看/编辑/删除
3. 批量拷贝
4. 镜像列表查看
5. 删除指定镜像
6. 手动关联

### P2 - 优化功能（第三阶段）

1. 清理悬空镜像
2. 监控图表展示

---

## 八、依赖关系

### 8.1 技术依赖

- Docker Java 3.3.4（已有）
- Apache MINA SSHD 2.12.1（已有）
- XTerm.js 5.3.0（已有）

### 8.2 模块依赖

- 主机管理模块：获取主机连接信息
- 游戏元数据模块：获取镜像匹配规则
- 游戏实例模块：关联实例信息

---

*文档版本: 1.0*  
*最后更新: 2026-03-24*
