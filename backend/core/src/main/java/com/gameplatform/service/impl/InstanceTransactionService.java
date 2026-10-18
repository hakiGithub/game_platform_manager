package com.gameplatform.service.impl;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.mapper.GameInstanceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 实例记录事务边界。
 *
 * <p>远程卸载和插件钩子不进入该事务，避免长时间持有 SQLite 读快照。</p>
 */
@Service
@RequiredArgsConstructor
public class InstanceTransactionService {

    private final GameInstanceMapper instanceMapper;

    @Transactional(rollbackFor = Exception.class)
    public void deleteRecord(Long id) {
        int rows = instanceMapper.physicalDeleteById(id);
        if (rows == 0) {
            throw new BusinessException("删除实例失败，记录不存在或已被删除");
        }
    }
}
