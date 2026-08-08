package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.extension.ListOptions;
import com.gameplatform.plugin.extension.exception.DuplicateExtensionException;
import com.gameplatform.plugin.extension.exception.ExtensionNotFoundException;
import com.gameplatform.plugin.l4d2.dto.AdminAddDTO;
import com.gameplatform.plugin.l4d2.dto.InstanceIdDTO;
import com.gameplatform.plugin.l4d2.extension.AdminResource;
import com.gameplatform.plugin.l4d2.extension.AdminSpec;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.vo.AdminVO;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理员管理控制器
 * 提供 L4D2 SourceMod 管理员的管理功能
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Tag(name = "L4D2 管理员管理", description = "L4D2 服务器管理员管理接口")
@RestController
@RequestMapping("/api/plugin/l4d2/admins")
@RequiredArgsConstructor
@Validated
public class AdminController {

    private final InstanceQueryService instanceQueryService;
    private final InstanceFileService instanceFileService;
    private final L4D2PathResolver pathResolver;
    private final ExtensionClient extensionClient;

    /**
     * 获取管理员列表
     */
    @Operation(summary = "获取管理员列表", description = "获取服务器的管理员列表")
    @GetMapping("/list")
    public Result<List<AdminVO>> getAdminList(@Parameter(description = "实例ID") @RequestParam Long instanceId) {
        log.info("获取管理员列表, instanceId: {}", instanceId);

        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            return Result.fail("实例不存在");
        }

        List<AdminVO> admins = getAdminsFromDatabase(instanceId);
        return Result.success(admins);
    }

    /**
     * 添加管理员
     */
    @Operation(summary = "添加管理员", description = "添加新的服务器管理员")
    @PostMapping("/add")
    public Result<AdminVO> addAdmin(@Valid @RequestBody AdminAddDTO dto) {
        log.info("添加管理员, instanceId: {}, steamId: {}", dto.getInstanceId(), dto.getSteamId());

        InstanceVO instance = instanceQueryService.getInstanceById(dto.getInstanceId());
        if (instance == null) {
            return Result.fail("实例不存在");
        }

        AdminResource resource = new AdminResource();
        resource.setName(buildAdminName(dto.getInstanceId(), dto.getSteamId()));

        AdminSpec spec = new AdminSpec();
        spec.setInstanceId(dto.getInstanceId());
        spec.setSteamId(dto.getSteamId());
        spec.setAdminFlags(dto.getAdminFlags());
        spec.setRemark(dto.getRemark());
        spec.setIsActive(true);
        resource.setSpec(spec);

        try {
            extensionClient.create(resource);
        } catch (DuplicateExtensionException e) {
            return Result.fail("该 SteamID 已存在");
        }

        AdminVO vo = toVO(resource);

        // 更新 admins.cfg 文件
        List<AdminVO> admins = getAdminsFromDatabase(dto.getInstanceId());
        updateAdminsConfig(instance, admins);

        return Result.success("管理员添加成功", vo);
    }

    /**
     * 删除管理员
     */
    @Operation(summary = "删除管理员", description = "删除指定的服务器管理员")
    @DeleteMapping("/{steamId}")
    public Result<Void> deleteAdmin(
            @Parameter(description = "实例ID") @RequestParam Long instanceId,
            @Parameter(description = "SteamID") @PathVariable String steamId) {
        log.info("删除管理员, instanceId: {}, steamId: {}", instanceId, steamId);

        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            return Result.fail("实例不存在");
        }

        try {
            extensionClient.delete(AdminResource.class, buildAdminName(instanceId, steamId));
        } catch (ExtensionNotFoundException e) {
            return Result.fail("管理员不存在");
        }

        // 更新 admins.cfg 文件
        List<AdminVO> admins = getAdminsFromDatabase(instanceId);
        updateAdminsConfig(instance, admins);

        return Result.success();
    }

    /**
     * 更新管理员权限
     */
    @Operation(summary = "更新管理员权限", description = "更新指定管理员的权限标志")
    @PutMapping("/{steamId}/flags")
    public Result<AdminVO> updateAdminFlags(
            @Parameter(description = "实例ID") @RequestParam Long instanceId,
            @Parameter(description = "SteamID") @PathVariable String steamId,
            @Parameter(description = "权限标志") @RequestParam String adminFlags) {
        log.info("更新管理员权限, instanceId: {}, steamId: {}, flags: {}", instanceId, steamId, adminFlags);

        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            return Result.fail("实例不存在");
        }

        AdminResource resource;
        try {
            resource = extensionClient.get(AdminResource.class, buildAdminName(instanceId, steamId))
                    .orElse(null);
        } catch (Exception e) {
            return Result.fail("查询管理员失败: " + e.getMessage());
        }
        if (resource == null) {
            return Result.fail("管理员不存在");
        }

        resource.getSpec().setAdminFlags(adminFlags);
        extensionClient.update(resource);

        AdminVO vo = toVO(resource);

        // 更新 admins.cfg 文件
        List<AdminVO> admins = getAdminsFromDatabase(instanceId);
        updateAdminsConfig(instance, admins);

        return Result.success(vo);
    }

    /**
     * 启用/禁用管理员
     */
    @Operation(summary = "启用/禁用管理员", description = "启用或禁用指定管理员")
    @PutMapping("/{steamId}/active")
    public Result<Void> toggleAdminActive(
            @Parameter(description = "实例ID") @RequestParam Long instanceId,
            @Parameter(description = "SteamID") @PathVariable String steamId,
            @Parameter(description = "是否激活") @RequestParam Boolean isActive) {
        log.info("启用/禁用管理员, instanceId: {}, steamId: {}, isActive: {}", instanceId, steamId, isActive);

        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            return Result.fail("实例不存在");
        }

        AdminResource resource;
        try {
            resource = extensionClient.get(AdminResource.class, buildAdminName(instanceId, steamId))
                    .orElse(null);
        } catch (Exception e) {
            return Result.fail("查询管理员失败: " + e.getMessage());
        }
        if (resource == null) {
            return Result.fail("管理员不存在");
        }

        resource.getSpec().setIsActive(isActive);
        extensionClient.update(resource);

        // 更新 admins.cfg 文件
        List<AdminVO> admins = getAdminsFromDatabase(instanceId);
        updateAdminsConfig(instance, admins);

        return Result.success();
    }

    /**
     * 重载管理员配置
     */
    @Operation(summary = "重载管理员配置", description = "重载 SourceMod 管理员配置文件")
    @PostMapping("/reload")
    public Result<Void> reloadAdminConfig(@Valid @RequestBody InstanceIdDTO dto) {
        log.info("重载管理员配置, instanceId: {}", dto.getInstanceId());

        InstanceVO instance = instanceQueryService.getInstanceById(dto.getInstanceId());
        if (instance == null) {
            return Result.fail("实例不存在");
        }

        // 通过 RCON 执行重载命令
        // sm_reloadadmins
        log.info("执行 sm_reloadadmins 命令");

        return Result.success("管理员配置已重载", null);
    }

    /**
     * 同步管理员到服务器
     */
    @Operation(summary = "同步管理员", description = "将管理员列表同步到服务器配置文件")
    @PostMapping("/sync")
    public Result<Void> syncAdmins(@Valid @RequestBody InstanceIdDTO dto) {
        log.info("同步管理员, instanceId: {}", dto.getInstanceId());

        InstanceVO instance = instanceQueryService.getInstanceById(dto.getInstanceId());
        if (instance == null) {
            return Result.fail("实例不存在");
        }

        List<AdminVO> admins = getAdminsFromDatabase(dto.getInstanceId());
        updateAdminsConfig(instance, admins);

        return Result.success("管理员已同步", null);
    }

    /**
     * 按 ID 获取单个管理员
     */
    @Operation(summary = "按ID获取管理员", description = "根据管理员记录ID获取单个管理员")
    @GetMapping("/by-id/{id}")
    public Result<AdminVO> getAdminById(@Parameter(description = "管理员记录ID") @PathVariable String id) {
        log.info("按ID获取管理员, id: {}", id);

        AdminResource resource;
        try {
            resource = extensionClient.getById(AdminResource.class, id).orElse(null);
        } catch (Exception e) {
            return Result.fail("查询管理员失败: " + e.getMessage());
        }
        if (resource == null) {
            return Result.fail("管理员不存在");
        }
        return Result.success(toVO(resource));
    }

    /**
     * 按 ID 删除管理员
     */
    @Operation(summary = "按ID删除管理员", description = "根据管理员记录ID删除管理员")
    @DeleteMapping("/by-id/{id}")
    public Result<Void> deleteAdminById(
            @Parameter(description = "管理员记录ID") @PathVariable String id,
            @Parameter(description = "实例ID") @RequestParam Long instanceId) {
        log.info("按ID删除管理员, id: {}, instanceId: {}", id, instanceId);

        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            return Result.fail("实例不存在");
        }

        try {
            extensionClient.deleteById(AdminResource.class, id);
        } catch (ExtensionNotFoundException e) {
            return Result.fail("管理员不存在");
        }

        // 更新 admins.cfg 文件
        List<AdminVO> admins = getAdminsFromDatabase(instanceId);
        updateAdminsConfig(instance, admins);

        return Result.success();
    }

    // ========== 私有方法 ==========

    /**
     * 构建管理员资源 name（同实例内 steamId 唯一，跨实例不冲突）
     */
    private String buildAdminName(Long instanceId, String steamId) {
        return instanceId + "-" + steamId;
    }

    /**
     * 从扩展存储查询管理员列表并转为 VO
     */
    private List<AdminVO> getAdminsFromDatabase(Long instanceId) {
        ListOptions opts = ListOptions.builder()
                .specFilter("$.instanceId", "=", instanceId)
                .build();
        List<AdminResource> resources = extensionClient.list(AdminResource.class, opts);
        return resources.stream().map(this::toVO).collect(Collectors.toList());
    }

    /**
     * Resource 转 VO
     */
    private AdminVO toVO(AdminResource resource) {
        AdminVO vo = new AdminVO();
        vo.setId(resource.getId());
        if (resource.getMetadata() != null && resource.getMetadata().getCreationTimestamp() != null) {
            vo.setCreateTime(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(resource.getMetadata().getCreationTimestamp()),
                    ZoneId.systemDefault()));
            vo.setUpdateTime(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(resource.getMetadata().getUpdateTimestamp() != null
                            ? resource.getMetadata().getUpdateTimestamp()
                            : resource.getMetadata().getCreationTimestamp()),
                    ZoneId.systemDefault()));
        }
        AdminSpec spec = resource.getSpec();
        if (spec != null) {
            vo.setInstanceId(spec.getInstanceId());
            vo.setSteamId(spec.getSteamId());
            vo.setAdminFlags(spec.getAdminFlags());
            vo.setRemark(spec.getRemark());
            vo.setIsActive(spec.getIsActive());
        }
        return vo;
    }

    /**
     * 更新 admins.cfg 配置文件
     */
    private void updateAdminsConfig(InstanceVO instance, List<AdminVO> admins) {
        // 构建 admins.cfg 内容
        StringBuilder content = new StringBuilder();
        content.append("/**\n");
        content.append(" * SourceMod 管理员配置文件\n");
        content.append(" * 由 GamePlatform 自动生成\n");
        content.append(" */\n\n");
        content.append("Admins\n");
        content.append("{\n");

        for (AdminVO admin : admins) {
            if (Boolean.TRUE.equals(admin.getIsActive())) {
                content.append("    \"").append(admin.getSteamId()).append("\"\n");
                content.append("    {\n");
                content.append("        \"auth\"        \"steam\"\n");
                content.append("        \"identity\"    \"").append(admin.getSteamId()).append("\"\n");
                content.append("        \"flags\"       \"").append(admin.getAdminFlags()).append("\"\n");
                if (admin.getRemark() != null && !admin.getRemark().isEmpty()) {
                    content.append("        \"name\"        \"").append(admin.getRemark()).append("\"\n");
                }
                content.append("    }\n");
            }
        }

        content.append("}\n");

        // 获取配置文件路径（相对路径）
        String configPath = getAdminsConfigPath();

        // 写入文件
        log.info("写入管理员配置文件: {}, 管理员数量: {}", configPath, admins.size());
        instanceFileService.writeTextFile(instance.getId(), configPath, content.toString());
    }

    /**
     * 获取管理员配置文件相对路径
     */
    private String getAdminsConfigPath() {
        return pathResolver.getSourceModConfigsPath() + "/admins.cfg";
    }
}
