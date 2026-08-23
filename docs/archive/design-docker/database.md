# Docker 实例管理模块 - 数据库设计文档

> 版本: 1.0
> 日期: 2026-03-24
> 数据库: SQLite
> ORM: MyBatis-Plus 3.5.6

---

## 一、概述

本文档描述 Docker 实例管理模块的数据库表结构设计，主要包含容器关联表 `docker_container_link`，用于存储 Docker 容器与游戏实例/主机的关联关系。

---

## 二、表结构设计

### 2.1 容器关联表 (docker_container_link)

#### 业务说明

存储 Docker 容器与游戏实例或主机的关联关系，支持：
- 自动关联：根据镜像名称匹配自动创建关联
- 手动关联：用户手动将容器关联到实例或主机
- 权限控制：通过创建人字段实现按实例归属的权限控制

#### 表结构

| 字段名 | 数据类型 | 可空 | 默认值 | 说明 |
|--------|----------|------|--------|------|
| id | INTEGER | 否 | AUTOINCREMENT | 主键ID |
| host_id | INTEGER | 否 | - | 主机ID，关联 host_info.id |
| container_id | VARCHAR(64) | 否 | - | Docker容器ID（完整或短ID） |
| container_name | VARCHAR(255) | 否 | - | 容器名称 |
| instance_id | INTEGER | 是 | NULL | 关联的游戏实例ID，关联 game_instance.id |
| link_type | VARCHAR(20) | 否 | 'host' | 关联类型：instance-关联实例，host-关联主机 |
| image_name | VARCHAR(255) | 是 | NULL | 镜像名称（不含标签） |
| image_tag | VARCHAR(100) | 是 | NULL | 镜像标签 |
| auto_linked | INTEGER | 否 | 0 | 是否自动关联：0-手动，1-自动 |
| create_by | INTEGER | 是 | NULL | 创建人ID，关联 sys_user.id |
| create_time | DATETIME | 否 | now | 创建时间 |
| update_time | DATETIME | 否 | now | 更新时间 |
| is_deleted | INTEGER | 否 | 0 | 逻辑删除：0-未删除，1-已删除 |
| remark | TEXT | 是 | NULL | 备注 |

#### 字段详细说明

| 字段 | 设计考量 |
|------|----------|
| host_id | 必填字段，容器必须属于某个主机；外键关联 host_info 表 |
| container_id | Docker 容器唯一标识，最长64字符（完整ID），通常使用12字符短ID |
| container_name | 容器名称，可能被用户重命名，用于展示和快速识别 |
| instance_id | 可空字段，当 link_type='instance' 时必填；关联到具体游戏实例 |
| link_type | 枚举值：instance/host；区分容器是关联到游戏实例还是仅关联到主机 |
| image_name | 镜像名称（如 mygame/palworld），用于自动匹配和展示 |
| image_tag | 镜像标签（如 latest、v1.0.0），用于版本识别 |
| auto_linked | 标识关联方式，自动关联的记录在实例删除时可能需要特殊处理 |
| create_by | 创建人ID，用于权限控制：普通用户只能操作自己创建的关联 |

#### 约束设计

| 约束类型 | 约束名称 | 字段 | 说明 |
|----------|----------|------|------|
| PRIMARY KEY | pk_docker_container_link | id | 主键约束 |
| FOREIGN KEY | fk_dcl_host_id | host_id | 关联 host_info(id) |
| FOREIGN KEY | fk_dcl_instance_id | instance_id | 关联 game_instance(id) |
| FOREIGN KEY | fk_dcl_create_by | create_by | 关联 sys_user(id) |
| UNIQUE | uk_dcl_host_container | host_id, container_id | 同一主机下容器唯一 |

#### 索引设计

| 索引名称 | 索引字段 | 索引类型 | 说明 |
|----------|----------|----------|------|
| idx_dcl_host_id | host_id | 普通索引 | 按主机查询容器关联 |
| idx_dcl_container_id | container_id | 普通索引 | 按容器ID查询关联 |
| idx_dcl_instance_id | instance_id | 普通索引 | 按实例ID查询关联容器 |
| idx_dcl_link_type | link_type | 普通索引 | 按关联类型筛选 |
| idx_dcl_create_by | create_by | 普通索引 | 按创建人查询（权限控制） |
| idx_dcl_is_deleted | is_deleted | 普通索引 | 逻辑删除筛选 |

---

## 三、建表 SQL 脚本

### 3.1 完整建表语句

```sql
-- =====================================================
-- Docker 容器关联表 (docker_container_link)
-- 用于存储容器与实例/主机的关联关系
-- 创建时间: 2026-03-24
-- =====================================================

CREATE TABLE IF NOT EXISTS docker_container_link (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    host_id         INTEGER NOT NULL,
    container_id    VARCHAR(64) NOT NULL,
    container_name  VARCHAR(255) NOT NULL,
    instance_id     INTEGER,
    link_type       VARCHAR(20) NOT NULL DEFAULT 'host',
    image_name      VARCHAR(255),
    image_tag       VARCHAR(100),
    auto_linked     INTEGER NOT NULL DEFAULT 0,
    create_by       INTEGER,
    create_time     DATETIME DEFAULT (datetime('now', 'localtime')),
    update_time     DATETIME DEFAULT (datetime('now', 'localtime')),
    is_deleted      INTEGER NOT NULL DEFAULT 0,
    remark          TEXT,
    CONSTRAINT uk_dcl_host_container UNIQUE (host_id, container_id),
    CONSTRAINT fk_dcl_host_id FOREIGN KEY (host_id) REFERENCES host_info(id),
    CONSTRAINT fk_dcl_instance_id FOREIGN KEY (instance_id) REFERENCES game_instance(id),
    CONSTRAINT fk_dcl_create_by FOREIGN KEY (create_by) REFERENCES sys_user(id)
);

-- 容器关联表索引
CREATE INDEX IF NOT EXISTS idx_dcl_host_id ON docker_container_link(host_id);
CREATE INDEX IF NOT EXISTS idx_dcl_container_id ON docker_container_link(container_id);
CREATE INDEX IF NOT EXISTS idx_dcl_instance_id ON docker_container_link(instance_id);
CREATE INDEX IF NOT EXISTS idx_dcl_link_type ON docker_container_link(link_type);
CREATE INDEX IF NOT EXISTS idx_dcl_create_by ON docker_container_link(create_by);
CREATE INDEX IF NOT EXISTS idx_dcl_is_deleted ON docker_container_link(is_deleted);
```

### 3.2 迁移脚本 (V1.3__add_docker_container_link.sql)

```sql
-- =====================================================
-- Migration: V1.3
-- Description: 添加 Docker 容器关联表
-- Date: 2026-03-24
-- =====================================================

-- 创建 Docker 容器关联表
CREATE TABLE IF NOT EXISTS docker_container_link (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    host_id         INTEGER NOT NULL,
    container_id    VARCHAR(64) NOT NULL,
    container_name  VARCHAR(255) NOT NULL,
    instance_id     INTEGER,
    link_type       VARCHAR(20) NOT NULL DEFAULT 'host',
    image_name      VARCHAR(255),
    image_tag       VARCHAR(100),
    auto_linked     INTEGER NOT NULL DEFAULT 0,
    create_by       INTEGER,
    create_time     DATETIME DEFAULT (datetime('now', 'localtime')),
    update_time     DATETIME DEFAULT (datetime('now', 'localtime')),
    is_deleted      INTEGER NOT NULL DEFAULT 0,
    remark          TEXT,
    CONSTRAINT uk_dcl_host_container UNIQUE (host_id, container_id),
    CONSTRAINT fk_dcl_host_id FOREIGN KEY (host_id) REFERENCES host_info(id),
    CONSTRAINT fk_dcl_instance_id FOREIGN KEY (instance_id) REFERENCES game_instance(id),
    CONSTRAINT fk_dcl_create_by FOREIGN KEY (create_by) REFERENCES sys_user(id)
);

-- 创建索引
CREATE INDEX IF NOT EXISTS idx_dcl_host_id ON docker_container_link(host_id);
CREATE INDEX IF NOT EXISTS idx_dcl_container_id ON docker_container_link(container_id);
CREATE INDEX IF NOT EXISTS idx_dcl_instance_id ON docker_container_link(instance_id);
CREATE INDEX IF NOT EXISTS idx_dcl_link_type ON docker_container_link(link_type);
CREATE INDEX IF NOT EXISTS idx_dcl_create_by ON docker_container_link(create_by);
CREATE INDEX IF NOT EXISTS idx_dcl_is_deleted ON docker_container_link(is_deleted);
```

---

## 四、表关联关系

### 4.1 ER 关系图

```
┌─────────────────┐       ┌──────────────────────────┐       ┌─────────────────┐
│    host_info    │       │ docker_container_link    │       │  game_instance  │
├─────────────────┤       ├──────────────────────────┤       ├─────────────────┤
│ id (PK)         │◄──────│ host_id (FK)             │       │ id (PK)         │
│ host_name       │       │ id (PK)                  │       │ instance_name   │
│ ip_address      │       │ container_id             │       │ host_id (FK)    │
│ ssh_port        │       │ container_name           │       │ game_id (FK)    │
│ ...             │       │ instance_id (FK)         │──────►│ deploy_type     │
└─────────────────┘       │ link_type                │       │ run_status      │
                          │ image_name               │       │ ...             │
                          │ image_tag                │       └─────────────────┘
                          │ auto_linked              │
                          │ create_by (FK)           │
                          │ ...                      │
                          └──────────────────────────┘
                                      │
                                      │ FK
                                      ▼
                          ┌─────────────────┐
                          │    sys_user     │
                          ├─────────────────┤
                          │ id (PK)         │
                          │ username        │
                          │ ...             │
                          └─────────────────┘
```

### 4.2 关联关系说明

| 关联关系 | 类型 | 说明 |
|----------|------|------|
| host_info → docker_container_link | 一对多 | 一个主机可以有多个容器关联记录 |
| game_instance → docker_container_link | 一对多 | 一个游戏实例可以关联多个容器 |
| sys_user → docker_container_link | 一对多 | 一个用户可以创建多个容器关联 |
| docker_container_link → host_info | 多对一 | 容器关联必须属于某个主机 |
| docker_container_link → game_instance | 多对一 | 容器可选关联到游戏实例 |

### 4.3 级联操作策略

| 操作 | 策略 | 说明 |
|------|------|------|
| 删除主机 | RESTRICT | 存在关联容器时禁止删除主机 |
| 删除实例 | SET NULL | 实例删除时，关联记录的 instance_id 置空，link_type 改为 'host' |
| 删除用户 | SET NULL | 用户删除时，create_by 置空 |

---

## 五、实体类设计

### 5.1 DockerContainerLink 实体类

```java
package com.gameplatform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Docker容器关联实体类
 * 对应表: docker_container_link
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("docker_container_link")
public class DockerContainerLink extends BaseEntity {

    /**
     * 主机ID
     */
    private Long hostId;

    /**
     * Docker容器ID
     */
    private String containerId;

    /**
     * 容器名称
     */
    private String containerName;

    /**
     * 关联的游戏实例ID
     */
    private Long instanceId;

    /**
     * 关联类型: instance-关联实例, host-关联主机
     */
    private String linkType;

    /**
     * 镜像名称（不含标签）
     */
    private String imageName;

    /**
     * 镜像标签
     */
    private String imageTag;

    /**
     * 是否自动关联: 0-手动, 1-自动
     */
    private Integer autoLinked;

    /**
     * 创建人ID
     */
    private Long createBy;

}
```

### 5.2 关联类型枚举

```java
package com.gameplatform.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 容器关联类型枚举
 */
@Getter
@AllArgsConstructor
public enum ContainerLinkType {

    INSTANCE("instance", "关联实例"),
    HOST("host", "关联主机");

    private final String code;
    private final String description;

}
```

### 5.3 VO 设计建议

```java
package com.gameplatform.vo;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 容器关联详情VO
 */
@Data
public class ContainerLinkVO {

    private Long id;
    private Long hostId;
    private String hostName;
    private String containerId;
    private String containerName;
    private Long instanceId;
    private String instanceName;
    private String linkType;
    private String imageName;
    private String imageTag;
    private Boolean autoLinked;
    private Long createBy;
    private String createByName;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

}
```

---

## 六、查询优化建议

### 6.1 常用查询场景

| 查询场景 | SQL 示例 | 推荐索引 |
|----------|----------|----------|
| 按主机查询容器列表 | `WHERE host_id = ? AND is_deleted = 0` | idx_dcl_host_id |
| 按实例查询关联容器 | `WHERE instance_id = ? AND is_deleted = 0` | idx_dcl_instance_id |
| 按容器ID查询关联 | `WHERE container_id = ? AND host_id = ?` | uk_dcl_host_container |
| 按创建人查询 | `WHERE create_by = ? AND is_deleted = 0` | idx_dcl_create_by |
| 按关联类型筛选 | `WHERE link_type = ? AND host_id = ?` | idx_dcl_link_type |

### 6.2 复合查询优化

```sql
-- 查询主机下所有容器关联（含实例信息）
SELECT
    dcl.*,
    h.host_name,
    gi.instance_name,
    u.username as create_by_name
FROM docker_container_link dcl
LEFT JOIN host_info h ON dcl.host_id = h.id
LEFT JOIN game_instance gi ON dcl.instance_id = gi.id
LEFT JOIN sys_user u ON dcl.create_by = u.id
WHERE dcl.host_id = ?
  AND dcl.is_deleted = 0
ORDER BY dcl.create_time DESC;

-- 查询实例关联的所有容器
SELECT
    dcl.*,
    h.host_name
FROM docker_container_link dcl
LEFT JOIN host_info h ON dcl.host_id = h.id
WHERE dcl.instance_id = ?
  AND dcl.is_deleted = 0
ORDER BY dcl.container_name;
```

---

## 七、数据完整性约束

### 7.1 业务约束

| 约束 | 实现方式 | 说明 |
|------|----------|------|
| 容器唯一性 | UNIQUE(host_id, container_id) | 同一主机下容器只能关联一次 |
| 实例关联完整性 | 应用层校验 | link_type='instance' 时 instance_id 必填 |
| 镜像格式校验 | 应用层校验 | image_name 格式符合 Docker 镜像命名规范 |

### 7.2 数据一致性

- **实例删除处理**: 当游戏实例被删除时，需要将关联记录的 `instance_id` 置空，`link_type` 改为 'host'
- **主机删除限制**: 存在容器关联的主机禁止删除，需先解除所有容器关联
- **容器删除同步**: 当 Docker 容器被删除时，应同步删除对应的关联记录

---

## 八、扩展性考虑

### 8.1 预留字段

当前设计已包含 `remark` 字段用于扩展存储 JSON 格式的附加信息。

### 8.2 未来扩展

| 扩展方向 | 建议 |
|----------|------|
| 容器分组 | 新增 `group_id` 字段，支持容器分组管理 |
| 容器标签 | 新增 `tags` 字段（JSON 格式），支持自定义标签 |
| 容器元数据 | 新增 `metadata` 字段（JSON 格式），存储扩展属性 |

---

## 九、版本历史

| 版本 | 日期 | 修改内容 | 作者 |
|------|------|----------|------|
| 1.0 | 2026-03-24 | 初始版本，创建 docker_container_link 表 | Database Modeler |

---

*文档版本: 1.0*
*最后更新: 2026-03-24*
