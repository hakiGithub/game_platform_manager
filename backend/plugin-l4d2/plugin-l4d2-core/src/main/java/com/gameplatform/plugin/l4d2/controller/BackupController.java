package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.l4d2.dto.BackupCreateDTO;
import com.gameplatform.plugin.l4d2.dto.BackupRenameDTO;
import com.gameplatform.plugin.l4d2.dto.BackupRestoreDTO;
import com.gameplatform.plugin.l4d2.extension.PluginBackupResource;
import com.gameplatform.plugin.l4d2.extension.PluginBackupSpec;
import com.gameplatform.plugin.l4d2.service.BackupService;
import com.gameplatform.plugin.l4d2.vo.BackupVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * L4D2 备份还原控制器：备份创建/还原/删除/重命名/列表/详情。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Tag(name = "L4D2 备份还原", description = "备份创建/还原/删除/重命名")
@RestController
@RequestMapping("/api/plugin/l4d2/backups")
@RequiredArgsConstructor
@Validated
public class BackupController {

    private final BackupService backupService;

    /**
     * 备份列表。
     */
    @Operation(summary = "备份列表", description = "列出指定实例的所有备份")
    @GetMapping("/list")
    public Result<List<BackupVO>> list(@Parameter(description = "实例ID") @RequestParam Long instanceId) {
        List<PluginBackupResource> resources = backupService.list(instanceId);
        return Result.success(resources.stream().map(this::toVO).collect(Collectors.toList()));
    }

    /**
     * 创建备份。
     */
    @Operation(summary = "创建备份", description = "扫描实例文件并创建备份")
    @PostMapping("/create")
    public Result<BackupVO> create(@Valid @RequestBody BackupCreateDTO dto) {
        PluginBackupResource r = backupService.create(dto.getInstanceId(), dto.getName(), dto.getDescription());
        return Result.success(toVO(r));
    }

    /**
     * 还原备份。
     */
    @Operation(summary = "还原备份", description = "将备份内容还原到实例文件")
    @PostMapping("/restore")
    public Result<Void> restore(@Valid @RequestBody BackupRestoreDTO dto) {
        backupService.restore(dto.getInstanceId(), dto.getBackupId());
        return Result.success(null);
    }

    /**
     * 重命名备份。
     */
    @Operation(summary = "重命名备份", description = "更新备份名称")
    @PostMapping("/rename")
    public Result<Void> rename(@Valid @RequestBody BackupRenameDTO dto) {
        backupService.rename(dto.getBackupId(), dto.getNewName());
        return Result.success(null);
    }

    /**
     * 删除备份。
     */
    @Operation(summary = "删除备份", description = "按备份ID删除")
    @DeleteMapping("/{backupId}")
    public Result<Void> delete(@Parameter(description = "备份ID") @PathVariable String backupId) {
        backupService.delete(backupId);
        return Result.success(null);
    }

    /**
     * 备份详情。
     */
    @Operation(summary = "备份详情", description = "按备份ID获取详情")
    @GetMapping("/{backupId}")
    public Result<BackupVO> detail(@Parameter(description = "备份ID") @PathVariable String backupId) {
        return Result.success(toVO(backupService.getById(backupId)));
    }

    private BackupVO toVO(PluginBackupResource r) {
        if (r == null) {
            return null;
        }
        BackupVO vo = new BackupVO();
        vo.setId(r.getId());
        PluginBackupSpec spec = r.getSpec();
        if (spec != null) {
            vo.setName(spec.getName());
            vo.setDescription(spec.getDescription());
            vo.setCreatedAt(spec.getCreatedAt());
            vo.setContent(spec.getContent());
            vo.setOwner(spec.getOwner());
        }
        vo.setStatus(r.getStatus());
        return vo;
    }
}
