package com.gameplatform.listener;

import com.gameplatform.service.GameMetadataScanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 游戏元数据初始化监听器
 * 在应用启动完成后自动扫描加载游戏元数据
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(100)
public class GameMetadataInitListener implements ApplicationListener<ApplicationReadyEvent> {

    private final GameMetadataScanner gameMetadataScanner;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("应用启动完成，开始执行游戏元数据初始化...");

        try {
            // 执行扫描加载
            GameMetadataScanner.ScanResult result = gameMetadataScanner.scanAndLoad();

            // 输出扫描结果
            log.info("========================================");
            log.info("游戏元数据扫描加载完成");
            log.info("========================================");
            log.info("扫描文件总数: {}", result.getTotalFiles());
            log.info("新增游戏数量: {}", result.getSuccessCount());
            log.info("更新游戏数量: {}", result.getUpdateCount());
            log.info("失败数量: {}", result.getErrorCount());

            if (!result.getLoadedGames().isEmpty()) {
                log.info("已加载游戏列表:");
                result.getLoadedGames().forEach(gameCode -> log.info("  - {}", gameCode));
            }

            if (!result.getErrors().isEmpty()) {
                log.warn("加载过程中的错误:");
                result.getErrors().forEach(error -> log.warn("  ! {}", error));
            }

            log.info("========================================");

        } catch (Exception e) {
            log.error("游戏元数据初始化失败", e);
            // 不抛出异常，避免影响应用启动
        }
    }
}
