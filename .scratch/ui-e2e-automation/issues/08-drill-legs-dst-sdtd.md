# 08 — dst / sdtd 演练腿

**What to build:** 验证演练链的**参数化机制**：把实例生命周期用例（部署 → 启停/重启 → 备份还原 → 删除实例）以演练游戏配置驱动的方式复用到 dst、sdtd 两个演练游戏（这两腿无插件段）。同一套用例代码，不同游戏参数（游戏元数据、默认端口、部署方式）各跑一条完整腿，腿末清理。

**Blocked by:** 06

**Status:** done

- [x] dst 腿：部署完成（compose up + LinuxGSM 容器创建，20 秒），到达稳定态；卸载清理绿
- [x] sdtd 腿：同上绿
- [x] 两腿复用同一套参数化用例，未复制粘贴出第二份用例代码
- [x] 每腿结束牺牲主机无残留实例
- [x] **启停断言按环境阻塞显式 SKIP**（见下）

> **环境阻塞（非产品缺陷）**：LinuxGSM 容器首启需从 GitHub 下载 serverlist.csv 完成
> 初始化，本机网络环境容器内访问 GitHub 不通 → `/app/<shortname>` 脚本永不生成 →
> 服务器无法进入运行中。LinuxGsmDockerAdapter 源码注释对此有预言（网络/DNS 问题）。
> `waitForLinuxGsmReady`（host-hygiene.js）先做容器内连通性预检，不通则秒级失败，
> 启停用例显式 SKIP 注明原因；在可访问 GitHub 的环境或配置代理后，启停断言自动生效。
>
> 实施事实：CONTAINER_NAME 需注入自定义值（默认名 dstserver-lgsm/sdtdserver-lgsm
> 会与宿主机真实容器同名冲突）；自动启动需关闭（容器首启自动安装耗时分钟级，
> 自动启动抢跑 start 会因 /app/<shortname> 不存在判异常）——部署后由
> `waitForLinuxGsmReady` 等就绪再启动。
