package com.gameplatform.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gameplatform.common.result.PageResult;
import com.gameplatform.dto.PageQueryDTO;
import com.gameplatform.vo.LogVO;

import java.util.List;

/**
 * 操作日志服务接口
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface LogService {

    /**
     * 记录操作日志
     *
     * @param operator         操作人
     * @param operationType    操作类型
     * @param operationTarget  操作目标
     * @param operationContent 操作内容
     * @param operationResult  操作结果
     * @param ipAddress        IP地址
     * @param errorMessage     错误信息
     */
    void log(String operator, String operationType, String operationTarget,
             String operationContent, String operationResult, String ipAddress, String errorMessage);

    /**
     * 分页查询日志
     *
     * @param queryDTO 查询DTO
     * @return 分页结果
     */
    PageResult<LogVO> pageLogs(PageQueryDTO queryDTO);

    /**
     * 根据操作人查询日志
     *
     * @param operator 操作人
     * @return 日志列表
     */
    List<LogVO> getLogsByOperator(String operator);

    /**
     * 根据操作类型查询日志
     *
     * @param operationType 操作类型
     * @return 日志列表
     */
    List<LogVO> getLogsByOperationType(String operationType);

    /**
     * 查询最近的操作日志
     *
     * @param limit 数量限制
     * @return 日志列表
     */
    List<LogVO> getRecentLogs(int limit);

}
