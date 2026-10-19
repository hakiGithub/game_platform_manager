package com.gameplatform.plugin.l4d2.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 换图结果。
 *
 * <p>传输成功不等于服务器接受换图。调用方必须检查 accepted，并在需要终态时
 * 轮询状态接口；不能仅凭 HTTP 成功认定地图已经切换。
 */
@Data
public class ChangeMapResultVO implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 目标地图代码 */
    private String mapName;
    /** 服务器是否接受换图命令 */
    private Boolean accepted;
    /** 是否已经通过 status 确认真实地图变化 */
    private Boolean confirmed;
    /** 命令原始回显 */
    private String output;
    /** 可读失败原因 */
    private String error;
    /** 观察到的当前地图（未确认时可能仍为旧地图） */
    private String currentMap;
}
