package com.gameplatform;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;

/**
 * 测试基类
 * 所有测试类继承此类，自动配置测试环境
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = GamePlatformApplication.class)
@ActiveProfiles("test")
@Transactional
public abstract class BaseTest {
    // 公共测试配置和方法可以放在这里
}
