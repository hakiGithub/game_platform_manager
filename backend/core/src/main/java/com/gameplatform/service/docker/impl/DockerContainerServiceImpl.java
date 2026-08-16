package com.gameplatform.service.docker.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.common.result.ResultCode;
import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.deploy.HostCredentials;
import com.gameplatform.dto.docker.ContainerLogQueryDTO;
import com.gameplatform.dto.docker.ContainerOperationDTO;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.service.docker.DockerContainerService;
import com.gameplatform.util.SshUtil;
import com.gameplatform.vo.docker.ContainerDetailVO;
import com.gameplatform.vo.docker.ContainerHealthVO;
import com.gameplatform.vo.docker.ContainerListVO;
import com.gameplatform.vo.docker.ContainerStatsVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Docker容器管理服务实现
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DockerContainerServiceImpl implements DockerContainerService {

    private final HostMapper hostMapper;
    private final GameInstanceMapper instanceMapper;
    private final SshUtil sshUtil;
    private final DeploymentAccess deployAccess;
    private final ObjectMapper objectMapper;

    @Override
    public List<ContainerListVO> listContainers(Long hostId, String status, String keyword, Boolean linked) {
        Host host = getHost(hostId);
        
        // 执行docker ps命令获取容器列表
        String command = "docker ps -a --format '{{json .}}'";
        SshUtil.CommandResult result = executeCommand(host, command, 30000);
        
        if (!result.isSuccess()) {
            throw new BusinessException(ResultCode.HOST_CONNECTION_FAILED, "获取容器列表失败: " + result.getError());
        }
        
        List<ContainerListVO> containers = parseContainerList(result.getOutput());

        // 关联平台实例（按容器 ID/名称匹配，与同步对账同一套识别语义）
        enrichInstanceLink(host, containers);

        // 过滤状态
        if (status != null && !status.isEmpty() && !"all".equalsIgnoreCase(status)) {
            containers = containers.stream()
                    .filter(c -> c.getStatus().equalsIgnoreCase(status))
                    .toList();
        }
        
        // 过滤关键词
        if (keyword != null && !keyword.isEmpty()) {
            String lowerKeyword = keyword.toLowerCase();
            containers = containers.stream()
                    .filter(c -> c.getContainerName().toLowerCase().contains(lowerKeyword) ||
                            (c.getImageName() != null && c.getImageName().toLowerCase().contains(lowerKeyword)))
                    .toList();
        }
        
        // 过滤关联状态
        if (Boolean.TRUE.equals(linked)) {
            containers = containers.stream()
                    .filter(c -> Boolean.TRUE.equals(c.getIsLinked()))
                    .toList();
        } else if (Boolean.FALSE.equals(linked)) {
            containers = containers.stream()
                    .filter(c -> !Boolean.TRUE.equals(c.getIsLinked()))
                    .toList();
        }

        return containers;
    }

    @Override
    public ContainerDetailVO getContainerDetail(Long hostId, String containerId) {
        Host host = getHost(hostId);

        // 获取容器详细信息
        String command = String.format("docker inspect %s", containerId);
        SshUtil.CommandResult result = executeCommand(host, command, 10000);

        if (!result.isSuccess()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "容器不存在: " + containerId);
        }

        ContainerDetailVO detail = parseContainerDetail(result.getOutput());
        // 关联平台实例（与列表同一套识别语义）
        enrichInstanceLinkForDetail(host, detail);
        return detail;
    }

    /**
     * 详情级实例关联填充（识别语义与 {@link #enrichInstanceLink} 一致）。
     */
    private void enrichInstanceLinkForDetail(Host host, ContainerDetailVO detail) {
        if (detail == null || detail.getContainerId() == null) {
            return;
        }
        List<GameInstance> instances = instanceMapper.selectByHostId(host.getId());
        if (instances == null || instances.isEmpty()) {
            return;
        }
        String cid = detail.getContainerId().toLowerCase();
        String cname = detail.getContainerName() == null ? "" : detail.getContainerName();
        for (GameInstance inst : instances) {
            if (matchInstance(inst, cid, cname)) {
                detail.setIsLinked(true);
                ContainerDetailVO.LinkInfo linkInfo = new ContainerDetailVO.LinkInfo();
                linkInfo.setInstanceId(inst.getId());
                linkInfo.setInstanceName(inst.getInstanceName());
                linkInfo.setLinkType("instance");
                linkInfo.setAutoLinked(true);
                detail.setLinkInfo(linkInfo);
                return;
            }
        }
    }

    @Override
    public ContainerOperationResult startContainer(Long hostId, String containerId) {
        Host host = getHost(hostId);
        
        String command = String.format("docker start %s", containerId);
        SshUtil.CommandResult result = executeCommand(host, command, 30000);
        
        if (result.isSuccess()) {
            return new ContainerOperationResult(true, containerId, "容器启动成功");
        } else {
            return new ContainerOperationResult(false, containerId, "容器启动失败: " + result.getError());
        }
    }

    @Override
    public ContainerOperationResult stopContainer(Long hostId, String containerId, ContainerOperationDTO dto) {
        Host host = getHost(hostId);
        
        StringBuilder command = new StringBuilder("docker stop");
        
        if (dto != null && Boolean.TRUE.equals(dto.getForce())) {
            command.append(" -t 0");
        } else if (dto != null && dto.getTimeout() != null) {
            command.append(" -t ").append(dto.getTimeout());
        }
        
        command.append(" ").append(containerId);
        
        SshUtil.CommandResult result = executeCommand(host, command.toString(), 60000);
        
        if (result.isSuccess()) {
            return new ContainerOperationResult(true, containerId, "容器已停止");
        } else {
            return new ContainerOperationResult(false, containerId, "容器停止失败: " + result.getError());
        }
    }

    @Override
    public ContainerOperationResult restartContainer(Long hostId, String containerId, ContainerOperationDTO dto) {
        Host host = getHost(hostId);
        
        StringBuilder command = new StringBuilder("docker restart");
        
        if (dto != null && dto.getTimeout() != null) {
            command.append(" -t ").append(dto.getTimeout());
        }
        
        command.append(" ").append(containerId);
        
        SshUtil.CommandResult result = executeCommand(host, command.toString(), 60000);
        
        if (result.isSuccess()) {
            return new ContainerOperationResult(true, containerId, "容器重启成功");
        } else {
            return new ContainerOperationResult(false, containerId, "容器重启失败: " + result.getError());
        }
    }

    @Override
    public ContainerOperationResult deleteContainer(Long hostId, String containerId, ContainerOperationDTO dto) {
        Host host = getHost(hostId);
        
        StringBuilder command = new StringBuilder("docker rm");
        
        if (dto != null && Boolean.TRUE.equals(dto.getForce())) {
            command.append(" -f");
        }
        
        if (dto != null && Boolean.TRUE.equals(dto.getVolumes())) {
            command.append(" -v");
        }
        
        command.append(" ").append(containerId);
        
        SshUtil.CommandResult result = executeCommand(host, command.toString(), 30000);
        
        if (result.isSuccess()) {
            return new ContainerOperationResult(true, containerId, "容器已删除");
        } else {
            return new ContainerOperationResult(false, containerId, "容器删除失败: " + result.getError());
        }
    }

    @Override
    public ContainerStatsVO getContainerStats(Long hostId, String containerId) {
        Host host = getHost(hostId);
        
        String command = String.format("docker stats --no-stream --format '{{json .}}' %s", containerId);
        SshUtil.CommandResult result = executeCommand(host, command, 10000);
        
        if (!result.isSuccess()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "获取容器统计失败: " + result.getError());
        }
        
        return parseContainerStats(result.getOutput());
    }

    @Override
    public ContainerHealthVO getContainerHealth(Long hostId, String containerId) {
        Host host = getHost(hostId);
        
        // 获取健康检查状态
        String command = String.format(
                "docker inspect --format '{{json .State.Health}}' %s 2>/dev/null || echo '{}'",
                containerId);
        SshUtil.CommandResult result = executeCommand(host, command, 10000);
        
        ContainerHealthVO healthVO = new ContainerHealthVO();
        healthVO.setContainerId(containerId);
        healthVO.setStatus("none");
        
        if (result.isSuccess() && !result.getOutput().trim().isEmpty() && !result.getOutput().trim().equals("{}")) {
            try {
                JsonNode healthNode = objectMapper.readTree(result.getOutput());
                if (healthNode.has("Status")) {
                    healthVO.setStatus(healthNode.get("Status").asText());
                }
                // 解析其他健康检查信息
            } catch (Exception e) {
                log.warn("解析健康检查信息失败: {}", e.getMessage());
            }
        }
        
        return healthVO;
    }

    @Override
    public ContainerLogVO getContainerLogs(Long hostId, String containerId, ContainerLogQueryDTO query) {
        Host host = getHost(hostId);
        
        StringBuilder command = new StringBuilder("docker logs");
        
        if (query != null && query.getLines() != null) {
            command.append(" --tail ").append(Math.min(query.getLines(), 2000));
        } else {
            command.append(" --tail 100");
        }
        
        if (query != null && Boolean.TRUE.equals(query.getTimestamps())) {
            command.append(" --timestamps");
        }
        
        if (query != null && query.getSince() != null && !query.getSince().isEmpty()) {
            command.append(" --since ").append(query.getSince());
        }
        
        command.append(" ").append(containerId).append(" 2>&1");
        
        SshUtil.CommandResult result = executeCommand(host, command.toString(), 30000);
        
        List<LogLine> logs = new ArrayList<>();
        String[] lines = result.getOutput().split("\n");
        
        for (String line : lines) {
            if (!line.isEmpty()) {
                // 简单处理，实际应该解析时间戳
                logs.add(new LogLine(null, line));
            }
        }
        
        // 关键词过滤
        if (query != null && query.getKeyword() != null && !query.getKeyword().isEmpty()) {
            String keyword = query.getKeyword().toLowerCase();
            logs = logs.stream()
                    .filter(l -> l.content().toLowerCase().contains(keyword))
                    .toList();
        }
        
        return new ContainerLogVO(containerId, logs, logs.size());
    }

    // ========== 私有方法 ==========

    private Host getHost(Long hostId) {
        Host host = hostMapper.selectById(hostId);
        if (host == null) {
            throw new BusinessException(ResultCode.HOST_NOT_FOUND);
        }
        return host;
    }

    private SshUtil.CommandResult executeCommand(Host host, String command) {
        return executeCommand(host, command, 30000);
    }

    private SshUtil.CommandResult executeCommand(Host host, String command, long timeoutMs) {
        HostCredentials conn = deployAccess.credentials(host);
        return sshUtil.executeCommand(
                conn.host(), conn.port(), conn.username(), conn.privateKey(), conn.password(),
                command,
                timeoutMs
        );
    }

    private List<ContainerListVO> parseContainerList(String output) {
        List<ContainerListVO> containers = new ArrayList<>();
        String[] lines = output.split("\n");
        
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            
            try {
                // 移除可能的引号
                if (line.startsWith("'") && line.endsWith("'")) {
                    line = line.substring(1, line.length() - 1);
                }
                
                JsonNode node = objectMapper.readTree(line);
                ContainerListVO container = new ContainerListVO();
                
                container.setContainerId(node.has("ID") ? node.get("ID").asText() : "");
                container.setContainerName(extractContainerName(node.has("Names") ? node.get("Names").asText() : ""));
                container.setStatus(parseStatus(node.has("State") ? node.get("State").asText() : ""));
                container.setState(node.has("Status") ? node.get("Status").asText() : "");
                
                // 解析镜像信息
                String image = node.has("Image") ? node.get("Image").asText() : "";
                parseImageInfo(image, container);
                
                // 解析端口映射
                String ports = node.has("Ports") ? node.get("Ports").asText() : "";
                container.setPorts(parsePorts(ports));
                
                container.setIsLinked(false);
                
                containers.add(container);
            } catch (Exception e) {
                log.warn("解析容器信息失败: {}, line: {}", e.getMessage(), line);
            }
        }
        
        return containers;
    }

    /**
     * 容器 ↔ 平台实例关联填充（与 DockerInstanceSyncStrategy 同一套识别语义）。
     *
     * <p>匹配优先级：
     * <ol>
     *   <li>runtime_metadata.containerId（支持 12 位短 ID 与 64 位完整 ID 互为前缀）</li>
     *   <li>runtime_metadata.containerName / configInfo（CONTAINER_NAME 等）精确匹配容器名</li>
     *   <li>docker 类默认命名 game-instance-{id}</li>
     *   <li>docker-compose 项目名前缀 game{id}_（compose 容器名规范）</li>
     * </ol>
     */
    private void enrichInstanceLink(Host host, List<ContainerListVO> containers) {
        if (containers == null || containers.isEmpty()) {
            return;
        }
        List<GameInstance> instances = instanceMapper.selectByHostId(host.getId());
        if (instances == null || instances.isEmpty()) {
            return;
        }
        for (ContainerListVO container : containers) {
            String cid = container.getContainerId() == null ? "" : container.getContainerId().toLowerCase();
            String cname = container.getContainerName() == null ? "" : container.getContainerName();
            for (GameInstance inst : instances) {
                if (matchInstance(inst, cid, cname)) {
                    container.setIsLinked(true);
                    container.setLinkedInstanceId(inst.getId());
                    container.setLinkedInstanceName(inst.getInstanceName());
                    break;
                }
            }
        }
    }

    private boolean matchInstance(GameInstance inst, String containerId, String containerName) {
        // 1. runtime_metadata.containerId（短 ID/完整 ID 互为前缀）
        Map<String, Object> runtime = inst.getRuntimeMetadata();
        if (runtime != null) {
            Object cidObj = runtime.get("containerId");
            if (cidObj instanceof String s && !s.isBlank()) {
                String expected = s.toLowerCase();
                if (containerId.startsWith(expected) || expected.startsWith(containerId)) {
                    return true;
                }
            }
            Object nameObj = runtime.get("containerName");
            if (nameObj instanceof String n && !n.isBlank() && n.equals(containerName)) {
                return true;
            }
        }
        // 2. configInfo 容器名（compose 模板变量 / 显式配置）
        Map<String, Object> config = inst.getConfigInfo();
        if (config != null) {
            for (String key : List.of("containerName", "CONTAINER_NAME", "container_name")) {
                Object v = config.get(key);
                if (v instanceof String s && !s.isBlank() && s.equals(containerName)) {
                    return true;
                }
            }
        }
        // 3. docker 类默认命名 game-instance-{id}
        if ("docker".equals(inst.getDeployType()) && inst.getId() != null
                && containerName.equals("game-instance-" + inst.getId())) {
            return true;
        }
        // 4. compose 项目名前缀 game{id}_（容器名规范 {project}_{service}_{n}）
        if ("docker-compose".equals(inst.getDeployType()) && inst.getId() != null
                && containerName.startsWith("game" + inst.getId() + "_")) {
            return true;
        }
        return false;
    }

    private ContainerDetailVO parseContainerDetail(String output) {
        ContainerDetailVO detail = new ContainerDetailVO();
        
        try {
            JsonNode root = objectMapper.readTree(output);
            if (root.isArray() && root.size() > 0) {
                JsonNode container = root.get(0);
                
                detail.setContainerId(container.path("Id").asText());
                detail.setContainerIdShort(detail.getContainerId().substring(0, 12));
                detail.setContainerName(extractContainerName(container.path("Name").asText()));
                detail.setImageId(container.path("Image").asText());
                detail.setStatus(container.path("State").path("Status").asText());
                detail.setState(buildStateString(container.path("State")));
                
                // 解析镜像名称
                String image = container.path("Config").path("Image").asText("");
                parseImageInfoForDetail(image, detail);
                
                // 解析环境变量
                JsonNode envArray = container.path("Config").path("Env");
                if (envArray.isArray()) {
                    List<String> envList = new ArrayList<>();
                    envArray.forEach(e -> envList.add(e.asText()));
                    detail.setEnv(envList);
                }
                
                // 解析标签
                JsonNode labels = container.path("Config").path("Labels");
                if (labels.isObject()) {
                    Map<String, String> labelMap = new HashMap<>();
                    labels.fields().forEachRemaining(e -> labelMap.put(e.getKey(), e.getValue().asText()));
                    detail.setLabels(labelMap);
                }
                
                // 解析网络
                JsonNode networks = container.path("NetworkSettings").path("Networks");
                if (networks.isObject()) {
                    List<ContainerDetailVO.NetworkInfo> networkList = new ArrayList<>();
                    networks.fields().forEachRemaining(e -> {
                        ContainerDetailVO.NetworkInfo info = new ContainerDetailVO.NetworkInfo();
                        info.setNetworkName(e.getKey());
                        info.setIpAddress(e.getValue().path("IPAddress").asText());
                        info.setGateway(e.getValue().path("Gateway").asText());
                        networkList.add(info);
                    });
                    detail.setNetworks(networkList);
                }
                
                // 解析挂载卷
                JsonNode mounts = container.path("Mounts");
                if (mounts.isArray()) {
                    List<ContainerDetailVO.VolumeMount> volumeList = new ArrayList<>();
                    mounts.forEach(m -> {
                        ContainerDetailVO.VolumeMount mount = new ContainerDetailVO.VolumeMount();
                        mount.setSource(m.path("Source").asText());
                        mount.setDestination(m.path("Destination").asText());
                        mount.setMode(m.path("Mode").asText("rw"));
                        volumeList.add(mount);
                    });
                    detail.setVolumes(volumeList);
                }
                
                // 解析端口
                JsonNode ports = container.path("NetworkSettings").path("Ports");
                if (ports.isObject()) {
                    List<ContainerListVO.PortMapping> portList = new ArrayList<>();
                    ports.fields().forEachRemaining(e -> {
                        String[] parts = e.getKey().split("/");
                        String portWithProto = parts[0];
                        String proto = parts.length > 1 ? parts[1] : "tcp";
                        String[] portParts = portWithProto.split(":");
                        int containerPort = Integer.parseInt(portParts[portParts.length - 1]);
                        
                        if (e.getValue().isArray() && e.getValue().size() > 0) {
                            e.getValue().forEach(binding -> {
                                ContainerListVO.PortMapping mapping = new ContainerListVO.PortMapping();
                                mapping.setContainerPort(containerPort);
                                mapping.setHostPort(binding.path("HostPort").asInt());
                                mapping.setProtocol(proto);
                                portList.add(mapping);
                            });
                        }
                    });
                    detail.setPorts(portList);
                }
                
                detail.setIsLinked(false);
            }
        } catch (Exception e) {
            log.error("解析容器详情失败: {}", e.getMessage(), e);
        }
        
        return detail;
    }

    private ContainerStatsVO parseContainerStats(String output) {
        ContainerStatsVO stats = new ContainerStatsVO();
        
        try {
            String line = output.trim();
            if (line.startsWith("'") && line.endsWith("'")) {
                line = line.substring(1, line.length() - 1);
            }
            
            JsonNode node = objectMapper.readTree(line);
            
            stats.setContainerId(node.has("Container") ? node.get("Container").asText() : "");
            stats.setContainerName(node.has("Name") ? node.get("Name").asText() : "");
            
            // 解析CPU
            ContainerStatsVO.CpuStats cpu = new ContainerStatsVO.CpuStats();
            String cpuPerc = node.has("CPUPerc") ? node.get("CPUPerc").asText() : "0%";
            cpu.setUsagePercent(parsePercentage(cpuPerc));
            stats.setCpu(cpu);
            
            // 解析内存
            ContainerStatsVO.MemoryStats memory = new ContainerStatsVO.MemoryStats();
            String memPerc = node.has("MemPerc") ? node.get("MemPerc").asText() : "0%";
            memory.setUsagePercent(parsePercentage(memPerc));
            String memUsage = node.has("MemUsage") ? node.get("MemUsage").asText() : "0 / 0";
            parseMemoryUsage(memUsage, memory);
            stats.setMemory(memory);
            
            // 解析网络
            ContainerStatsVO.NetworkStats network = new ContainerStatsVO.NetworkStats();
            String netIO = node.has("NetIO") ? node.get("NetIO").asText() : "0 / 0";
            parseNetworkIO(netIO, network);
            stats.setNetwork(network);
            
            // 解析磁盘IO
            ContainerStatsVO.BlockIOStats blockIO = new ContainerStatsVO.BlockIOStats();
            String blockIOStr = node.has("BlockIO") ? node.get("BlockIO").asText() : "0 / 0";
            parseBlockIO(blockIOStr, blockIO);
            stats.setBlockIO(blockIO);
            
            stats.setTimestamp(LocalDateTime.now());
            
        } catch (Exception e) {
            log.error("解析容器统计失败: {}", e.getMessage(), e);
        }
        
        return stats;
    }

    private String extractContainerName(String names) {
        if (names == null || names.isEmpty()) return "";
        // 移除前导斜杠
        String[] nameArray = names.split(",");
        return nameArray[0].replaceFirst("^/", "");
    }

    private String parseStatus(String state) {
        if (state == null) return "unknown";
        return switch (state.toLowerCase()) {
            case "running" -> "running";
            case "exited", "created" -> "stopped";
            case "paused" -> "paused";
            case "restarting" -> "restarting";
            default -> "unknown";
        };
    }

    private void parseImageInfo(String image, ContainerListVO container) {
        if (image == null || image.isEmpty()) {
            container.setImageName("");
            container.setImageTag("");
            return;
        }
        
        int lastColon = image.lastIndexOf(':');
        if (lastColon > 0 && !image.substring(lastColon).contains("/")) {
            container.setImageName(image.substring(0, lastColon));
            container.setImageTag(image.substring(lastColon + 1));
        } else {
            container.setImageName(image);
            container.setImageTag("latest");
        }
    }

    private void parseImageInfoForDetail(String image, ContainerDetailVO detail) {
        if (image == null || image.isEmpty()) {
            detail.setImageName("");
            return;
        }
        detail.setImageName(image);
    }

    private List<ContainerListVO.PortMapping> parsePorts(String ports) {
        List<ContainerListVO.PortMapping> result = new ArrayList<>();
        if (ports == null || ports.isEmpty()) return result;
        
        // 简单解析端口映射字符串
        Pattern pattern = Pattern.compile("(\\d+\\.\\d+\\.\\d+\\.\\d+:)?(\\d+)->(\\d+)/(tcp|udp)");
        Matcher matcher = pattern.matcher(ports);
        
        while (matcher.find()) {
            ContainerListVO.PortMapping mapping = new ContainerListVO.PortMapping();
            mapping.setHostPort(Integer.parseInt(matcher.group(2)));
            mapping.setContainerPort(Integer.parseInt(matcher.group(3)));
            mapping.setProtocol(matcher.group(4));
            result.add(mapping);
        }
        
        return result;
    }

    private String buildStateString(JsonNode state) {
        String status = state.path("Status").asText("");
        if ("running".equals(status)) {
            return "Up " + state.path("StartedAt").asText("");
        } else if ("exited".equals(status)) {
            return "Exited (" + state.path("ExitCode").asInt() + ")";
        }
        return status;
    }

    private Double parsePercentage(String perc) {
        if (perc == null || perc.isEmpty()) return 0.0;
        try {
            return Double.parseDouble(perc.replace("%", "").trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private void parseMemoryUsage(String usage, ContainerStatsVO.MemoryStats memory) {
        if (usage == null) return;
        String[] parts = usage.split("/");
        if (parts.length == 2) {
            memory.setUsed(parseMemorySize(parts[0].trim()));
            memory.setLimit(parseMemorySize(parts[1].trim()));
        }
    }

    private Long parseMemorySize(String size) {
        if (size == null || size.isEmpty()) return 0L;
        size = size.trim().toUpperCase();
        try {
            if (size.endsWith("GIB") || size.endsWith("GB")) {
                return (long) (Double.parseDouble(size.replaceAll("[^0-9.]", "")) * 1024);
            } else if (size.endsWith("MIB") || size.endsWith("MB")) {
                return (long) Double.parseDouble(size.replaceAll("[^0-9.]", ""));
            } else if (size.endsWith("KIB") || size.endsWith("KB")) {
                return (long) (Double.parseDouble(size.replaceAll("[^0-9.]", "")) / 1024);
            } else if (size.endsWith("B")) {
                return (long) (Double.parseDouble(size.replaceAll("[^0-9.]", "")) / (1024 * 1024));
            }
            return Long.parseLong(size.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void parseNetworkIO(String netIO, ContainerStatsVO.NetworkStats network) {
        if (netIO == null) return;
        String[] parts = netIO.split("/");
        if (parts.length == 2) {
            network.setRxBytes(parseMemorySize(parts[0].trim()));
            network.setTxBytes(parseMemorySize(parts[1].trim()));
        }
    }

    private void parseBlockIO(String blockIO, ContainerStatsVO.BlockIOStats stats) {
        if (blockIO == null) return;
        String[] parts = blockIO.split("/");
        if (parts.length == 2) {
            stats.setReadBytes(parseMemorySize(parts[0].trim()));
            stats.setWriteBytes(parseMemorySize(parts[1].trim()));
        }
    }
}
