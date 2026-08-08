package com.gameplatform.plugin.l4d2.extension;

import lombok.Data;

import java.io.Serializable;

/**
 * L4D2 玩家统计-玩家记录业务数据。
 * <p>
 * 对齐源项目 {@code model.PlayerStatPlayer}。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class PlayerStatPlayerSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 实例ID */
    private Long instanceId;

    /** 关联的快照 ID（PlayerStatSnapshotResource.id，雪花ID 字符串） */
    private String snapshotId;

    /** 时间戳（Unix 秒） */
    private Long timestamp;

    /** SteamID（STEAM_X:Y:Z 格式） */
    private String steamId;

    /** 玩家名 */
    private String name;

    /** IP（IP:port 格式） */
    private String ip;

    /** 归属地（GeoIpService 查询结果，省份） */
    private String location;

    /** 状态 */
    private String status;

    /** 延迟（ms） */
    private Integer delay;

    /** 丢包率（%） */
    private Integer loss;

    /** 在线时长（如 1:23:45） */
    private String duration;

    /** 连接速率 */
    private Integer linkRate;
}
