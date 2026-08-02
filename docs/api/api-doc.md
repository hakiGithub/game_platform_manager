# 游戏服务器统一管理平台 API接口文档

> **版本**: v1.2.0  
> **更新日期**: 2026-08-02  
> **后端框架**: Spring Boot 3.2.5  
> **API基础路径**: `/api`

---

## 目录

- [1. 概述](#1-概述)
- [2. 通用说明](#2-通用说明)
- [3. 认证模块](#3-认证模块)
- [4. 主机管理模块](#4-主机管理模块)
- [5. 游戏元数据模块](#5-游戏元数据模块)
- [6. 游戏实例模块](#6-游戏实例模块)
- [7. 插件管理模块](#7-插件管理模块)
- [8. 系统设置模块](#8-系统设置模块)
- [9. 错误码说明](#9-错误码说明)
- [10. Docker 实例管理模块](#10-docker-实例管理模块)

---

## 1. 概述

本文档描述游戏服务器统一管理平台的所有API接口规范，**以代码实际实现为准**。文档与代码存在差异时，以代码为准。

> **当前实现状态**（2026-08-02）：核心业务接口与插件框架已完成。包含主机管理、游戏实例、部署向导、Docker 管理、备份还原、操作日志（含导出）、插件框架（PF4J + Wujie 微前端）、任务中心等模块。用户注册功能不在 MVP 范围内。

### 1.1 基础信息

| 项目 | 说明 |
|------|------|
| API基础路径 | `/api` |
| 协议 | HTTP/HTTPS |
| 数据格式 | JSON |
| 字符编码 | UTF-8 |
| 认证方式 | JWT Token |

### 1.2 请求头说明

| 请求头 | 必填 | 说明 |
|--------|------|------|
| `Content-Type` | 是 | `application/json` |
| `Authorization` | 是 | `Bearer {token}` (登录后获取的JWT令牌) |
| `Accept` | 否 | `application/json` |

---

## 2. 通用说明

### 2.1 统一响应格式

所有接口返回统一的JSON格式：

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {},
  "timestamp": 1711084800000
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| code | Integer | 响应状态码，200表示成功 |
| message | String | 响应消息 |
| data | Object | 响应数据，可能为对象、数组或null |
| timestamp | Long | 响应时间戳（毫秒） |

### 2.2 分页响应格式

分页查询接口返回格式：

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "current": 1,
    "size": 10,
    "total": 100,
    "pages": 10,
    "records": []
  },
  "timestamp": 1711084800000
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| current | Long | 当前页码 |
| size | Long | 每页大小 |
| total | Long | 总记录数 |
| pages | Long | 总页数 |
| records | Array | 数据列表 |

### 2.3 分页请求参数

| 参数名 | 类型 | 必填 | 默认值 | 说明 |
|--------|------|------|--------|------|
| current | Integer | 否 | 1 | 当前页码 |
| size | Integer | 否 | 10 | 每页大小 |
| orderBy | String | 否 | - | 排序字段 |
| order | String | 否 | asc | 排序方式：asc/desc |

### 2.4 通用字段说明

所有实体对象包含以下通用字段：

| 字段名 | 类型 | 说明 |
|--------|------|------|
| id | Long | 主键ID |
| createTime | DateTime | 创建时间 |
| updateTime | DateTime | 更新时间 |
| createBy | String | 创建人 |
| updateBy | String | 更新人 |
| deleted | Integer | 逻辑删除标识：0-未删除，1-已删除 |
| remark | String | 备注 |

---

## 3. 认证模块

**模块路径**: `/api/auth`

### 3.1 用户登录

**接口**: `POST /api/auth/login`

**描述**: 用户登录获取JWT令牌

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| username | String | 是 | 用户名 |
| password | String | 是 | 密码 |

**请求示例**:

```json
{
  "username": "admin",
  "password": "admin123"
}
```

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| token | String | JWT访问令牌 |
| tokenType | String | 令牌类型，固定值"Bearer" |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "token": "eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJhZG1pbiIsImlhdCI6MTcxMTA4NDgwMCwiZXhwIjoxNzExMTcxMjAwfQ.xxx",
    "tokenType": "Bearer"
  },
  "timestamp": 1711084800000
}
```

---

### 3.2 刷新Token

**接口**: `POST /api/auth/refresh`

**描述**: 刷新JWT令牌

**请求头**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| Authorization | String | 是 | 当前有效的JWT令牌 |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| token | String | 新的JWT访问令牌 |
| tokenType | String | 令牌类型，固定值"Bearer" |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "token": "eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJhZG1pbiIsImlhdCI6MTcxMTA4NDgwMCwiZXhwIjoxNzExMTcxMjAwfQ.xxx",
    "tokenType": "Bearer"
  },
  "timestamp": 1711084800000
}
```

---

### 3.3 获取当前用户信息

**接口**: `GET /api/auth/info`

**描述**: 获取当前登录用户信息

**认证**: 需要JWT令牌

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| username | String | 用户名 |
| nickname | String | 昵称 |
| email | String | 邮箱 |
| avatar | String | 头像URL |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "username": "admin",
    "nickname": "系统管理员",
    "email": "admin@gameplatform.com",
    "avatar": ""
  },
  "timestamp": 1711084800000
}
```

---

### 3.4 用户登出

**接口**: `POST /api/auth/logout`

**描述**: 用户退出登录

**认证**: 需要JWT令牌

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": null,
  "timestamp": 1711084800000
}
```

---

### 3.5 修改密码

**接口**: `PUT /api/auth/password`

**描述**: 修改当前用户密码

**认证**: 需要JWT令牌

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| oldPassword | String | 是 | 原密码 |
| newPassword | String | 是 | 新密码 |
| confirmPassword | String | 是 | 确认新密码 |

**请求示例**:

```json
{
  "oldPassword": "admin123",
  "newPassword": "newpassword123",
  "confirmPassword": "newpassword123"
}
```

**响应示例**:

```json
{
  "code": 200,
  "message": "密码修改成功",
  "data": null,
  "timestamp": 1711084800000
}
```

---

## 4. 主机管理模块

**模块路径**: `/api/hosts`

### 4.1 获取主机列表

**接口**: `GET /api/hosts`

**描述**: 分页获取主机列表

**认证**: 需要JWT令牌

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| current | Integer | 否 | 当前页码，默认1 |
| size | Integer | 否 | 每页大小，默认10 |
| name | String | 否 | 主机名称（模糊查询） |
| ip | String | 否 | IP地址（模糊查询） |
| status | Integer | 否 | 状态：0-离线，1-在线 |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| records | Array | 主机列表 |
| records[].id | Long | 主机ID |
| records[].name | String | 主机名称 |
| records[].ip | String | IP地址 |
| records[].sshPort | Integer | SSH端口 |
| records[].sshUsername | String | SSH用户名 |
| records[].osType | String | 操作系统类型 |
| records[].osVersion | String | 操作系统版本 |
| records[].cpuCores | Integer | CPU核心数 |
| records[].memoryMb | Long | 内存大小(MB) |
| records[].diskGb | Long | 磁盘大小(GB) |
| records[].status | Integer | 状态：0-离线，1-在线 |
| records[].lastCheckTime | DateTime | 最后检测时间 |
| records[].tags | String | 标签(JSON格式) |
| records[].createTime | DateTime | 创建时间 |
| records[].updateTime | DateTime | 更新时间 |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "current": 1,
    "size": 10,
    "total": 2,
    "pages": 1,
    "records": [
      {
        "id": 1,
        "name": "游戏服务器-01",
        "ip": "192.168.1.100",
        "sshPort": 22,
        "sshUsername": "root",
        "osType": "Ubuntu",
        "osVersion": "22.04 LTS",
        "cpuCores": 8,
        "memoryMb": 16384,
        "diskGb": 500,
        "status": 1,
        "lastCheckTime": "2026-03-22T10:30:00",
        "tags": "[\"游戏服务器\",\"生产环境\"]",
        "createTime": "2026-03-20T08:00:00",
        "updateTime": "2026-03-22T10:30:00"
      }
    ]
  },
  "timestamp": 1711084800000
}
```

---

### 4.2 获取主机详情

**接口**: `GET /api/hosts/{id}`

**描述**: 根据ID获取主机详情

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| id | Long | 是 | 主机ID |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| id | Long | 主机ID |
| name | String | 主机名称 |
| ip | String | IP地址 |
| sshPort | Integer | SSH端口 |
| sshUsername | String | SSH用户名 |
| osType | String | 操作系统类型 |
| osVersion | String | 操作系统版本 |
| cpuCores | Integer | CPU核心数 |
| memoryMb | Long | 内存大小(MB) |
| diskGb | Long | 磁盘大小(GB) |
| status | Integer | 状态：0-离线，1-在线 |
| lastCheckTime | DateTime | 最后检测时间 |
| tags | String | 标签(JSON格式) |
| createTime | DateTime | 创建时间 |
| updateTime | DateTime | 更新时间 |
| remark | String | 备注 |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "id": 1,
    "name": "游戏服务器-01",
    "ip": "192.168.1.100",
    "sshPort": 22,
    "sshUsername": "root",
    "osType": "Ubuntu",
    "osVersion": "22.04 LTS",
    "cpuCores": 8,
    "memoryMb": 16384,
    "diskGb": 500,
    "status": 1,
    "lastCheckTime": "2026-03-22T10:30:00",
    "tags": "[\"游戏服务器\",\"生产环境\"]",
    "createTime": "2026-03-20T08:00:00",
    "updateTime": "2026-03-22T10:30:00",
    "remark": "主游戏服务器"
  },
  "timestamp": 1711084800000
}
```

---

### 4.3 新增主机

**接口**: `POST /api/hosts`

**描述**: 新增主机

**认证**: 需要JWT令牌

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| name | String | 是 | 主机名称 |
| ip | String | 是 | IP地址 |
| sshPort | Integer | 否 | SSH端口，默认22 |
| sshUsername | String | 是 | SSH用户名 |
| sshPassword | String | 否 | SSH密码（与sshPrivateKey二选一） |
| sshPrivateKey | String | 否 | SSH私钥（与sshPassword二选一） |
| tags | String | 否 | 标签(JSON数组格式) |
| remark | String | 否 | 备注 |

**请求示例**:

```json
{
  "name": "游戏服务器-02",
  "ip": "192.168.1.101",
  "sshPort": 22,
  "sshUsername": "root",
  "sshPassword": "password123",
  "tags": "[\"游戏服务器\",\"测试环境\"]",
  "remark": "测试服务器"
}
```

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "id": 2
  },
  "timestamp": 1711084800000
}
```

---

### 4.4 更新主机

**接口**: `PUT /api/hosts/{id}`

**描述**: 更新主机信息

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| id | Long | 是 | 主机ID |

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| name | String | 否 | 主机名称 |
| sshPort | Integer | 否 | SSH端口 |
| sshUsername | String | 否 | SSH用户名 |
| sshPassword | String | 否 | SSH密码 |
| sshPrivateKey | String | 否 | SSH私钥 |
| tags | String | 否 | 标签(JSON数组格式) |
| remark | String | 否 | 备注 |

**请求示例**:

```json
{
  "name": "游戏服务器-02(已更新)",
  "sshPort": 2222,
  "tags": "[\"游戏服务器\",\"生产环境\"]",
  "remark": "已升级为生产服务器"
}
```

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": null,
  "timestamp": 1711084800000
}
```

---

### 4.5 删除主机

**接口**: `DELETE /api/hosts/{id}`

**描述**: 删除主机（逻辑删除）

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| id | Long | 是 | 主机ID |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": null,
  "timestamp": 1711084800000
}
```

---

### 4.6 测试主机连接

**接口**: `POST /api/hosts/{id}/test`

**描述**: 测试主机SSH连接是否正常

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| id | Long | 是 | 主机ID |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| connected | Boolean | 连接是否成功 |
| message | String | 连接结果消息 |
| testTime | Long | 测试时间戳(毫秒) |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "connected": true,
    "message": "连接成功",
    "testTime": 1774274742914
  },
  "timestamp": 1774274742915
}
```

---

### 4.7 获取主机状态

**接口**: `GET /api/hosts/{id}/status`

**描述**: 获取主机实时状态信息

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| id | Long | 是 | 主机ID |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| status | Integer | 在线状态：0-离线，1-在线 |
| cpuUsage | Double | CPU使用率(%) |
| memoryUsage | Double | 内存使用率(%) |
| diskUsage | Double | 磁盘使用率(%) |
| uptime | Long | 运行时长(秒) |
| loadAverage | String | 系统负载 |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "status": 1,
    "cpuUsage": 45.5,
    "memoryUsage": 62.3,
    "diskUsage": 35.8,
    "uptime": 864000,
    "loadAverage": "1.25, 1.10, 0.95"
  },
  "timestamp": 1711084800000
}
```

---

### 4.8 扫描端口占用

**接口**: `GET /api/hosts/{id}/ports`

**描述**: 扫描主机端口占用情况

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| id | Long | 是 | 主机ID |

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| startPort | Integer | 否 | 起始端口，默认1 |
| endPort | Integer | 否 | 结束端口，默认65535 |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| ports | Array | 占用端口列表 |
| ports[].port | Integer | 端口号 |
| ports[].protocol | String | 协议：TCP/UDP |
| ports[].service | String | 服务名称 |
| ports[].pid | Integer | 进程ID |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "ports": [
      {
        "port": 22,
        "protocol": "TCP",
        "service": "sshd",
        "pid": 1234
      },
      {
        "port": 2456,
        "protocol": "UDP",
        "service": "palworld-server",
        "pid": 5678
      }
    ]
  },
  "timestamp": 1711084800000
}
```

---

### 4.9 获取主机资源使用情况

**接口**: `GET /api/hosts/{id}/resources`

**描述**: 获取主机资源使用详情

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| id | Long | 是 | 主机ID |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| cpu | Object | CPU信息 |
| cpu.cores | Integer | CPU核心数 |
| cpu.usage | Double | CPU使用率(%) |
| cpu.model | String | CPU型号 |
| memory | Object | 内存信息 |
| memory.total | Long | 总内存(MB) |
| memory.used | Long | 已用内存(MB) |
| memory.free | Long | 空闲内存(MB) |
| memory.usage | Double | 内存使用率(%) |
| disk | Object | 磁盘信息 |
| disk.total | Long | 总磁盘(GB) |
| disk.used | Long | 已用磁盘(GB) |
| disk.free | Long | 空闲磁盘(GB) |
| disk.usage | Double | 磁盘使用率(%) |
| network | Object | 网络信息 |
| network.rxBytes | Long | 接收字节数 |
| network.txBytes | Long | 发送字节数 |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "cpu": {
      "cores": 8,
      "usage": 45.5,
      "model": "Intel(R) Xeon(R) CPU E5-2680 v4"
    },
    "memory": {
      "total": 16384,
      "used": 10240,
      "free": 6144,
      "usage": 62.5
    },
    "disk": {
      "total": 500,
      "used": 180,
      "free": 320,
      "usage": 36.0
    },
    "network": {
      "rxBytes": 1073741824,
      "txBytes": 536870912
    }
  },
  "timestamp": 1711084800000
}
```

---

## 5. 游戏元数据模块

**模块路径**: `/api/games`

### 5.1 获取游戏列表

**接口**: `GET /api/games/list`

**描述**: 获取所有游戏元数据列表

**认证**: 需要JWT令牌

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| keyword | String | 否 | 关键词搜索（游戏名称/编码） |
| status | Integer | 否 | 状态：0-禁用，1-启用 |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| id | Long | 元数据ID |
| gameCode | String | 游戏唯一编码 |
| gameName | String | 游戏名称 |
| gameIcon | String | 游戏图标URL |
| gameDesc | String | 游戏描述 |
| supportDeployType | String | 支持的部署方式(JSON数组) |
| defaultPort | Integer | 默认端口 |
| status | Integer | 状态：0-禁用，1-启用 |
| metadataVersion | String | 元数据版本号 |
| createTime | DateTime | 创建时间 |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": [
    {
      "id": 1,
      "gameCode": "palworld",
      "gameName": "幻兽帕鲁",
      "gameIcon": "/icons/palworld.png",
      "gameDesc": "幻兽帕鲁是一款支持多人在线的生存游戏",
      "supportDeployType": "[1, 2, 3]",
      "defaultPort": 2456,
      "status": 1,
      "metadataVersion": "1.0.0",
      "createTime": "2026-03-20T08:00:00"
    },
    {
      "id": 2,
      "gameCode": "mc",
      "gameName": "Minecraft",
      "gameIcon": "/icons/minecraft.png",
      "gameDesc": "Minecraft是一款沙盒建造游戏",
      "supportDeployType": "[1, 2, 3]",
      "defaultPort": 25565,
      "status": 1,
      "metadataVersion": "1.0.0",
      "createTime": "2026-03-20T08:00:00"
    }
  ],
  "timestamp": 1711084800000
}
```

---

### 5.2 获取游戏详情

**接口**: `GET /api/games/{id}`

**描述**: 根据ID获取游戏元数据详情

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| id | Long | 是 | 游戏元数据ID |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| id | Long | 元数据ID |
| gameCode | String | 游戏唯一编码 |
| gameName | String | 游戏名称 |
| gameIcon | String | 游戏图标URL |
| gameDesc | String | 游戏描述 |
| supportDeployType | String | 支持的部署方式(JSON数组)：1-LinuxGSM，2-Docker，3-Docker Compose |
| defaultPort | Integer | 默认端口 |
| defaultDependences | String | 环境依赖列表(JSON数组) |
| configSchema | String | 配置表单Schema(JSON格式) |
| customOperations | String | 自定义操作列表(JSON数组) |
| metadataFilePath | String | 元数据文件路径 |
| status | Integer | 状态：0-禁用，1-启用 |
| metadataVersion | String | 元数据版本号 |
| createTime | DateTime | 创建时间 |
| updateTime | DateTime | 更新时间 |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "id": 1,
    "gameCode": "palworld",
    "gameName": "幻兽帕鲁",
    "gameIcon": "/icons/palworld.png",
    "gameDesc": "幻兽帕鲁是一款支持多人在线的生存游戏",
    "supportDeployType": "[1, 2, 3]",
    "defaultPort": 2456,
    "defaultDependences": "[\"steamcmd\"]",
    "configSchema": "{\"serverName\":{\"type\":\"text\",\"label\":\"服务器名称\",\"default\":\"My Server\"},\"maxPlayers\":{\"type\":\"number\",\"label\":\"最大玩家数\",\"default\":32}}",
    "customOperations": "[{\"name\":\"更新服务器\",\"command\":\"./steamcmd +app_update 2394010\"}]",
    "metadataFilePath": "/data/metadata/palworld.yaml",
    "status": 1,
    "metadataVersion": "1.0.0",
    "createTime": "2026-03-20T08:00:00",
    "updateTime": "2026-03-22T10:30:00"
  },
  "timestamp": 1711084800000
}
```

---

### 5.3 新增游戏元数据

**接口**: `POST /api/games`

**描述**: 新增游戏元数据

**认证**: 需要JWT令牌

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| gameCode | String | 是 | 游戏唯一编码 |
| gameName | String | 是 | 游戏名称 |
| gameIcon | String | 否 | 游戏图标URL |
| gameDesc | String | 否 | 游戏描述 |
| supportDeployType | String | 是 | 支持的部署方式(JSON数组) |
| defaultPort | Integer | 否 | 默认端口 |
| defaultDependences | String | 否 | 环境依赖列表(JSON数组) |
| configSchema | String | 否 | 配置表单Schema(JSON格式) |
| customOperations | String | 否 | 自定义操作列表(JSON数组) |
| metadataFilePath | String | 是 | 元数据文件路径 |
| status | Integer | 否 | 状态，默认1 |

**请求示例**:

```json
{
  "gameCode": "l4d2",
  "gameName": "求生之路2",
  "gameIcon": "/icons/l4d2.png",
  "gameDesc": "Left 4 Dead 2是一款合作生存恐怖游戏",
  "supportDeployType": "[1, 2]",
  "defaultPort": 27015,
  "defaultDependences": "[\"steamcmd\"]",
  "configSchema": "{\"hostname\":{\"type\":\"text\",\"label\":\"服务器名称\",\"default\":\"L4D2 Server\"}}",
  "customOperations": "[]",
  "metadataFilePath": "/data/metadata/l4d2.yaml",
  "status": 1
}
```

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "id": 3
  },
  "timestamp": 1711084800000
}
```

---

### 5.4 更新游戏元数据

**接口**: `PUT /api/games/{id}`

**描述**: 更新游戏元数据

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| id | Long | 是 | 游戏元数据ID |

**请求参数**: 同新增游戏元数据

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": null,
  "timestamp": 1711084800000
}
```

---

### 5.5 删除游戏元数据

**接口**: `DELETE /api/games/{id}`

**描述**: 删除游戏元数据（逻辑删除）

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| id | Long | 是 | 游戏元数据ID |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": null,
  "timestamp": 1711084800000
}
```

---

## 6. 游戏实例模块

**模块路径**: `/api/instances`

### 6.1 获取实例列表

**接口**: `GET /api/instances`

**描述**: 分页获取游戏实例列表

**认证**: 需要JWT令牌

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| current | Integer | 否 | 当前页码，默认1 |
| size | Integer | 否 | 每页大小，默认10 |
| name | String | 否 | 实例名称（模糊查询） |
| hostId | Long | 否 | 主机ID |
| gameCode | String | 否 | 游戏编码 |
| status | Integer | 否 | 运行状态：0-已停止，1-运行中，2-异常 |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| records | Array | 实例列表 |
| records[].id | Long | 实例ID |
| records[].name | String | 实例名称 |
| records[].gameType | String | 游戏类型 |
| records[].gameVersion | String | 游戏版本 |
| records[].hostId | Long | 主机ID |
| records[].hostName | String | 主机名称 |
| records[].deployPath | String | 部署路径 |
| records[].port | Integer | 端口号 |
| records[].status | Integer | 运行状态：0-已停止，1-运行中，2-异常 |
| records[].processId | Integer | 进程ID |
| records[].autoRestart | Integer | 自动重启：0-否，1-是 |
| records[].lastStartTime | DateTime | 最后启动时间 |
| records[].createTime | DateTime | 创建时间 |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "current": 1,
    "size": 10,
    "total": 3,
    "pages": 1,
    "records": [
      {
        "id": 1,
        "name": "Palworld-Server-01",
        "gameType": "palworld",
        "gameVersion": "latest",
        "hostId": 1,
        "hostName": "游戏服务器-01",
        "deployPath": "/opt/gameplatform/instances/palworld-01",
        "port": 2456,
        "status": 1,
        "processId": 12345,
        "autoRestart": 1,
        "lastStartTime": "2026-03-22T08:00:00",
        "createTime": "2026-03-20T08:00:00"
      }
    ]
  },
  "timestamp": 1711084800000
}
```

---

### 6.2 获取实例详情

**接口**: `GET /api/instances/{id}`

**描述**: 根据ID获取实例详情

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| id | Long | 是 | 实例ID |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| id | Long | 实例ID |
| name | String | 实例名称 |
| gameType | String | 游戏类型 |
| gameVersion | String | 游戏版本 |
| hostId | Long | 主机ID |
| hostInfo | Object | 主机信息 |
| deployPath | String | 部署路径 |
| port | Integer | 端口号 |
| status | Integer | 运行状态：0-已停止，1-运行中，2-异常 |
| processId | Integer | 进程ID |
| startArgs | String | 启动参数 |
| configPath | String | 配置文件路径 |
| logPath | String | 日志路径 |
| autoRestart | Integer | 自动重启：0-否，1-是 |
| lastStartTime | DateTime | 最后启动时间 |
| lastStopTime | DateTime | 最后停止时间 |
| createTime | DateTime | 创建时间 |
| updateTime | DateTime | 更新时间 |
| remark | String | 备注 |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "id": 1,
    "name": "Palworld-Server-01",
    "gameType": "palworld",
    "gameVersion": "latest",
    "hostId": 1,
    "hostInfo": {
      "id": 1,
      "name": "游戏服务器-01",
      "ip": "192.168.1.100"
    },
    "deployPath": "/opt/gameplatform/instances/palworld-01",
    "port": 2456,
    "status": 1,
    "processId": 12345,
    "startArgs": "-port=2456 -players=32",
    "configPath": "/opt/gameplatform/instances/palworld-01/config",
    "logPath": "/opt/gameplatform/instances/palworld-01/logs",
    "autoRestart": 1,
    "lastStartTime": "2026-03-22T08:00:00",
    "lastStopTime": null,
    "createTime": "2026-03-20T08:00:00",
    "updateTime": "2026-03-22T08:00:00",
    "remark": "主服务器"
  },
  "timestamp": 1711084800000
}
```

---

### 6.3 创建实例

**接口**: `POST /api/instances`

**描述**: 创建游戏实例（部署游戏）

**认证**: 需要JWT令牌

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| name | String | 是 | 实例名称 |
| gameType | String | 是 | 游戏类型（游戏编码） |
| gameVersion | String | 否 | 游戏版本 |
| hostId | Long | 是 | 主机ID |
| deployPath | String | 是 | 部署路径 |
| port | Integer | 是 | 端口号 |
| startArgs | String | 否 | 启动参数 |
| autoRestart | Integer | 否 | 自动重启：0-否，1-是，默认0 |
| remark | String | 否 | 备注 |

**请求示例**:

```json
{
  "name": "Palworld-Server-02",
  "gameType": "palworld",
  "gameVersion": "latest",
  "hostId": 1,
  "deployPath": "/opt/gameplatform/instances/palworld-02",
  "port": 2457,
  "startArgs": "-port=2457 -players=16",
  "autoRestart": 1,
  "remark": "测试服务器"
}
```

**响应示例**:

```json
{
  "code": 200,
  "message": "实例创建成功，正在部署中",
  "data": {
    "id": 2,
    "deployTaskId": "task-uuid-xxx"
  },
  "timestamp": 1711084800000
}
```

---

### 6.4 更新实例配置

**接口**: `PUT /api/instances/{id}`

**描述**: 更新实例配置信息

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| id | Long | 是 | 实例ID |

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| name | String | 否 | 实例名称 |
| port | Integer | 否 | 端口号 |
| startArgs | String | 否 | 启动参数 |
| autoRestart | Integer | 否 | 自动重启 |
| remark | String | 否 | 备注 |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": null,
  "timestamp": 1711084800000
}
```

---

### 6.5 删除实例

**接口**: `DELETE /api/instances/{id}`

**描述**: 删除实例（卸载游戏）

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| id | Long | 是 | 实例ID |

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| deleteFiles | Boolean | 否 | 是否删除文件，默认false |

**响应示例**:

```json
{
  "code": 200,
  "message": "实例删除成功",
  "data": null,
  "timestamp": 1711084800000
}
```

---

### 6.6 启动实例

**接口**: `POST /api/instances/{id}/start`

**描述**: 启动游戏实例

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| id | Long | 是 | 实例ID |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| success | Boolean | 是否启动成功 |
| processId | Integer | 进程ID |
| message | String | 启动结果消息 |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "success": true,
    "processId": 12345,
    "message": "实例启动成功"
  },
  "timestamp": 1711084800000
}
```

---

### 6.7 停止实例

**接口**: `POST /api/instances/{id}/stop`

**描述**: 停止游戏实例

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| id | Long | 是 | 实例ID |

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| force | Boolean | 否 | 是否强制停止，默认false |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "success": true,
    "message": "实例已停止"
  },
  "timestamp": 1711084800000
}
```

---

### 6.8 重启实例

**接口**: `POST /api/instances/{id}/restart`

**描述**: 重启游戏实例

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| id | Long | 是 | 实例ID |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "success": true,
    "processId": 12346,
    "message": "实例重启成功"
  },
  "timestamp": 1711084800000
}
```

---

### 6.9 获取实例日志

**接口**: `GET /api/instances/{id}/logs`

**描述**: 获取实例运行日志

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| id | Long | 是 | 实例ID |

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| lines | Integer | 否 | 日志行数，默认100，最大1000 |
| keyword | String | 否 | 关键词过滤 |
| startTime | DateTime | 否 | 开始时间 |
| endTime | DateTime | 否 | 结束时间 |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| logs | Array | 日志列表 |
| logs[].time | DateTime | 日志时间 |
| logs[].level | String | 日志级别 |
| logs[].content | String | 日志内容 |
| total | Integer | 总行数 |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "logs": [
      {
        "time": "2026-03-22T10:30:00",
        "level": "INFO",
        "content": "[Server] Server started on port 2456"
      },
      {
        "time": "2026-03-22T10:30:01",
        "level": "INFO",
        "content": "[Server] Max players: 32"
      }
    ],
    "total": 2
  },
  "timestamp": 1711084800000
}
```

---

### 6.10 获取实例配置

**接口**: `GET /api/instances/{id}/config`

**描述**: 获取实例配置文件内容

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| id | Long | 是 | 实例ID |

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| configFile | String | 否 | 配置文件名，不传则返回默认配置文件 |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| fileName | String | 文件名 |
| content | String | 文件内容 |
| lastModified | DateTime | 最后修改时间 |
| size | Long | 文件大小(字节) |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "fileName": "PalWorldSettings.ini",
    "content": "[/Script/Pal.PalGameWorldSettings]\nOptionSettings=(Difficulty=None,DayTimeSpeedRate=1.000000...)",
    "lastModified": "2026-03-22T10:00:00",
    "size": 2048
  },
  "timestamp": 1711084800000
}
```

---

### 6.11 更新实例配置

**接口**: `PUT /api/instances/{id}/config`

**描述**: 更新实例配置文件

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| id | Long | 是 | 实例ID |

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| configFile | String | 否 | 配置文件名 |
| content | String | 是 | 配置文件内容 |
| restart | Boolean | 否 | 是否重启实例使配置生效，默认false |

**请求示例**:

```json
{
  "configFile": "PalWorldSettings.ini",
  "content": "[/Script/Pal.PalGameWorldSettings]\nOptionSettings=(Difficulty=None,DayTimeSpeedRate=1.000000...)",
  "restart": false
}
```

**响应示例**:

```json
{
  "code": 200,
  "message": "配置更新成功",
  "data": null,
  "timestamp": 1711084800000
}
```

---

### 6.12 获取文件列表

**接口**: `GET /api/instances/{id}/files`

**描述**: 获取实例目录下的文件列表

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| id | Long | 是 | 实例ID |

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| path | String | 否 | 目录路径，默认为实例根目录 |
| showHidden | Boolean | 否 | 是否显示隐藏文件，默认false |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| currentPath | String | 当前路径 |
| files | Array | 文件列表 |
| files[].name | String | 文件名 |
| files[].path | String | 文件路径 |
| files[].isDirectory | Boolean | 是否为目录 |
| files[].size | Long | 文件大小(字节) |
| files[].lastModified | DateTime | 最后修改时间 |
| files[].permissions | String | 文件权限 |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "currentPath": "/opt/gameplatform/instances/palworld-01",
    "files": [
      {
        "name": "config",
        "path": "/opt/gameplatform/instances/palworld-01/config",
        "isDirectory": true,
        "size": 4096,
        "lastModified": "2026-03-22T10:00:00",
        "permissions": "drwxr-xr-x"
      },
      {
        "name": "PalWorldSettings.ini",
        "path": "/opt/gameplatform/instances/palworld-01/PalWorldSettings.ini",
        "isDirectory": false,
        "size": 2048,
        "lastModified": "2026-03-22T10:00:00",
        "permissions": "-rw-r--r--"
      }
    ]
  },
  "timestamp": 1711084800000
}
```

---

### 6.13 下载文件

**接口**: `GET /api/instances/{id}/files/download`

**描述**: 下载实例文件

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| id | Long | 是 | 实例ID |

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| path | String | 是 | 文件路径 |

**响应**: 文件流（Content-Type: application/octet-stream）

**响应示例**:

```
HTTP/1.1 200 OK
Content-Type: application/octet-stream
Content-Disposition: attachment; filename="PalWorldSettings.ini"

[文件二进制内容]
```

---

### 6.14 上传文件

**接口**: `POST /api/instances/{id}/files/upload`

**描述**: 上传文件到实例目录

**认证**: 需要JWT令牌

**Content-Type**: `multipart/form-data`

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| id | Long | 是 | 实例ID |

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| file | File | 是 | 上传的文件 |
| path | String | 否 | 目标目录路径，默认为实例根目录 |
| overwrite | Boolean | 否 | 是否覆盖已存在的文件，默认false |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| fileName | String | 文件名 |
| filePath | String | 文件路径 |
| size | Long | 文件大小 |

**响应示例**:

```json
{
  "code": 200,
  "message": "文件上传成功",
  "data": {
    "fileName": "backup.tar.gz",
    "filePath": "/opt/gameplatform/instances/palworld-01/backup.tar.gz",
    "size": 1048576
  },
  "timestamp": 1711084800000
}
```

---

### 6.15 删除文件

**接口**: `DELETE /api/instances/{id}/files`

**描述**: 删除实例文件

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| id | Long | 是 | 实例ID |

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| path | String | 是 | 文件路径 |

**响应示例**:

```json
{
  "code": 200,
  "message": "文件删除成功",
  "data": null,
  "timestamp": 1711084800000
}
```

---

## 7. 插件管理模块

**模块路径**: `/api/plugins`

### 7.1 获取插件列表

**接口**: `GET /api/plugins/list`

**描述**: 获取所有插件列表

**认证**: 需要JWT令牌

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| keyword | String | 否 | 关键词搜索 |
| status | Integer | 否 | 状态：0-禁用，1-启用 |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| id | Long | 插件ID |
| pluginId | String | 插件唯一标识 |
| pluginName | String | 插件名称 |
| pluginVersion | String | 插件版本号 |
| pluginDesc | String | 插件描述 |
| author | String | 插件作者 |
| pluginClass | String | 插件主类 |
| pluginPath | String | 插件路径 |
| status | Integer | 状态：0-禁用，1-启用 |
| installTime | DateTime | 安装时间 |
| createTime | DateTime | 创建时间 |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": [
    {
      "id": 1,
      "pluginId": "auto-backup",
      "pluginName": "自动备份插件",
      "pluginVersion": "1.0.0",
      "pluginDesc": "定时自动备份游戏实例数据",
      "author": "GamePlatform",
      "pluginClass": "com.gameplatform.plugin.AutoBackupPlugin",
      "pluginPath": "/opt/gameplatform/plugins/auto-backup.jar",
      "status": 1,
      "installTime": "2026-03-20T08:00:00",
      "createTime": "2026-03-20T08:00:00"
    }
  ],
  "timestamp": 1711084800000
}
```

---

### 7.2 获取插件详情

**接口**: `GET /api/plugins/{id}`

**描述**: 根据ID获取插件详情

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| id | Long | 是 | 插件ID |

**响应参数**: 同插件列表中的单个对象

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "id": 1,
    "pluginId": "auto-backup",
    "pluginName": "自动备份插件",
    "pluginVersion": "1.0.0",
    "pluginDesc": "定时自动备份游戏实例数据",
    "author": "GamePlatform",
    "pluginClass": "com.gameplatform.plugin.AutoBackupPlugin",
    "pluginPath": "/opt/gameplatform/plugins/auto-backup.jar",
    "status": 1,
    "installTime": "2026-03-20T08:00:00",
    "createTime": "2026-03-20T08:00:00",
    "updateTime": "2026-03-22T10:00:00"
  },
  "timestamp": 1711084800000
}
```

---

### 7.3 启用插件

**接口**: `PUT /api/plugins/{id}/enable`

**描述**: 启用插件

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| id | Long | 是 | 插件ID |

**响应示例**:

```json
{
  "code": 200,
  "message": "插件启用成功",
  "data": null,
  "timestamp": 1711084800000
}
```

---

### 7.4 禁用插件

**接口**: `PUT /api/plugins/{id}/disable`

**描述**: 禁用插件

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| id | Long | 是 | 插件ID |

**响应示例**:

```json
{
  "code": 200,
  "message": "插件禁用成功",
  "data": null,
  "timestamp": 1711084800000
}
```

---

### 7.5 安装插件

**接口**: `POST /api/plugins`

**描述**: 安装新插件

**认证**: 需要JWT令牌

**Content-Type**: `multipart/form-data`

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| file | File | 是 | 插件文件(.jar) |

**响应示例**:

```json
{
  "code": 200,
  "message": "插件安装成功",
  "data": {
    "id": 2,
    "pluginId": "monitor-alert"
  },
  "timestamp": 1711084800000
}
```

---

### 7.6 卸载插件

**接口**: `DELETE /api/plugins/{id}`

**描述**: 卸载插件

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| id | Long | 是 | 插件ID |

**响应示例**:

```json
{
  "code": 200,
  "message": "插件卸载成功",
  "data": null,
  "timestamp": 1711084800000
}
```

---

## 8. 系统设置模块

**模块路径**: `/api/system`

### 8.1 获取系统设置

**接口**: `GET /api/system/settings`

**描述**: 获取系统全局设置

**认证**: 需要JWT令牌

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| settings | Array | 设置项列表 |
| settings[].configKey | String | 配置键 |
| settings[].configValue | String | 配置值 |
| settings[].configName | String | 配置名称 |
| settings[].configDesc | String | 配置描述 |
| settings[].isSystem | Integer | 是否系统内置：0-否，1-是 |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "settings": [
      {
        "configKey": "platform.name",
        "configValue": "游戏服务器统一管理平台",
        "configName": "平台名称",
        "configDesc": "系统显示的平台名称",
        "isSystem": 1
      },
      {
        "configKey": "ssh.timeout",
        "configValue": "30",
        "configName": "SSH全局默认超时时间（秒）",
        "configDesc": "SSH连接的默认超时时间",
        "isSystem": 1
      },
      {
        "configKey": "backup.default_path",
        "configValue": "./data/backup",
        "configName": "默认备份存储路径",
        "configDesc": "备份文件的默认存储路径",
        "isSystem": 1
      }
    ]
  },
  "timestamp": 1711084800000
}
```

---

### 8.2 更新系统设置

**接口**: `PUT /api/system/settings`

**描述**: 更新系统全局设置

**认证**: 需要JWT令牌

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| settings | Array | 是 | 设置项列表 |
| settings[].configKey | String | 是 | 配置键 |
| settings[].configValue | String | 是 | 配置值 |

**请求示例**:

```json
{
  "settings": [
    {
      "configKey": "platform.name",
      "configValue": "我的游戏平台"
    },
    {
      "configKey": "ssh.timeout",
      "configValue": "60"
    }
  ]
}
```

**响应示例**:

```json
{
  "code": 200,
  "message": "设置更新成功",
  "data": null,
  "timestamp": 1711084800000
}
```

---

### 8.3 获取操作日志

**接口**: `GET /api/system/logs`

**描述**: 分页获取操作日志

**认证**: 需要JWT令牌

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| current | Integer | 否 | 当前页码，默认1 |
| size | Integer | 否 | 每页大小，默认10 |
| operatorName | String | 否 | 操作人用户名 |
| operationModule | String | 否 | 操作模块 |
| operationType | String | 否 | 操作类型 |
| responseStatus | Integer | 否 | 响应状态：0-失败，1-成功 |
| startTime | DateTime | 否 | 开始时间 |
| endTime | DateTime | 否 | 结束时间 |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| records | Array | 日志列表 |
| records[].id | Long | 日志ID |
| records[].operatorId | Long | 操作人ID |
| records[].operatorName | String | 操作人用户名 |
| records[].operationModule | String | 操作模块 |
| records[].operationType | String | 操作类型 |
| records[].operationContent | String | 操作内容 |
| records[].requestIp | String | 请求IP |
| records[].requestMethod | String | 请求方法 |
| records[].requestUri | String | 请求URI |
| records[].requestParams | String | 请求参数(JSON) |
| records[].responseStatus | Integer | 响应状态：0-失败，1-成功 |
| records[].responseMsg | String | 响应消息 |
| records[].costTime | Integer | 耗时(毫秒) |
| records[].operationTime | DateTime | 操作时间 |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "current": 1,
    "size": 10,
    "total": 50,
    "pages": 5,
    "records": [
      {
        "id": 1,
        "operatorId": 1,
        "operatorName": "admin",
        "operationModule": "主机管理",
        "operationType": "新增",
        "operationContent": "新增主机：游戏服务器-01",
        "requestIp": "192.168.1.50",
        "requestMethod": "POST",
        "requestUri": "/api/hosts",
        "requestParams": "{\"name\":\"游戏服务器-01\",\"ip\":\"192.168.1.100\"}",
        "responseStatus": 1,
        "responseMsg": "操作成功",
        "costTime": 150,
        "operationTime": "2026-03-22T10:30:00"
      }
    ]
  },
  "timestamp": 1711084800000
}
```

---

### 8.4 健康检查

**接口**: `GET /api/system/health`

**描述**: 系统健康检查接口

**认证**: 不需要JWT令牌

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| status | String | 状态：UP/DOWN |
| timestamp | DateTime | 当前时间 |
| version | String | 系统版本 |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "status": "UP",
    "timestamp": "2026-03-22T10:30:00",
    "version": "1.0.0"
  },
  "timestamp": 1711084800000
}
```

---

### 8.5 获取系统信息

**接口**: `GET /api/system/info`

**描述**: 获取系统基本信息

**认证**: 不需要JWT令牌

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| name | String | 系统名称 |
| version | String | 系统版本 |
| description | String | 系统描述 |
| javaVersion | String | Java版本 |
| osName | String | 操作系统名称 |
| osVersion | String | 操作系统版本 |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "name": "Game Platform Manager",
    "version": "1.0.0",
    "description": "游戏服务器统一管理平台",
    "javaVersion": "17.0.2",
    "osName": "Linux",
    "osVersion": "5.15.0-91-generic"
  },
  "timestamp": 1711084800000
}
```

---

## 9. 错误码说明

### 9.1 HTTP状态码

| 状态码 | 说明 |
|--------|------|
| 200 | 请求成功 |
| 400 | 请求参数错误 |
| 401 | 未授权，需要登录 |
| 403 | 无权限访问 |
| 404 | 资源不存在 |
| 405 | 请求方法不允许 |
| 409 | 资源冲突 |
| 500 | 服务器内部错误 |
| 503 | 服务不可用 |

### 9.2 业务错误码

| 错误码 | 错误信息 | 说明 |
|--------|----------|------|
| 200 | 操作成功 | 成功 |
| 400 | 操作失败 | 通用失败 |
| 400 | 参数校验失败 | 参数验证错误 |
| 401 | 未授权,请先登录 | 未登录或Token无效 |
| 401 | Token已过期,请重新登录 | Token过期 |
| 401 | Token无效 | Token格式错误或被篡改 |
| 403 | 没有相关权限 | 权限不足 |
| 404 | 资源不存在 | 请求的资源不存在 |
| 405 | 请求方法不允许 | HTTP方法不支持 |
| 409 | 资源冲突 | 资源已存在 |
| 500 | 服务器内部错误 | 系统异常 |
| 503 | 服务不可用 | 服务暂时不可用 |

### 9.3 业务错误码(1xxx)

| 错误码 | 错误信息 | 说明 |
|--------|----------|------|
| 1001 | 用户不存在 | 登录时用户不存在 |
| 1002 | 密码错误 | 登录密码错误 |
| 1003 | 用户已被禁用 | 用户状态为禁用 |
| 1004 | 用户已存在 | 注册时用户名已存在 |
| 1101 | 主机不存在 | 主机ID不存在 |
| 1102 | 主机连接失败 | SSH连接失败 |
| 1103 | 主机已存在 | 主机IP+端口已存在 |
| 1201 | 游戏实例不存在 | 实例ID不存在 |
| 1202 | 游戏实例已在运行 | 启动时实例已运行 |
| 1203 | 游戏实例未运行 | 停止时实例未运行 |
| 1301 | 部署失败 | 实例部署失败 |
| 1302 | 部署配置错误 | 部署配置不正确 |
| 1401 | 备份不存在 | 备份记录不存在 |
| 1402 | 备份失败 | 备份操作失败 |
| 1403 | 恢复失败 | 恢复操作失败 |
| 1501 | 插件不存在 | 插件ID不存在 |
| 1502 | 插件加载失败 | 插件加载异常 |
| 1503 | 插件已存在 | 插件ID已存在 |
| 1601 | 文件不存在 | 文件路径不存在 |
| 1602 | 文件上传失败 | 文件上传异常 |
| 1603 | 文件下载失败 | 文件下载异常 |

### 9.4 错误响应示例

```json
{
  "code": 1102,
  "message": "主机连接失败",
  "data": {
    "hostId": 1,
    "ip": "192.168.1.100",
    "error": "Connection refused"
  },
  "timestamp": 1711084800000
}
```

---

## 附录

### A. 日期时间格式

所有日期时间字段使用ISO 8601格式：`yyyy-MM-ddTHH:mm:ss`

示例：`2026-03-22T10:30:00`

### B. 文件大小单位

| 单位 | 说明 |
|------|------|
| B | 字节 |
| KB | 千字节 |
| MB | 兆字节 |
| GB | 吉字节 |

### C. 状态枚举值

**主机状态**:
- 0: 离线
- 1: 在线

**实例运行状态**:
- 0: 已停止
- 1: 运行中
- 2: 异常
- 3: 部署中
- 4: 卸载中

**用户状态**:
- 0: 禁用
- 1: 启用

**插件状态**:
- 0: 禁用
- 1: 启用

**备份状态**:
- 0: 失败
- 1: 成功
- 2: 进行中

**还原状态**:
- 0: 未还原
- 1: 还原中
- 2: 还原成功
- 3: 还原失败

---

---

## 10. Docker 实例管理模块

**模块路径**: `/api/docker`

本模块提供独立的 Docker 容器管理能力，支持容器与游戏实例的关联管理，提供容器文件管理、终端、监控等功能。

### 10.1 容器管理 API

#### 10.1.1 获取容器列表

**接口**: `GET /api/docker/hosts/{hostId}/containers`

**描述**: 获取指定主机上的 Docker 容器列表

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| hostId | Long | 是 | 主机ID |

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| status | String | 否 | 状态筛选：running/stopped/paused/all，默认all |
| keyword | String | 否 | 关键词搜索（容器名称/镜像名称） |
| linked | Boolean | 否 | 关联状态筛选：true-已关联，false-未关联 |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| containers | Array | 容器列表 |
| containers[].containerId | String | 容器ID（短ID） |
| containers[].containerName | String | 容器名称 |
| containers[].imageName | String | 镜像名称 |
| containers[].imageTag | String | 镜像标签 |
| containers[].status | String | 状态：running/stopped/paused/restarting |
| containers[].state | String | 详细状态信息 |
| containers[].createdAt | DateTime | 创建时间 |
| containers[].ports | Array | 端口映射列表 |
| containers[].ports[].containerPort | Integer | 容器端口 |
| containers[].ports[].hostPort | Integer | 主机端口 |
| containers[].ports[].protocol | String | 协议：tcp/udp |
| containers[].cpuUsage | Double | CPU使用率(%) |
| containers[].memoryUsage | Double | 内存使用率(%) |
| containers[].memoryUsed | Long | 已用内存(MB) |
| containers[].memoryLimit | Long | 内存限制(MB) |
| containers[].isLinked | Boolean | 是否已关联到实例 |
| containers[].linkedInstanceId | Long | 关联的实例ID |
| containers[].linkedInstanceName | String | 关联的实例名称 |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "containers": [
      {
        "containerId": "a1b2c3d4e5f6",
        "containerName": "palworld-server",
        "imageName": "mygame/palworld",
        "imageTag": "latest",
        "status": "running",
        "state": "Up 2 hours",
        "createdAt": "2026-03-24T08:00:00",
        "ports": [
          {
            "containerPort": 2456,
            "hostPort": 2456,
            "protocol": "udp"
          },
          {
            "containerPort": 8211,
            "hostPort": 8211,
            "protocol": "udp"
          }
        ],
        "cpuUsage": 15.5,
        "memoryUsage": 45.2,
        "memoryUsed": 1843,
        "memoryLimit": 4096,
        "isLinked": true,
        "linkedInstanceId": 1,
        "linkedInstanceName": "Palworld-Server-01"
      },
      {
        "containerId": "g7h8i9j0k1l2",
        "containerName": "minecraft-server",
        "imageName": "itzg/minecraft-server",
        "imageTag": "latest",
        "status": "stopped",
        "state": "Exited (0) 1 hour ago",
        "createdAt": "2026-03-23T10:00:00",
        "ports": [
          {
            "containerPort": 25565,
            "hostPort": 25565,
            "protocol": "tcp"
          }
        ],
        "cpuUsage": 0,
        "memoryUsage": 0,
        "memoryUsed": 0,
        "memoryLimit": 0,
        "isLinked": false,
        "linkedInstanceId": null,
        "linkedInstanceName": null
      }
    ]
  },
  "timestamp": 1711084800000
}
```

---

#### 10.1.2 获取容器详情

**接口**: `GET /api/docker/hosts/{hostId}/containers/{containerId}`

**描述**: 获取指定容器的详细信息

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| hostId | Long | 是 | 主机ID |
| containerId | String | 是 | 容器ID（短ID或完整ID） |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| containerId | String | 容器完整ID |
| containerIdShort | String | 容器短ID |
| containerName | String | 容器名称 |
| imageName | String | 镜像名称 |
| imageId | String | 镜像ID |
| status | String | 状态：running/stopped/paused/restarting |
| state | String | 详细状态信息 |
| createdAt | DateTime | 创建时间 |
| startedAt | DateTime | 启动时间 |
| finishedAt | DateTime | 结束时间 |
| ports | Array | 端口映射列表 |
| networks | Array | 网络配置列表 |
| networks[].networkName | String | 网络名称 |
| networks[].ipAddress | String | IP地址 |
| networks[].gateway | String | 网关 |
| volumes | Array | 挂载卷列表 |
| volumes[].source | String | 源路径 |
| volumes[].destination | String | 目标路径 |
| volumes[].mode | String | 挂载模式 |
| env | Array | 环境变量列表 |
| command | String | 启动命令 |
| labels | Object | 标签信息 |
| isLinked | Boolean | 是否已关联 |
| linkInfo | Object | 关联信息 |
| linkInfo.id | Long | 关联记录ID |
| linkInfo.instanceId | Long | 实例ID |
| linkInfo.instanceName | String | 实例名称 |
| linkInfo.linkType | String | 关联类型：instance/host |
| linkInfo.autoLinked | Boolean | 是否自动关联 |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "containerId": "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0",
    "containerIdShort": "a1b2c3d4e5f6",
    "containerName": "palworld-server",
    "imageName": "mygame/palworld",
    "imageId": "sha256:abc123def456",
    "status": "running",
    "state": "Up 2 hours",
    "createdAt": "2026-03-24T08:00:00",
    "startedAt": "2026-03-24T08:05:00",
    "finishedAt": null,
    "ports": [
      {
        "containerPort": 2456,
        "hostPort": 2456,
        "protocol": "udp"
      }
    ],
    "networks": [
      {
        "networkName": "bridge",
        "ipAddress": "172.17.0.2",
        "gateway": "172.17.0.1"
      }
    ],
    "volumes": [
      {
        "source": "/opt/gameplatform/instances/palworld-01/data",
        "destination": "/palworld",
        "mode": "rw"
      }
    ],
    "env": [
      "SERVER_NAME=My Server",
      "MAX_PLAYERS=32"
    ],
    "command": "/start.sh",
    "labels": {
      "maintainer": "gameplatform",
      "version": "1.0.0"
    },
    "isLinked": true,
    "linkInfo": {
      "id": 1,
      "instanceId": 1,
      "instanceName": "Palworld-Server-01",
      "linkType": "instance",
      "autoLinked": false
    }
  },
  "timestamp": 1711084800000
}
```

---

#### 10.1.3 启动容器

**接口**: `POST /api/docker/hosts/{hostId}/containers/{containerId}/start`

**描述**: 启动已停止的容器

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| hostId | Long | 是 | 主机ID |
| containerId | String | 是 | 容器ID |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| success | Boolean | 是否启动成功 |
| containerId | String | 容器ID |
| message | String | 操作结果消息 |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "success": true,
    "containerId": "a1b2c3d4e5f6",
    "message": "容器启动成功"
  },
  "timestamp": 1711084800000
}
```

---

#### 10.1.4 停止容器

**接口**: `POST /api/docker/hosts/{hostId}/containers/{containerId}/stop`

**描述**: 停止运行中的容器

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| hostId | Long | 是 | 主机ID |
| containerId | String | 是 | 容器ID |

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| force | Boolean | 否 | 是否强制停止，默认false |
| timeout | Integer | 否 | 超时时间(秒)，默认10 |

**请求示例**:

```json
{
  "force": false,
  "timeout": 10
}
```

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| success | Boolean | 是否停止成功 |
| containerId | String | 容器ID |
| message | String | 操作结果消息 |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "success": true,
    "containerId": "a1b2c3d4e5f6",
    "message": "容器已停止"
  },
  "timestamp": 1711084800000
}
```

---

#### 10.1.5 重启容器

**接口**: `POST /api/docker/hosts/{hostId}/containers/{containerId}/restart`

**描述**: 重启容器

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| hostId | Long | 是 | 主机ID |
| containerId | String | 是 | 容器ID |

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| timeout | Integer | 否 | 超时时间(秒)，默认10 |

**请求示例**:

```json
{
  "timeout": 10
}
```

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| success | Boolean | 是否重启成功 |
| containerId | String | 容器ID |
| message | String | 操作结果消息 |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "success": true,
    "containerId": "a1b2c3d4e5f6",
    "message": "容器重启成功"
  },
  "timestamp": 1711084800000
}
```

---

#### 10.1.6 删除容器

**接口**: `DELETE /api/docker/hosts/{hostId}/containers/{containerId}`

**描述**: 删除容器

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| hostId | Long | 是 | 主机ID |
| containerId | String | 是 | 容器ID |

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| force | Boolean | 否 | 是否强制删除（运行中的容器需强制删除），默认false |
| volumes | Boolean | 否 | 是否删除关联的匿名卷，默认false |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| success | Boolean | 是否删除成功 |
| containerId | String | 容器ID |
| message | String | 操作结果消息 |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "success": true,
    "containerId": "a1b2c3d4e5f6",
    "message": "容器已删除"
  },
  "timestamp": 1711084800000
}
```

---

#### 10.1.7 获取容器资源统计

**接口**: `GET /api/docker/hosts/{hostId}/containers/{containerId}/stats`

**描述**: 获取容器实时资源使用统计

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| hostId | Long | 是 | 主机ID |
| containerId | String | 是 | 容器ID |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| containerId | String | 容器ID |
| containerName | String | 容器名称 |
| cpu | Object | CPU统计 |
| cpu.usagePercent | Double | CPU使用率(%) |
| cpu.systemUsage | Long | 系统CPU使用量 |
| cpu.totalUsage | Long | 总CPU使用量 |
| memory | Object | 内存统计 |
| memory.usagePercent | Double | 内存使用率(%) |
| memory.used | Long | 已用内存(MB) |
| memory.limit | Long | 内存限制(MB) |
| memory.cache | Long | 缓存(MB) |
| network | Object | 网络统计 |
| network.rxBytes | Long | 接收字节数 |
| network.txBytes | Long | 发送字节数 |
| network.rxPackets | Long | 接收包数 |
| network.txPackets | Long | 发送包数 |
| blockIO | Object | 磁盘IO统计 |
| blockIO.readBytes | Long | 读取字节数 |
| blockIO.writeBytes | Long | 写入字节数 |
| timestamp | DateTime | 统计时间 |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "containerId": "a1b2c3d4e5f6",
    "containerName": "palworld-server",
    "cpu": {
      "usagePercent": 15.5,
      "systemUsage": 5000000000,
      "totalUsage": 775000000
    },
    "memory": {
      "usagePercent": 45.2,
      "used": 1843,
      "limit": 4096,
      "cache": 512
    },
    "network": {
      "rxBytes": 1073741824,
      "txBytes": 536870912,
      "rxPackets": 1000000,
      "txPackets": 500000
    },
    "blockIO": {
      "readBytes": 104857600,
      "writeBytes": 52428800
    },
    "timestamp": "2026-03-24T10:30:00"
  },
  "timestamp": 1711084800000
}
```

---

#### 10.1.8 获取容器健康状态

**接口**: `GET /api/docker/hosts/{hostId}/containers/{containerId}/health`

**描述**: 获取容器健康检查状态

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| hostId | Long | 是 | 主机ID |
| containerId | String | 是 | 容器ID |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| containerId | String | 容器ID |
| status | String | 健康状态：healthy/unhealthy/starting/none |
| lastCheck | DateTime | 最后检查时间 |
| failingStreak | Integer | 连续失败次数 |
| log | Array | 健康检查日志（最近5条） |
| log[].start | DateTime | 检查开始时间 |
| log[].end | DateTime | 检查结束时间 |
| log[].exitCode | Integer | 退出码 |
| log[].output | String | 检查输出 |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "containerId": "a1b2c3d4e5f6",
    "status": "healthy",
    "lastCheck": "2026-03-24T10:30:00",
    "failingStreak": 0,
    "log": [
      {
        "start": "2026-03-24T10:30:00",
        "end": "2026-03-24T10:30:01",
        "exitCode": 0,
        "output": "Health check passed"
      },
      {
        "start": "2026-03-24T10:25:00",
        "end": "2026-03-24T10:25:01",
        "exitCode": 0,
        "output": "Health check passed"
      }
    ]
  },
  "timestamp": 1711084800000
}
```

---

### 10.2 容器日志 API

#### 10.2.1 获取容器日志

**接口**: `GET /api/docker/hosts/{hostId}/containers/{containerId}/logs`

**描述**: 获取容器运行日志

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| hostId | Long | 是 | 主机ID |
| containerId | String | 是 | 容器ID |

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| lines | Integer | 否 | 日志行数，默认100，最大2000 |
| since | DateTime | 否 | 开始时间 |
| until | DateTime | 否 | 结束时间 |
| timestamps | Boolean | 否 | 是否显示时间戳，默认false |
| keyword | String | 否 | 关键词过滤 |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| containerId | String | 容器ID |
| logs | Array | 日志列表 |
| logs[].time | DateTime | 日志时间 |
| logs[].content | String | 日志内容 |
| total | Integer | 总行数 |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "containerId": "a1b2c3d4e5f6",
    "logs": [
      {
        "time": "2026-03-24T10:30:00",
        "content": "[Server] Server started on port 2456"
      },
      {
        "time": "2026-03-24T10:30:01",
        "content": "[Server] Max players: 32"
      },
      {
        "time": "2026-03-24T10:30:02",
        "content": "[Server] Waiting for connections..."
      }
    ],
    "total": 3
  },
  "timestamp": 1711084800000
}
```

---

### 10.3 文件管理 API

#### 10.3.1 获取文件列表

**接口**: `GET /api/docker/hosts/{hostId}/containers/{containerId}/files`

**描述**: 浏览容器内指定目录的文件列表

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| hostId | Long | 是 | 主机ID |
| containerId | String | 是 | 容器ID |

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| path | String | 否 | 目录路径，默认为容器根目录"/" |
| showHidden | Boolean | 否 | 是否显示隐藏文件，默认false |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| currentPath | String | 当前路径 |
| files | Array | 文件列表 |
| files[].name | String | 文件/目录名称 |
| files[].path | String | 完整路径 |
| files[].isDirectory | Boolean | 是否为目录 |
| files[].size | Long | 文件大小(字节) |
| files[].modifiedTime | DateTime | 修改时间 |
| files[].permissions | String | 权限字符串 |
| files[].owner | String | 所有者 |
| files[].group | String | 所属组 |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "currentPath": "/palworld",
    "files": [
      {
        "name": "Pal",
        "path": "/palworld/Pal",
        "isDirectory": true,
        "size": 4096,
        "modifiedTime": "2026-03-24T08:00:00",
        "permissions": "drwxr-xr-x",
        "owner": "steam",
        "group": "steam"
      },
      {
        "name": "PalWorldSettings.ini",
        "path": "/palworld/Pal/Saved/Config/LinuxServer/PalWorldSettings.ini",
        "isDirectory": false,
        "size": 2048,
        "modifiedTime": "2026-03-24T09:30:00",
        "permissions": "-rw-r--r--",
        "owner": "steam",
        "group": "steam"
      }
    ]
  },
  "timestamp": 1711084800000
}
```

---

#### 10.3.2 获取文件内容

**接口**: `GET /api/docker/hosts/{hostId}/containers/{containerId}/files/content`

**描述**: 获取容器内文件内容

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| hostId | Long | 是 | 主机ID |
| containerId | String | 是 | 容器ID |

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| path | String | 是 | 文件路径 |
| encoding | String | 否 | 文件编码，默认UTF-8，支持：UTF-8/GBK/ISO-8859-1 |
| lines | Integer | 否 | 读取行数限制，默认不限制 |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| path | String | 文件路径 |
| name | String | 文件名 |
| content | String | 文件内容 |
| size | Long | 文件大小(字节) |
| encoding | String | 文件编码 |
| modifiedTime | DateTime | 修改时间 |
| truncated | Boolean | 是否被截断（超过限制） |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "path": "/palworld/Pal/Saved/Config/LinuxServer/PalWorldSettings.ini",
    "name": "PalWorldSettings.ini",
    "content": "[/Script/Pal.PalGameWorldSettings]\nOptionSettings=(Difficulty=None,DayTimeSpeedRate=1.000000...)",
    "size": 2048,
    "encoding": "UTF-8",
    "modifiedTime": "2026-03-24T09:30:00",
    "truncated": false
  },
  "timestamp": 1711084800000
}
```

---

#### 10.3.3 更新文件内容

**接口**: `PUT /api/docker/hosts/{hostId}/containers/{containerId}/files/content`

**描述**: 更新容器内文件内容

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| hostId | Long | 是 | 主机ID |
| containerId | String | 是 | 容器ID |

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| path | String | 是 | 文件路径 |
| content | String | 是 | 文件内容 |
| encoding | String | 否 | 文件编码，默认UTF-8 |
| backup | Boolean | 否 | 是否备份原文件，默认true |

**请求示例**:

```json
{
  "path": "/palworld/Pal/Saved/Config/LinuxServer/PalWorldSettings.ini",
  "content": "[/Script/Pal.PalGameWorldSettings]\nOptionSettings=(Difficulty=None,DayTimeSpeedRate=2.000000...)",
  "encoding": "UTF-8",
  "backup": true
}
```

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| success | Boolean | 是否更新成功 |
| path | String | 文件路径 |
| size | Long | 文件大小(字节) |
| backupPath | String | 备份文件路径（如果启用了备份） |

**响应示例**:

```json
{
  "code": 200,
  "message": "文件更新成功",
  "data": {
    "success": true,
    "path": "/palworld/Pal/Saved/Config/LinuxServer/PalWorldSettings.ini",
    "size": 2048,
    "backupPath": "/palworld/Pal/Saved/Config/LinuxServer/PalWorldSettings.ini.bak.20260324"
  },
  "timestamp": 1711084800000
}
```

---

#### 10.3.4 删除文件

**接口**: `DELETE /api/docker/hosts/{hostId}/containers/{containerId}/files`

**描述**: 删除容器内文件或空目录

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| hostId | Long | 是 | 主机ID |
| containerId | String | 是 | 容器ID |

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| path | String | 是 | 文件路径 |

**响应示例**:

```json
{
  "code": 200,
  "message": "文件删除成功",
  "data": {
    "success": true,
    "path": "/palworld/temp.txt"
  },
  "timestamp": 1711084800000
}
```

---

#### 10.3.5 上传文件

**接口**: `POST /api/docker/hosts/{hostId}/containers/{containerId}/files/upload`

**描述**: 上传文件到容器指定目录

**认证**: 需要JWT令牌

**Content-Type**: `multipart/form-data`

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| hostId | Long | 是 | 主机ID |
| containerId | String | 是 | 容器ID |

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| file | File | 是 | 上传的文件 |
| path | String | 否 | 目标目录路径，默认为容器根目录 |
| overwrite | Boolean | 否 | 是否覆盖已存在的文件，默认false |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| success | Boolean | 是否上传成功 |
| fileName | String | 文件名 |
| filePath | String | 文件在容器内的完整路径 |
| size | Long | 文件大小(字节) |

**响应示例**:

```json
{
  "code": 200,
  "message": "文件上传成功",
  "data": {
    "success": true,
    "fileName": "backup.tar.gz",
    "filePath": "/palworld/backup.tar.gz",
    "size": 1048576
  },
  "timestamp": 1711084800000
}
```

---

#### 10.3.6 下载文件

**接口**: `GET /api/docker/hosts/{hostId}/containers/{containerId}/files/download`

**描述**: 从容器下载文件

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| hostId | Long | 是 | 主机ID |
| containerId | String | 是 | 容器ID |

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| path | String | 是 | 文件路径 |

**响应**: 文件流（Content-Type: application/octet-stream）

**响应示例**:

```
HTTP/1.1 200 OK
Content-Type: application/octet-stream
Content-Disposition: attachment; filename="PalWorldSettings.ini"

[文件二进制内容]
```

---

#### 10.3.7 拷贝文件

**接口**: `POST /api/docker/hosts/{hostId}/containers/{containerId}/copy`

**描述**: 在容器与主机间拷贝文件

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| hostId | Long | 是 | 主机ID |
| containerId | String | 是 | 容器ID |

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| direction | String | 是 | 拷贝方向：toContainer-主机到容器，fromContainer-容器到主机 |
| sourcePath | String | 是 | 源路径 |
| destinationPath | String | 是 | 目标路径 |
| overwrite | Boolean | 否 | 是否覆盖已存在的文件，默认false |

**请求示例**:

```json
{
  "direction": "toContainer",
  "sourcePath": "/opt/gameplatform/files/config.ini",
  "destinationPath": "/palworld/config/config.ini",
  "overwrite": true
}
```

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| success | Boolean | 是否拷贝成功 |
| direction | String | 拷贝方向 |
| sourcePath | String | 源路径 |
| destinationPath | String | 目标路径 |
| filesCopied | Integer | 拷贝的文件数量 |

**响应示例**:

```json
{
  "code": 200,
  "message": "文件拷贝成功",
  "data": {
    "success": true,
    "direction": "toContainer",
    "sourcePath": "/opt/gameplatform/files/config.ini",
    "destinationPath": "/palworld/config/config.ini",
    "filesCopied": 1
  },
  "timestamp": 1711084800000
}
```

---

### 10.4 镜像管理 API

#### 10.4.1 获取镜像列表

**接口**: `GET /api/docker/hosts/{hostId}/images`

**描述**: 获取主机上的 Docker 镜像列表

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| hostId | Long | 是 | 主机ID |

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| keyword | String | 否 | 关键词搜索（镜像名称） |
| dangling | Boolean | 否 | 是否只显示悬空镜像，默认false |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| images | Array | 镜像列表 |
| images[].imageId | String | 镜像ID（短ID） |
| images[].imageIdFull | String | 镜像完整ID |
| images[].repoTags | Array | 仓库标签列表 |
| images[].size | Long | 镜像大小(MB) |
| images[].createdAt | DateTime | 创建时间 |
| images[].usedByContainers | Integer | 被使用的容器数量 |
| images[].isDangling | Boolean | 是否为悬空镜像 |
| images[].labels | Object | 标签信息 |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "images": [
      {
        "imageId": "abc123def456",
        "imageIdFull": "sha256:abc123def456789...",
        "repoTags": ["mygame/palworld:latest", "mygame/palworld:v1.0.0"],
        "size": 2048,
        "createdAt": "2026-03-20T08:00:00",
        "usedByContainers": 1,
        "isDangling": false,
        "labels": {
          "maintainer": "gameplatform"
        }
      },
      {
        "imageId": "def456ghi789",
        "imageIdFull": "sha256:def456ghi789abc...",
        "repoTags": ["<none>:<none>"],
        "size": 512,
        "createdAt": "2026-03-19T10:00:00",
        "usedByContainers": 0,
        "isDangling": true,
        "labels": {}
      }
    ]
  },
  "timestamp": 1711084800000
}
```

---

#### 10.4.2 删除镜像

**接口**: `DELETE /api/docker/hosts/{hostId}/images/{imageId}`

**描述**: 删除指定的镜像

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| hostId | Long | 是 | 主机ID |
| imageId | String | 是 | 镜像ID |

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| force | Boolean | 否 | 是否强制删除，默认false |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| success | Boolean | 是否删除成功 |
| imageId | String | 镜像ID |
| message | String | 操作结果消息 |
| deletedImages | Array | 被删除的镜像列表（强制删除时可能删除多个） |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "success": true,
    "imageId": "def456ghi789",
    "message": "镜像已删除",
    "deletedImages": ["sha256:def456ghi789abc..."]
  },
  "timestamp": 1711084800000
}
```

---

#### 10.4.3 清理悬空镜像

**接口**: `POST /api/docker/hosts/{hostId}/images/prune`

**描述**: 清理无标签的悬空镜像

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| hostId | Long | 是 | 主机ID |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| deletedImages | Array | 被删除的镜像列表 |
| spaceReclaimed | Long | 释放的空间(MB) |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "deletedImages": [
      "sha256:def456ghi789abc...",
      "sha256:ghi789jkl012def..."
    ],
    "spaceReclaimed": 1024
  },
  "timestamp": 1711084800000
}
```

---

### 10.5 关联管理 API

#### 10.5.1 创建关联

**接口**: `POST /api/docker/links`

**描述**: 手动创建容器与实例的关联

**认证**: 需要JWT令牌

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| hostId | Long | 是 | 主机ID |
| containerId | String | 是 | 容器ID |
| containerName | String | 是 | 容器名称 |
| instanceId | Long | 否 | 关联的实例ID（linkType为instance时必填） |
| linkType | String | 是 | 关联类型：instance/host |
| imageName | String | 否 | 镜像名称 |
| imageTag | String | 否 | 镜像标签 |
| remark | String | 否 | 备注 |

**请求示例**:

```json
{
  "hostId": 1,
  "containerId": "a1b2c3d4e5f6",
  "containerName": "palworld-server",
  "instanceId": 1,
  "linkType": "instance",
  "imageName": "mygame/palworld",
  "imageTag": "latest",
  "remark": "手动关联"
}
```

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| id | Long | 关联记录ID |
| hostId | Long | 主机ID |
| containerId | String | 容器ID |
| instanceId | Long | 实例ID |
| linkType | String | 关联类型 |

**响应示例**:

```json
{
  "code": 200,
  "message": "关联创建成功",
  "data": {
    "id": 1,
    "hostId": 1,
    "containerId": "a1b2c3d4e5f6",
    "instanceId": 1,
    "linkType": "instance"
  },
  "timestamp": 1711084800000
}
```

---

#### 10.5.2 更新关联

**接口**: `PUT /api/docker/links/{id}`

**描述**: 更新容器关联信息

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| id | Long | 是 | 关联记录ID |

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| instanceId | Long | 否 | 关联的实例ID |
| linkType | String | 否 | 关联类型：instance/host |
| remark | String | 否 | 备注 |

**请求示例**:

```json
{
  "instanceId": 2,
  "linkType": "instance",
  "remark": "更新关联"
}
```

**响应示例**:

```json
{
  "code": 200,
  "message": "关联更新成功",
  "data": null,
  "timestamp": 1711084800000
}
```

---

#### 10.5.3 删除关联

**接口**: `DELETE /api/docker/links/{id}`

**描述**: 删除容器关联

**认证**: 需要JWT令牌

**路径参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| id | Long | 是 | 关联记录ID |

**响应示例**:

```json
{
  "code": 200,
  "message": "关联删除成功",
  "data": null,
  "timestamp": 1711084800000
}
```

---

#### 10.5.4 获取关联列表

**接口**: `GET /api/docker/links`

**描述**: 获取容器关联列表

**认证**: 需要JWT令牌

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| hostId | Long | 否 | 主机ID |
| instanceId | Long | 否 | 实例ID |
| containerId | String | 否 | 容器ID |
| linkType | String | 否 | 关联类型：instance/host |

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| records | Array | 关联列表 |
| records[].id | Long | 关联记录ID |
| records[].hostId | Long | 主机ID |
| records[].hostName | String | 主机名称 |
| records[].containerId | String | 容器ID |
| records[].containerName | String | 容器名称 |
| records[].instanceId | Long | 实例ID |
| records[].instanceName | String | 实例名称 |
| records[].linkType | String | 关联类型 |
| records[].imageName | String | 镜像名称 |
| records[].imageTag | String | 镜像标签 |
| records[].autoLinked | Boolean | 是否自动关联 |
| records[].createBy | Long | 创建人ID |
| records[].createByName | String | 创建人名称 |
| records[].createTime | DateTime | 创建时间 |
| records[].updateTime | DateTime | 更新时间 |

**响应示例**:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "records": [
      {
        "id": 1,
        "hostId": 1,
        "hostName": "游戏服务器-01",
        "containerId": "a1b2c3d4e5f6",
        "containerName": "palworld-server",
        "instanceId": 1,
        "instanceName": "Palworld-Server-01",
        "linkType": "instance",
        "imageName": "mygame/palworld",
        "imageTag": "latest",
        "autoLinked": false,
        "createBy": 1,
        "createByName": "admin",
        "createTime": "2026-03-24T08:00:00",
        "updateTime": "2026-03-24T08:00:00"
      }
    ]
  },
  "timestamp": 1711084800000
}
```

---

#### 10.5.5 执行自动关联

**接口**: `POST /api/docker/links/auto`

**描述**: 根据镜像名称自动匹配并创建容器关联

**认证**: 需要JWT令牌

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| hostId | Long | 是 | 主机ID |

**请求示例**:

```json
{
  "hostId": 1
}
```

**响应参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| totalContainers | Integer | 扫描的容器总数 |
| linkedCount | Integer | 成功关联的数量 |
| skippedCount | Integer | 跳过的数量（已存在关联） |
| links | Array | 创建的关联记录列表 |
| links[].containerId | String | 容器ID |
| links[].containerName | String | 容器名称 |
| links[].instanceId | Long | 实例ID |
| links[].instanceName | String | 实例名称 |
| links[].matchedImage | String | 匹配的镜像名称 |

**响应示例**:

```json
{
  "code": 200,
  "message": "自动关联完成",
  "data": {
    "totalContainers": 5,
    "linkedCount": 2,
    "skippedCount": 3,
    "links": [
      {
        "containerId": "a1b2c3d4e5f6",
        "containerName": "palworld-server",
        "instanceId": 1,
        "instanceName": "Palworld-Server-01",
        "matchedImage": "mygame/palworld"
      },
      {
        "containerId": "m3n4o5p6q7r8",
        "containerName": "minecraft-server",
        "instanceId": 2,
        "instanceName": "MC-Server-01",
        "matchedImage": "itzg/minecraft-server"
      }
    ]
  },
  "timestamp": 1711084800000
}
```

---

### 10.6 WebSocket 端点

#### 10.6.1 容器 Exec 终端

**端点**: `/ws/docker/exec`

**描述**: 通过 WebSocket 在容器内启动新进程执行命令

**认证**: 需要JWT令牌（通过URL参数或请求头传递）

**连接参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| hostId | Long | 是 | 主机ID |
| containerId | String | 是 | 容器ID |
| token | String | 是 | JWT令牌 |

**连接URL示例**:

```
ws://localhost:8080/ws/docker/exec?hostId=1&containerId=a1b2c3d4e5f6&token=xxx
```

**消息格式**:

客户端发送消息格式：

```json
{
  "type": "input",
  "data": "ls -la"
}
```

```json
{
  "type": "resize",
  "cols": 120,
  "rows": 40
}
```

服务端推送消息格式：

```json
{
  "type": "output",
  "data": "total 16\ndrwxr-xr-x 2 root root 4096 Mar 24 10:30 .\n..."
}
```

```json
{
  "type": "error",
  "data": "Connection error"
}
```

**消息类型说明**:

| 类型 | 方向 | 说明 |
|------|------|------|
| input | 客户端->服务端 | 终端输入 |
| output | 服务端->客户端 | 终端输出 |
| resize | 客户端->服务端 | 终端大小调整 |
| error | 服务端->客户端 | 错误消息 |
| close | 服务端->客户端 | 连接关闭通知 |

---

#### 10.6.2 容器 Attach 终端

**端点**: `/ws/docker/attach`

**描述**: 通过 WebSocket 连接到容器主进程

**认证**: 需要JWT令牌

**连接参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| hostId | Long | 是 | 主机ID |
| containerId | String | 是 | 容器ID |
| token | String | 是 | JWT令牌 |

**连接URL示例**:

```
ws://localhost:8080/ws/docker/attach?hostId=1&containerId=a1b2c3d4e5f6&token=xxx
```

**消息格式**: 同 Exec 终端

---

#### 10.6.3 实时日志流

**端点**: `/ws/docker/logs`

**描述**: 通过 WebSocket 实时获取容器日志流

**认证**: 需要JWT令牌

**连接参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| hostId | Long | 是 | 主机ID |
| containerId | String | 是 | 容器ID |
| token | String | 是 | JWT令牌 |
| tail | Integer | 否 | 初始显示的日志行数，默认100 |
| timestamps | Boolean | 否 | 是否显示时间戳，默认false |

**连接URL示例**:

```
ws://localhost:8080/ws/docker/logs?hostId=1&containerId=a1b2c3d4e5f6&token=xxx&tail=100
```

**消息格式**:

服务端推送消息格式：

```json
{
  "type": "log",
  "timestamp": "2026-03-24T10:30:00",
  "content": "[Server] Player connected: user1"
}
```

```json
{
  "type": "error",
  "data": "Container stopped"
}
```

---

### 10.7 Docker 模块错误码

| 错误码 | 错误信息 | 说明 |
|--------|----------|------|
| 1701 | Docker连接失败 | 无法连接到Docker守护进程 |
| 1702 | 容器不存在 | 指定的容器不存在 |
| 1703 | 容器操作失败 | 容器启动/停止/重启失败 |
| 1704 | 容器正在运行 | 删除运行中的容器需要强制删除 |
| 1705 | 容器未运行 | 操作需要容器处于运行状态 |
| 1706 | 镜像不存在 | 指定的镜像不存在 |
| 1707 | 镜像正在使用 | 镜像被容器使用中，无法删除 |
| 1708 | 文件路径不存在 | 容器内文件路径不存在 |
| 1709 | 文件过大 | 文件超过大小限制 |
| 1710 | 关联已存在 | 容器已存在关联关系 |
| 1711 | 关联不存在 | 关联记录不存在 |
| 1712 | 无权限操作 | 无权限操作该容器（非创建人） |
| 1713 | 终端连接失败 | WebSocket终端连接失败 |

---

## 附录

### A. 日期时间格式

所有日期时间字段使用ISO 8601格式：`yyyy-MM-ddTHH:mm:ss`

示例：`2026-03-22T10:30:00`

### B. 文件大小单位

| 单位 | 说明 |
|------|------|
| B | 字节 |
| KB | 千字节 |
| MB | 兆字节 |
| GB | 吉字节 |

### C. 状态枚举值

**主机状态**:
- 0: 离线
- 1: 在线

**实例运行状态**:
- 0: 已停止
- 1: 运行中
- 2: 异常
- 3: 部署中
- 4: 卸载中

**用户状态**:
- 0: 禁用
- 1: 启用

**插件状态**:
- 0: 禁用
- 1: 启用

**备份状态**:
- 0: 失败
- 1: 成功
- 2: 进行中

**还原状态**:
- 0: 未还原
- 1: 还原中
- 2: 还原成功
- 3: 还原失败

**Docker容器状态**:
- running: 运行中
- stopped: 已停止
- paused: 已暂停
- restarting: 重启中

**Docker容器健康状态**:
- healthy: 健康
- unhealthy: 不健康
- starting: 启动中
- none: 无健康检查

**容器关联类型**:
- instance: 关联实例
- host: 关联主机

---

**文档版本**: v1.1.0  
**最后更新**: 2026-03-24
