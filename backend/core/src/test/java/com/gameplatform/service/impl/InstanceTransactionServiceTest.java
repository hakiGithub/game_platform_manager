package com.gameplatform.service.impl;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.mapper.GameInstanceMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InstanceTransactionServiceTest {

    @Mock
    private GameInstanceMapper instanceMapper;

    @InjectMocks
    private InstanceTransactionService transactionService;

    @Test
    void deleteRecordPhysicallyDeletesInstance() {
        when(instanceMapper.physicalDeleteById(1L)).thenReturn(1);

        transactionService.deleteRecord(1L);

        verify(instanceMapper).physicalDeleteById(1L);
    }

    @Test
    void deleteRecordRejectsMissingInstance() {
        when(instanceMapper.physicalDeleteById(1L)).thenReturn(0);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> transactionService.deleteRecord(1L));

        assertEquals("删除实例失败，记录不存在或已被删除", exception.getMessage());
    }
}
