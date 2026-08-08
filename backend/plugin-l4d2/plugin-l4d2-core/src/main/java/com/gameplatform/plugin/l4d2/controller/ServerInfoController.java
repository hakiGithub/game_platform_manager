package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.l4d2.dto.ServerInfoUpdateDTO;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.util.GbkCodecUtil;
import com.gameplatform.plugin.l4d2.vo.ServerInfoVO;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.Charset;

/**
 * L4D2 服务器信息控制器：hostname / motd / host 文件管理。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Tag(name = "L4D2 服务器信息", description = "hostname/motd/host 管理")
@RestController
@RequestMapping("/api/plugin/l4d2/server-info")
@RequiredArgsConstructor
public class ServerInfoController {

    private final InstanceFileService instanceFileService;
    private final InstanceQueryService instanceQueryService;
    private final L4D2PathResolver pathResolver;
    private final Charset gbk = GbkCodecUtil.gbk();

    /**
     * 获取服务器信息。
     */
    @Operation(summary = "获取服务器信息", description = "读取 hostname/motd/host 文件内容")
    @GetMapping("/get")
    public Result<ServerInfoVO> get(@Parameter(description = "实例ID") @RequestParam Long instanceId) {
        log.info("获取服务器信息, instanceId: {}", instanceId);

        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            return Result.fail("实例不存在");
        }

        ServerInfoVO vo = new ServerInfoVO();
        vo.setHostname(readOrDefault(instanceId, pathResolver.getHostnameConfigPath(), ""));
        vo.setMotd(readOrDefault(instanceId, pathResolver.getMotdPath(), ""));
        vo.setHost(readOrDefault(instanceId, pathResolver.getHostInfoPath(), ""));
        return Result.success(vo);
    }

    /**
     * 更新服务器信息（字段为 null 表示不更新）。
     */
    @Operation(summary = "更新服务器信息", description = "写入 hostname/motd/host 文件内容")
    @PostMapping("/update")
    public Result<Void> update(@RequestBody ServerInfoUpdateDTO dto) {
        log.info("更新服务器信息, instanceId: {}", dto.getInstanceId());

        InstanceVO instance = instanceQueryService.getInstanceById(dto.getInstanceId());
        if (instance == null) {
            return Result.fail("实例不存在");
        }
        Long instanceId = dto.getInstanceId();

        if (dto.getHostname() != null) {
            instanceFileService.writeTextFile(instanceId, pathResolver.getHostnameConfigPath(), dto.getHostname());
        }
        if (dto.getMotd() != null) {
            instanceFileService.writeTextFile(instanceId, pathResolver.getMotdPath(), dto.getMotd());
        }
        if (dto.getHost() != null) {
            instanceFileService.writeTextFile(instanceId, pathResolver.getHostInfoPath(), dto.getHost());
        }
        return Result.success(null);
    }

    private String readOrDefault(Long instanceId, String path, String def) {
        try {
            return instanceFileService.readTextFile(instanceId, path, gbk);
        } catch (Exception e) {
            log.warn("读取文件失败 path={}, err={}", path, e.getMessage());
            return def;
        }
    }
}
