package com.gameplatform.service.docker.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.common.result.ResultCode;
import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.deploy.HostCredentials;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.service.docker.DockerImageService;
import com.gameplatform.util.SshUtil;
import com.gameplatform.vo.docker.ImageListVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Docker镜像管理服务实现
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DockerImageServiceImpl implements DockerImageService {

    private final HostMapper hostMapper;
    private final SshUtil sshUtil;
    private final DeploymentAccess deployAccess;
    private final ObjectMapper objectMapper;

    @Override
    public List<ImageListVO> listImages(Long hostId, String keyword, Boolean dangling) {
        Host host = getHost(hostId);
        
        StringBuilder command = new StringBuilder("docker images --format '{{json .}}'");
        
        if (Boolean.TRUE.equals(dangling)) {
            command.insert(7, " -f dangling=true");
        }
        
        SshUtil.CommandResult result = executeCommand(host, command.toString(), 30000);
        
        if (!result.isSuccess()) {
            throw new BusinessException(ResultCode.HOST_CONNECTION_FAILED, "获取镜像列表失败: " + result.getError());
        }
        
        List<ImageListVO> images = parseImageList(result.getOutput(), host);
        
        // 关键词过滤
        if (keyword != null && !keyword.isEmpty()) {
            String lowerKeyword = keyword.toLowerCase();
            images = images.stream()
                    .filter(img -> {
                        if (img.getRepoTags() != null) {
                            return img.getRepoTags().stream()
                                    .anyMatch(tag -> tag.toLowerCase().contains(lowerKeyword));
                        }
                        return false;
                    })
                    .toList();
        }
        
        return images;
    }

    @Override
    public ImageDeleteResult deleteImage(Long hostId, String imageId, Boolean force) {
        Host host = getHost(hostId);
        
        StringBuilder command = new StringBuilder("docker rmi");
        
        if (Boolean.TRUE.equals(force)) {
            command.append(" -f");
        }
        
        command.append(" ").append(imageId);
        
        SshUtil.CommandResult result = executeCommand(host, command.toString(), 60000);
        
        if (result.isSuccess()) {
            // 解析被删除的镜像
            List<String> deletedImages = parseDeletedImages(result.getOutput());
            return new ImageDeleteResult(true, imageId, "镜像已删除", deletedImages);
        } else {
            String error = result.getError();
            if (error != null && error.contains("image is being used")) {
                return new ImageDeleteResult(false, imageId, "镜像正在被容器使用，无法删除", Collections.emptyList());
            }
            return new ImageDeleteResult(false, imageId, "镜像删除失败: " + error, Collections.emptyList());
        }
    }

    @Override
    public ImagePruneResult pruneImages(Long hostId) {
        Host host = getHost(hostId);
        
        String command = "docker image prune -f";
        SshUtil.CommandResult result = executeCommand(host, command, 60000);
        
        if (result.isSuccess()) {
            // 解析清理结果
            PruneParseResult parseResult = parsePruneOutput(result.getOutput());
            return new ImagePruneResult(parseResult.deletedImages(), parseResult.spaceReclaimed());
        } else {
            return new ImagePruneResult(Collections.emptyList(), 0L);
        }
    }

    // ========== 私有方法 ==========

    private Host getHost(Long hostId) {
        Host host = hostMapper.selectById(hostId);
        if (host == null) {
            throw new BusinessException(ResultCode.HOST_NOT_FOUND);
        }
        return host;
    }

    private SshUtil.CommandResult executeCommand(Host host, String command, long timeoutMs) {
        HostCredentials conn = deployAccess.credentials(host);
        return sshUtil.executeCommand(
                conn.host(), conn.port(), conn.username(), conn.privateKey(), conn.password(),
                command,
                timeoutMs
        );
    }

    private List<ImageListVO> parseImageList(String output, Host host) {
        List<ImageListVO> images = new ArrayList<>();
        String[] lines = output.split("\n");
        
        // 用于统计每个镜像被使用的容器数量
        Map<String, Integer> usageCount = getImageUsageCount(host);
        
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            
            try {
                // 移除可能的引号
                if (line.startsWith("'") && line.endsWith("'")) {
                    line = line.substring(1, line.length() - 1);
                }
                
                JsonNode node = objectMapper.readTree(line);
                ImageListVO image = new ImageListVO();
                
                String imageId = node.has("ID") ? node.get("ID").asText() : "";
                image.setImageId(imageId);
                image.setImageIdFull("sha256:" + imageId);
                
                // 解析标签
                String repoTags = node.has("Repository") ? node.get("Repository").asText() : "";
                String tag = node.has("Tag") ? node.get("Tag").asText() : "";
                
                List<String> tags = new ArrayList<>();
                if (!repoTags.isEmpty() && !tag.isEmpty()) {
                    tags.add(repoTags + ":" + tag);
                } else if (repoTags.equals("<none>")) {
                    tags.add("<none>:<none>");
                    image.setIsDangling(true);
                } else {
                    image.setIsDangling(false);
                }
                image.setRepoTags(tags);
                
                // 解析大小
                String size = node.has("Size") ? node.get("Size").asText() : "0";
                image.setSize(parseSizeToMB(size));
                
                // 解析创建时间
                String createdAt = node.has("CreatedAt") ? node.get("CreatedAt").asText() : "";
                image.setCreatedAt(parseCreatedAt(createdAt));
                
                // 设置使用数量
                image.setUsedByContainers(usageCount.getOrDefault(imageId, 0));
                
                // 解析标签
                image.setLabels(new HashMap<>());
                
                images.add(image);
            } catch (Exception e) {
                log.warn("解析镜像信息失败: {}, line: {}", e.getMessage(), line);
            }
        }
        
        return images;
    }

    private Map<String, Integer> getImageUsageCount(Host host) {
        Map<String, Integer> countMap = new HashMap<>();
        
        try {
            String command = "docker ps -a --format '{{.Image}} {{.ImageID}}'";
            SshUtil.CommandResult result = executeCommand(host, command, 10000);
            
            if (result.isSuccess()) {
                String[] lines = result.getOutput().split("\n");
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    
                    // 提取镜像ID（短ID）
                    String[] parts = line.split(" ");
                    if (parts.length >= 2) {
                        String imageId = parts[parts.length - 1];
                        countMap.merge(imageId, 1, Integer::sum);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("获取镜像使用统计失败: {}", e.getMessage());
        }
        
        return countMap;
    }

    private Long parseSizeToMB(String size) {
        if (size == null || size.isEmpty()) return 0L;
        
        size = size.trim().toUpperCase();
        try {
            // 格式: 123.45MB, 1.23GB, etc.
            Pattern pattern = Pattern.compile("([\\d.]+)\\s*(B|KB|MB|GB|TB)", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(size);
            
            if (matcher.find()) {
                double value = Double.parseDouble(matcher.group(1));
                String unit = matcher.group(2).toUpperCase();
                
                return switch (unit) {
                    case "B" -> (long) (value / (1024 * 1024));
                    case "KB" -> (long) (value / 1024);
                    case "MB" -> (long) value;
                    case "GB" -> (long) (value * 1024);
                    case "TB" -> (long) (value * 1024 * 1024);
                    default -> (long) value;
                };
            }
            
            // 尝试直接解析数字（假设是字节）
            return (long) (Double.parseDouble(size.replaceAll("[^0-9.]", "")) / (1024 * 1024));
        } catch (Exception e) {
            return 0L;
        }
    }

    private LocalDateTime parseCreatedAt(String createdAt) {
        if (createdAt == null || createdAt.isEmpty()) return null;
        
        try {
            // Docker 返回的格式可能是: "2024-03-24 10:30:00 +0000 UTC"
            // 或者: "2024-03-24T10:30:00Z"
            
            // 简单处理，尝试解析时间戳
            if (createdAt.contains("ago")) {
                // 相对时间，如 "2 hours ago"
                return LocalDateTime.now().minusHours(parseRelativeTime(createdAt));
            }
            
            // ISO格式
            if (createdAt.contains("T")) {
                return LocalDateTime.parse(createdAt.replace("Z", "").replace("+00:00", ""));
            }
            
            // 其他格式，返回当前时间
            return LocalDateTime.now();
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    private long parseRelativeTime(String relative) {
        // 简单解析相对时间
        Pattern pattern = Pattern.compile("(\\d+)\\s*(second|minute|hour|day|week|month|year)s?\\s+ago");
        Matcher matcher = pattern.matcher(relative.toLowerCase());
        
        if (matcher.find()) {
            long value = Long.parseLong(matcher.group(1));
            String unit = matcher.group(2);
            
            return switch (unit) {
                case "second" -> value;
                case "minute" -> value * 60;
                case "hour" -> value * 3600;
                case "day" -> value * 86400;
                case "week" -> value * 604800;
                case "month" -> value * 2592000;
                case "year" -> value * 31536000;
                default -> 0;
            };
        }
        
        return 0;
    }

    private List<String> parseDeletedImages(String output) {
        List<String> deleted = new ArrayList<>();
        if (output == null) return deleted;
        
        // 解析输出中的镜像ID
        Pattern pattern = Pattern.compile("[a-f0-9]{12,64}");
        Matcher matcher = pattern.matcher(output);
        
        while (matcher.find()) {
            deleted.add("sha256:" + matcher.group());
        }
        
        return deleted;
    }

    private PruneParseResult parsePruneOutput(String output) {
        List<String> deletedImages = new ArrayList<>();
        long spaceReclaimed = 0;
        
        if (output == null) {
            return new PruneParseResult(deletedImages, spaceReclaimed);
        }
        
        // 解析删除的镜像
        Pattern imagePattern = Pattern.compile("deleted: sha256:([a-f0-9]+)");
        Matcher imageMatcher = imagePattern.matcher(output.toLowerCase());
        while (imageMatcher.find()) {
            deletedImages.add("sha256:" + imageMatcher.group(1));
        }
        
        // 解析释放的空间
        Pattern spacePattern = Pattern.compile("total reclaimed space:\\s*([\\d.]+)\\s*(b|kb|mb|gb)", Pattern.CASE_INSENSITIVE);
        Matcher spaceMatcher = spacePattern.matcher(output);
        if (spaceMatcher.find()) {
            double value = Double.parseDouble(spaceMatcher.group(1));
            String unit = spaceMatcher.group(2).toUpperCase();
            
            spaceReclaimed = switch (unit) {
                case "B" -> (long) (value / (1024 * 1024));
                case "KB" -> (long) (value / 1024);
                case "MB" -> (long) value;
                case "GB" -> (long) (value * 1024);
                default -> (long) value;
            };
        }
        
        return new PruneParseResult(deletedImages, spaceReclaimed);
    }

    private record PruneParseResult(List<String> deletedImages, Long spaceReclaimed) {}
}
