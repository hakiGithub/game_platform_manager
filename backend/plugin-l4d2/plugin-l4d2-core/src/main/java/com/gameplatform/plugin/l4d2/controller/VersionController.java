package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.l4d2.util.BuildInfoReader;
import com.gameplatform.plugin.l4d2.vo.BuildInfoVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 版本信息控制器（对齐 plan §6.3.3）。
 *
 * <p>提供两个端点：
 * <ul>
 *   <li>{@code GET /api/plugin/l4d2/version}：返回完整构建信息（BuildInfoVO）</li>
 *   <li>{@code GET /api/plugin/l4d2/version/short}：仅返回版本号字符串，对齐源项目极简接口</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Tag(name = "L4D2 版本信息", description = "插件构建版本信息查询")
@RestController
@RequestMapping("/api/plugin/l4d2/version")
@RequiredArgsConstructor
public class VersionController {

    private final BuildInfoReader buildInfoReader;

    /**
     * 获取完整版本信息。
     */
    @Operation(summary = "获取完整版本信息", description = "返回插件版本、Git commit、构建时间、JDK/PF4J/Spring Boot 版本等")
    @GetMapping
    public Result<BuildInfoVO> getVersion() {
        log.info("获取版本信息");
        return Result.success(buildInfoReader.toVO());
    }

    /**
     * 获取版本号字符串（对齐源项目极简接口）。
     */
    @Operation(summary = "获取版本号字符串", description = "仅返回 version 字符串，对齐源项目 {version: \"v1.2.3\"} 接口")
    @GetMapping("/short")
    public Result<String> getShortVersion() {
        log.info("获取版本号字符串");
        return Result.success(buildInfoReader.getVersion());
    }
}
