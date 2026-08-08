package com.gameplatform.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 任务类型 VO
 *
 * <p>用于 {@code GET /api/tasks/types} 接口返回已注册的任务类型列表。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskTypeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 任务来源（大写）：MAIN / L4D2 / {gameCode}
     */
    private String source;

    /**
     * 任务类型标识
     */
    private String taskType;

    /**
     * 任务类型显示名称
     */
    private String displayName;
}
