package com.gameplatform.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gameplatform.common.result.PageResult;
import com.gameplatform.dto.PageQueryDTO;
import com.gameplatform.entity.OperationLog;
import com.gameplatform.mapper.OperationLogMapper;
import com.gameplatform.service.LogService;
import com.gameplatform.vo.LogVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 操作日志服务实现类
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogServiceImpl implements LogService {

    private final OperationLogMapper operationLogMapper;

    @Override
    @Async
    public void log(String operator, String operationType, String operationTarget,
                    String operationContent, String operationResult, String ipAddress, String errorMessage) {
        try {
            OperationLog log = new OperationLog();
            log.setOperator(operator);
            log.setOperationType(operationType);
            log.setOperationTarget(operationTarget);
            log.setOperationContent(operationContent);
            log.setOperationResult(operationResult);
            log.setIpAddress(ipAddress);
            log.setErrorMessage(errorMessage);
            
            operationLogMapper.insert(log);
        } catch (Exception e) {
            log.error("记录操作日志失败: {}", e.getMessage());
        }
    }

    @Override
    public PageResult<LogVO> pageLogs(PageQueryDTO queryDTO) {
        LambdaQueryWrapper<OperationLog> wrapper = new LambdaQueryWrapper<>();
        
        // 关键词搜索
        if (queryDTO.getKeyword() != null && !queryDTO.getKeyword().isEmpty()) {
            wrapper.like(OperationLog::getOperator, queryDTO.getKeyword())
                    .or()
                    .like(OperationLog::getOperationType, queryDTO.getKeyword())
                    .or()
                    .like(OperationLog::getOperationTarget, queryDTO.getKeyword())
                    .or()
                    .like(OperationLog::getOperationContent, queryDTO.getKeyword());
        }
        
        // 排序
        wrapper.orderByDesc(OperationLog::getCreateTime);
        
        Page<OperationLog> page = new Page<>(queryDTO.getCurrent(), queryDTO.getSize());
        Page<OperationLog> result = operationLogMapper.selectPage(page, wrapper);
        
        List<LogVO> voList = result.getRecords().stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        
        return new PageResult<>(voList, result.getTotal(), queryDTO.getCurrent(), queryDTO.getSize());
    }

    @Override
    public List<LogVO> getLogsByOperator(String operator) {
        List<OperationLog> logs = operationLogMapper.selectByOperator(operator);
        return logs.stream().map(this::convertToVO).collect(Collectors.toList());
    }

    @Override
    public List<LogVO> getLogsByOperationType(String operationType) {
        List<OperationLog> logs = operationLogMapper.selectByOperationType(operationType);
        return logs.stream().map(this::convertToVO).collect(Collectors.toList());
    }

    @Override
    public List<LogVO> getRecentLogs(int limit) {
        List<OperationLog> logs = operationLogMapper.selectRecentLogs(limit);
        return logs.stream().map(this::convertToVO).collect(Collectors.toList());
    }

    /**
     * 转换为VO
     */
    private LogVO convertToVO(OperationLog log) {
        LogVO vo = new LogVO();
        BeanUtil.copyProperties(log, vo);
        return vo;
    }

}
