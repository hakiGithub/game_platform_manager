package com.gameplatform.service;

import com.gameplatform.service.impl.InstanceServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNull;

class InstanceDeleteTransactionBoundaryTest {

    @Test
    @DisplayName("删除实例入口不持有覆盖远端卸载的长事务")
    void deleteInstanceIsNotTransactional() throws Exception {
        Method singleArg = InstanceServiceImpl.class.getMethod("deleteInstance", Long.class);
        Method withContainerFlag = InstanceServiceImpl.class.getMethod(
                "deleteInstance", Long.class, boolean.class);

        assertNull(singleArg.getAnnotation(Transactional.class));
        assertNull(withContainerFlag.getAnnotation(Transactional.class));
    }
}
