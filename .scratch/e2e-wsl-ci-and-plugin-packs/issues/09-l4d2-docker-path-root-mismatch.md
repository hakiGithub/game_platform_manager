# 09 — l4d2 Docker 实例的容器工作目录与镜像实际布局不一致（产品缺陷）

**Status:** ready-for-human
**Type:** bug
**Blocked by:** -

## 现象

`games/l4d2.yml` 的 `docker` 段声明：

```yaml
docker:
  image: cm2network/left4dead2
  tag: latest
  workingDir: /home/steam/l4d2-dedicated   # 容器内服务端根目录
```

`DockerAdapter` 把这个值写进 `configInfo.containerWorkDir`，`InstanceFileService`
又以它为 Docker 路由的相对路径基准。但**实际拉到的镜像里这个目录根本不存在**。

## 实测证据（2026-10-22，WSL Docker）

```
$ docker image inspect cm2network/left4dead2:latest --format '{{json .RepoDigests}}'
["laoyutang/l4d2-pure@sha256:582b739f..."]        # ← tag 被顶成了另一个镜像

$ docker run --rm --entrypoint /bin/sh cm2network/left4dead2:latest \
    -c 'test -d /home/steam/l4d2-dedicated && echo dir-exists || echo dir-MISSING'
dir-MISSING

$ docker run --rm --entrypoint /bin/sh cm2network/left4dead2:latest \
    -c 'ls -d /l4d2/left4dead2'
/l4d2/left4dead2                                    # 真实布局在 /l4d2
```

影响面：

1. **所有 Docker 部署的 l4d2 实例的文件视图全错**。`InstanceFileService.listFiles`
   对不存在的路径返回空列表（`find` + `ls` 双失败 → `List.of()`），
   `SourceModLogService.listFiles` 再把空列表当成"没有日志"，页面永远空表。
   `readTextFile` / `writeTextFile` 同理（写会 mkdir 出一个假的
   `/home/steam/l4d2-dedicated`，读则直接失败）。
2. **地图列表、ServerMod 插件列表、server.cfg 编辑等所有走相对路径的功能**
   在这类实例上一起失效。E2E 里这些用例此前"通过"，是因为它们只断言了
   "页面渲染 + 请求 200"，没有断言列表非空。
3. 被顶掉的 tag 让问题更隐蔽：`cm2network/left4dead2` 现在实际指向
   `laoyutang/l4d2-pure`，Image 标签写着 l4d2-server-next，两者的目录约定不同
   （一个 `/home/steam/l4d2-dedicated`，一个 `/l4d2`）。

## 待定修法（择一，需产品决策）

- **A. 元数据对齐镜像**：把 `docker.workingDir` 改成 `/l4d2`，`volumes` 改挂
  `/l4d2/left4dead2`，与 `dockerCompose` 段保持一致（compose 段本来就写
  `workingDir: /l4d2`）。改动最小，但要求镜像固定来源，不能用被顶 tag 的镜像。
- **B. 部署后自检**：`DockerAdapter.deploy` 成功后 docker exec 校验
  `workingDir` 存在，不存在则部署阶段直接失败并给出明确错误，而不是让上层
  各处返回空列表。
- **C. 运行时探测**：首次访问实例文件时探测几个候选根目录
  （`/home/steam/l4d2-dedicated`、`/l4d2`、`/app`），把命中的写入
  `runtimeMetadata.containerWorkDir`。

倾向 **A + B**：A 修当前元数据，B 让同类问题下次在部署阶段就暴露。

## 为什么不在票 04 里修

票 04 的目标是把一条 E2E 从"假绿/长红"变成"真实可信"。把产品缺陷混进用例修复，
会让 E2E 与产品两个概念搅在一起，也违反票 04 自己的验收条款
（"若判定为产品缺陷：另开票并在用例里注明阻塞原因，不静默改断言"）。

## 验收

- [ ] 明确 selected 修法并给出决策理由（ADR 或本文件追加）。
- [ ] 新建 Docker 部署的 l4d2 实例后，`GET /api/instances/{id}/files?path=/`
      能列出真实游戏目录（至少包含 `left4dead2`）。
- [ ] `workingDir` 不存在的镜像在部署阶段显式失败，错误信息可操作。
- [ ] 相关 E2E 用例的断言可以恢复为"非空列表"而不用夹具造文件。

## 关联

- 票 04（本次日志页用例）用"平台文件 API 造夹具"绕过本缺陷，不是修复。
- 票 07 记录的同一类问题（地图列表读宿主机 installPath）。
