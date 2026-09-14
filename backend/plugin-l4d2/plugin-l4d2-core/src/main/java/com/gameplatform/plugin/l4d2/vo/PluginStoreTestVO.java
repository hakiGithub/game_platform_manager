package com.gameplatform.plugin.l4d2.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 仓库连接测试结果视图。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class PluginStoreTestVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 归一后的仓库地址（owner/repo） */
    private String repo;

    /** 实际使用的分支 */
    private String branch;

    /** 发现的插件数量（仓库根 plugins/ 目录下的一级子目录数） */
    private int pluginCount;
}
