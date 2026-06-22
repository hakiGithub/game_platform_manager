# Game Platform Manager - 项目总览

> AI Agent 协作指南 - 根目录

---

## 项目概述

游戏服务器统一管理平台是一个面向个人游戏服运维场景的轻量级管理后台，采用前后端分离架构，支持多游戏、多主机的统一管理。

### 核心功能
- **主机纳管**: SSH连接管理、资源监控、Web终端
- **游戏部署**: 支持 LinuxGSM/Docker/Docker Compose 三种部署方式
- **实例管理**: 游戏实例生命周期管理、配置管理、文件管理
- **插件扩展**: PF4J插件框架，支持4种标准化扩展插槽
- **备份还原**: 实例数据备份与恢复

---

## 技术栈概览

### 后端 (Backend)
| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 17 | 开发语言 |
| Spring Boot | 3.2.5 | 核心框架 |
| Spring Security | 6.x | 安全认证 |
| Spring WebSocket | 6.x | 实时通信 |
| MyBatis-Plus | 3.5.6 | ORM框架 |
| SQLite | 3.45.2.0 | 嵌入式数据库 |
| Apache MINA SSHD | 2.12.1 | SSH连接 |
| Docker Java | 3.3.4 | Docker API |
| PF4J | 3.10.0 | 插件框架 |
| JWT | 0.12.5 | 令牌认证 |

### 前端 (Frontend)
| 技术 | 版本 | 用途 |
|------|------|------|
| Vue | 3.4.21 | 前端框架 |
| Vue Router | 4.3.0 | 路由管理 |
| Pinia | 2.1.7 | 状态管理 |
| Element Plus | 2.6.1 | UI组件库 |
| Axios | 1.6.8 | HTTP请求 |
| XTerm.js | 5.3.0 | Web终端 |
| Vite | 5.2.0 | 构建工具 |
| Vitest | 1.4.0 | 测试框架 |

---

## 项目结构

```
game_platform_manger/
├── backend/                          # 后端项目 (Spring Boot)
│   ├── src/main/java/com/gameplatform/
│   │   ├── adapter/                  # 部署适配器
│   │   ├── annotation/               # 自定义注解
│   │   ├── aspect/                   # AOP切面
│   │   ├── common/                   # 公共类
│   │   ├── config/                   # 配置类
│   │   ├── controller/               # 控制器层
│   │   ├── dto/                      # 数据传输对象
│   │   ├── entity/                   # 实体类
│   │   ├── handler/                  # 类型处理器
│   │   ├── listener/                 # 监听器
│   │   ├── mapper/                   # MyBatis Mapper
│   │   ├── service/                  # 服务层
│   │   ├── task/                     # 定时任务
│   │   ├── util/                     # 工具类
│   │   ├── vo/                       # 视图对象
│   │   └── websocket/                # WebSocket处理器
│   ├── src/main/resources/
│   │   ├── db/                       # 数据库脚本
│   │   ├── games/                    # 游戏元数据配置
│   │   ├── mapper/                   # Mapper XML
│   │   └── application.yml           # 主配置
│   ├── src/test/                     # 测试代码
│   ├── pom.xml                       # Maven配置
│   └── AGENTS.md                     # 后端开发指南
│
├── frontend/                         # 前端项目 (Vue 3)
│   ├── src/
│   │   ├── api/                      # API接口
│   │   ├── components/               # 公共组件
│   │   ├── layouts/                  # 布局组件
│   │   ├── router/                   # 路由配置
│   │   ├── stores/                   # Pinia状态管理
│   │   ├── styles/                   # 样式文件
│   │   ├── tests/                    # 测试文件
│   │   ├── App.vue                   # 根组件
│   │   └── main.js                   # 入口文件
│   ├── package.json                  # NPM配置
│   └── AGENTS.md                     # 前端开发指南
│
├── docs/                             # 文档
│   └── api-doc.md                    # API接口文档
├── AGENTS.md                         # 项目总览 (本文件)
└── UE和UI设计稿.md                   # UI/UE设计规范
```

---

## 模块职责

### 后端模块

| 模块 | 职责 |
|------|------|
| adapter | 部署适配器，支持 LinuxGSM/Docker/Docker Compose |
| controller | REST API 控制器，处理 HTTP 请求 |
| service | 业务逻辑层，核心业务实现 |
| mapper | 数据访问层，MyBatis Mapper 接口 |
| entity | 数据库实体类 |
| dto | 数据传输对象，请求参数封装 |
| vo | 视图对象，响应数据封装 |
| config | 配置类，Spring Bean 配置 |
| websocket | WebSocket 处理器，实时通信 |
| util | 工具类 |

### 前端模块

| 模块 | 职责 |
|------|------|
| api | API 请求封装 |
| components | 可复用组件 |
| layouts | 页面布局组件 |
| router | 路由配置 |
| stores | Pinia 状态管理 |
| styles | 全局样式 |

---

## 前后端协作

### API 通信规范

#### 基础配置
- **基础路径**: `/api`
- **认证方式**: JWT Token
- **请求头**: `Authorization: Bearer {token}`

#### 统一响应格式
```json
{
  "code": 200,
  "message": "操作成功",
  "data": {},
  "timestamp": 1711084800000
}
```

#### 分页响应格式
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

### WebSocket 通信

| 端点 | 用途 |
|------|------|
| `/ws/ssh` | Web SSH 终端 |
| `/ws/instance/console` | 实例控制台 |
| `/ws/instance/log` | 实例日志流 |

---

## 常用命令

### 后端
```bash
cd backend

# 编译
mvn clean compile

# 运行
mvn spring-boot:run

# 测试
mvn test

# 打包
mvn clean package

# 代码覆盖率
mvn jacoco:report
```

### 前端
```bash
cd frontend

# 安装依赖
npm install

# 开发模式
npm run dev

# 构建
npm run build

# 测试
npm run test

# 代码检查
npm run lint
```

---

## 安全规范

1. **认证授权**: JWT Token + Spring Security
2. **密码加密**: BCrypt
3. **敏感数据**: AES加密存储
4. **输入校验**: 全局参数校验
5. **SQL注入**: MyBatis参数绑定
6. **XSS防护**: 前端转义 + 后端过滤

---

## 错误码规范

| 错误码 | 说明 |
|--------|------|
| 200 | 成功 |
| 400 | 参数错误 |
| 401 | 未授权 |
| 403 | 无权限 |
| 404 | 资源不存在 |
| 500 | 服务器错误 |
| 1001-1999 | 业务错误码 |

---

## 相关文档

- [后端开发指南](backend/AGENTS.md)
- [前端开发指南](frontend/AGENTS.md)
- [API接口文档](docs/api-doc.md)
- [UI/UE设计规范](UE和UI设计稿.md)

---

*最后更新: 2026-05-10*
