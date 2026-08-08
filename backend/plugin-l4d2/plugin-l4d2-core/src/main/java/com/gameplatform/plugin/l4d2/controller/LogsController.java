package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.l4d2.service.SourceModLogService;
import com.gameplatform.plugin.l4d2.vo.LogFileInfoVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * L4D2 SourceMod 日志控制器：文件列表、内容读取、SSE 实时流。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Tag(name = "L4D2 日志", description = "SourceMod 日志 SSE 流")
@RestController
@RequestMapping("/api/plugin/l4d2/logs")
@RequiredArgsConstructor
public class LogsController {

    private final SourceModLogService logService;

    /**
     * 日志文件列表。
     */
    @Operation(summary = "日志文件列表", description = "列出 SourceMod 日志目录下的日志文件")
    @GetMapping("/files")
    public Result<List<LogFileInfoVO>> files(@Parameter(description = "实例ID") @RequestParam Long instanceId) {
        return Result.success(logService.listFiles(instanceId));
    }

    /**
     * 日志文件内容（末尾 200KB）。
     */
    @Operation(summary = "日志文件内容", description = "读取日志文件末尾 200KB 内容")
    @GetMapping("/content")
    public Result<String> content(@Parameter(description = "实例ID") @RequestParam Long instanceId,
                                  @Parameter(description = "文件名") @RequestParam String file) {
        return Result.success(logService.getContent(instanceId, file));
    }

    /**
     * SSE 实时日志流。
     */
    @Operation(summary = "SSE 实时日志流", description = "推送日志末尾 200KB 历史后按 1s 间隔轮询增量")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Parameter(description = "实例ID") @RequestParam Long instanceId,
                             @Parameter(description = "文件名") @RequestParam String file) {
        return logService.stream(instanceId, file);
    }
}
