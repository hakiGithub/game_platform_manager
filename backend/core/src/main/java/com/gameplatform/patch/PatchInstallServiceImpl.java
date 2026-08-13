package com.gameplatform.patch;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.plugin.patch.HostCapabilities;
import com.gameplatform.plugin.patch.PatchInstallRequest;
import com.gameplatform.plugin.patch.PatchInstallService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.plugin.task.TaskService;
import com.gameplatform.plugin.task.TaskSubmitRequest;
import com.gameplatform.vo.InstanceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 补丁安装服务实现（core，ADR-0006 决策 1）
 *
 * <p>{@link #install} 提交任务中心任务（source=MAIN、taskType=PATCH_INSTALL），
 * scopeType=HOST + scopeKey=hostId 实现同主机互斥（ADR-0006 决策 8）；
 * {@link #probeHost} 同步执行宿主机能力探测供 UI 预检。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PatchInstallServiceImpl implements PatchInstallService {

    private final TaskService taskService;
    private final InstanceQueryService instanceQueryService;
    private final HostCapabilityProber prober;

    @Override
    public String install(PatchInstallRequest request) {
        if (request == null || request.getInstanceId() == null
                || request.getUrl() == null || request.getUrl().isBlank()
                || request.getTargetPath() == null) {
            throw new BusinessException("instanceId/url/targetPath 不能为空");
        }

        InstanceVO instance = instanceQueryService.getInstanceById(request.getInstanceId());
        if (instance == null) {
            throw new BusinessException("实例不存在: " + request.getInstanceId());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instanceId", request.getInstanceId());
        payload.put("url", request.getUrl());
        payload.put("targetPath", request.getTargetPath());
        if (request.getFormat() != null) {
            payload.put("format", request.getFormat());
        }
        if (request.getSha256() != null) {
            payload.put("sha256", request.getSha256());
        }

        TaskSubmitRequest submit = TaskSubmitRequest.builder()
                .taskType("PATCH_INSTALL")
                .source("MAIN")
                .scopeType("HOST")
                .scopeKey(String.valueOf(instance.getHostId()))
                .scopeName("补丁安装: " + instance.getInstanceName())
                .payload(payload)
                .build();

        String taskId = taskService.submit(submit);
        log.info("提交补丁安装任务: taskId={}, instanceId={}, url={}",
                taskId, request.getInstanceId(), request.getUrl());
        return taskId;
    }

    @Override
    public HostCapabilities probeHost(Long hostId) {
        if (hostId == null) {
            throw new BusinessException("hostId 不能为空");
        }
        return prober.probe(hostId);
    }
}
