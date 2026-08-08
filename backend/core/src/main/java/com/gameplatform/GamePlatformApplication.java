package com.gameplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 游戏服务器统一管理平台启动类
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@SpringBootApplication
@EnableScheduling
public class GamePlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(GamePlatformApplication.class, args);
    }

}
