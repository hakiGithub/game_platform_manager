package com.gameplatform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gameplatform.entity.OperationLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 操作日志Mapper接口
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLog> {

    /**
     * 根据操作人查询日志
     *
     * @param operator 操作人
     * @return 日志列表
     */
    @Select("SELECT * FROM operation_log WHERE operator = #{operator} AND is_deleted = 0 ORDER BY create_time DESC")
    List<OperationLog> selectByOperator(@Param("operator") String operator);

    /**
     * 根据操作类型查询日志
     *
     * @param operationType 操作类型
     * @return 日志列表
     */
    @Select("SELECT * FROM operation_log WHERE operation_type = #{operationType} AND is_deleted = 0 ORDER BY create_time DESC")
    List<OperationLog> selectByOperationType(@Param("operationType") String operationType);

    /**
     * 根据操作目标查询日志
     *
     * @param operationTarget 操作目标
     * @return 日志列表
     */
    @Select("SELECT * FROM operation_log WHERE operation_target = #{operationTarget} AND is_deleted = 0 ORDER BY create_time DESC")
    List<OperationLog> selectByOperationTarget(@Param("operationTarget") String operationTarget);

    /**
     * 查询最近的操作日志
     *
     * @param limit 数量限制
     * @return 日志列表
     */
    @Select("SELECT * FROM operation_log WHERE is_deleted = 0 ORDER BY create_time DESC LIMIT #{limit}")
    List<OperationLog> selectRecentLogs(@Param("limit") int limit);

}
