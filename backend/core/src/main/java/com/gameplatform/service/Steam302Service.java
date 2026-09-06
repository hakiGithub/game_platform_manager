package com.gameplatform.service;

import com.gameplatform.vo.Steam302StatusVO;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Steam302 主机加速服务
 *
 * <p>主机级 Docker 化部署 Steamcommunity 302：安装（任务中心异步，hostId 互斥）、
 * 启动/停止（同步）、运行状态查询、S302.ini 服务开关读写。
 * 配置修改需重启容器生效（CLI 启动时按开关改写 /etc/hosts 与生成 Caddyfile）。</p>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface Steam302Service {

    /**
     * 查询主机上的 Steam302 部署状态
     *
     * @param hostId 主机 ID
     * @return 状态（未安装 / 已停止 / 运行中）
     */
    Steam302StatusVO status(Long hostId);

    /**
     * 提交安装任务（任务中心异步执行：拉镜像 → 起容器 → 等待证书生成 → 信任 CA）
     *
     * @param hostId 主机 ID
     * @return 任务 ID
     */
    String install(Long hostId);

    /**
     * 启动容器（同步，秒级）
     */
    void start(Long hostId);

    /**
     * 停止容器（同步，秒级）
     */
    void stop(Long hostId);

    /**
     * 读取 S302.ini [Setting] 全部键值（保持文件顺序）
     *
     * @return 有序 key=value 表
     */
    LinkedHashMap<String, String> getConfig(Long hostId);

    /**
     * 保存 S302.ini 键值（仅允许覆盖已存在的键，不新增/删除键）
     *
     * @param values 待修改的键值子集
     */
    void saveConfig(Long hostId, Map<String, String> values);

    /**
     * 切换「容器共享加速」：开启后 hosts 劫持条目指向宿主机 LAN IP
     * （bridge 容器与宿主机均可走代理），立即重写 hosts 生效，无需重启容器。
     *
     * @param enabled true=指向宿主机 LAN IP（#S302-LAN），false=指向 127.0.0.1（#S302）
     */
    void setContainerShare(Long hostId, boolean enabled);
}
