package com.gameplatform.plugin.l4d2.extension;

import lombok.Data;

import java.io.Serializable;

/**
 * L4D2 玩家统计快照业务数据。
 * <p>
 * 对齐源项目 {@code model.PlayerStatSnapshot}。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class PlayerStatSnapshotSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 实例ID */
    private Long instanceId;

    /** 时间戳（Unix 秒） */
    private Long timestamp;

    /** 服务器是否在线 */
    private Boolean serverOnline;

    /** 采集是否成功 */
    private Boolean collectOk;

    /** 玩家数 */
    private Integer playerCount;

    /** 最大玩家数 */
    private Integer maxPlayers;

    /** 地图 */
    private String map;

    /** 主机名 */
    private String hostname;

    /** 难度（中文：简单/普通/高级/专家/未知） */
    private String difficulty;

    /** 游戏模式（中文：合作/写实/对抗/突变模式N 等） */
    private String gameMode;

    /** 错误信息 */
    private String errorMessage;
}
