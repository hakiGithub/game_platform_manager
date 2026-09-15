package com.gameplatform.controller;

import com.gameplatform.clouddrive.CloudAccountService;
import com.gameplatform.clouddrive.extension.CloudAccountResource;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.common.result.Result;
import com.haki.clouddrive.core.model.QuotaInfo;
import com.haki.clouddrive.core.provider.ProviderDescriptor;
import com.haki.clouddrive.sdk.CloudDriveClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 云盘账号管理控制器（ADR-0024）。
 * <p>
 * 只管账号（CRUD / 探活 / 配额）；转存、列目录、直链、下载不出 REST，
 * 归插件经 CloudDriveService 编程接口调用。凭证任何接口不回显明文。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Tag(name = "云盘账号", description = "云盘账号管理接口（ADR-0024）")
@Slf4j
@RestController
@RequestMapping("/cloud")
@RequiredArgsConstructor
@Validated
public class CloudAccountController {

    private final CloudAccountService accountService;
    private final CloudDriveClient cloudDriveClient;

    @Operation(summary = "账号列表（不回显凭证）")
    @GetMapping("/accounts")
    public Result<List<Map<String, Object>>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (CloudAccountResource res : accountService.listAll()) {
            result.add(toVo(res));
        }
        return Result.success(result);
    }

    @Operation(summary = "新增账号（添加即探活，凭证无效报错）")
    @PostMapping("/accounts")
    public Result<Map<String, Object>> create(@Valid @RequestBody CreateAccountDTO dto) {
        CloudAccountResource res = accountService.create(
                dto.getName().trim(), dto.getProviderType().trim(), dto.getSettingsJson(),
                dto.getDisplayName(), dto.getRemark());
        return Result.success(toVo(res));
    }

    @Operation(summary = "重贴凭证（凭证过期后的修复路径）")
    @PutMapping("/accounts/{name}/credential")
    public Result<Map<String, Object>> replaceCredential(@PathVariable String name,
                                                         @Valid @RequestBody CredentialDTO dto) {
        return Result.success(toVo(accountService.replaceCredential(name, dto.getSettingsJson())));
    }

    @Operation(summary = "更新展示信息（备注等，不动凭证）")
    @PutMapping("/accounts/{name}")
    public Result<Map<String, Object>> update(@PathVariable String name,
                                              @RequestBody UpdateAccountDTO dto) {
        CloudAccountResource res = accountService.get(name)
                .orElseThrow(() -> new BusinessException("云盘账号不存在: " + name));
        if (dto.getDisplayName() != null) {
            res.getSpec().setDisplayName(dto.getDisplayName().isBlank() ? res.getName() : dto.getDisplayName());
        }
        if (dto.getRemark() != null) {
            res.getSpec().setRemark(dto.getRemark());
        }
        return Result.success(toVo(res));
    }

    @Operation(summary = "删除账号（连同挂载）")
    @DeleteMapping("/accounts/{name}")
    public Result<Void> delete(@PathVariable String name) {
        accountService.delete(name);
        return Result.success(null);
    }

    @Operation(summary = "探活账号并回写状态")
    @PostMapping("/accounts/{name}/verify")
    public Result<Map<String, Object>> verify(@PathVariable String name) {
        return Result.success(toVo(accountService.verify(name)));
    }

    @Operation(summary = "查询账号配额")
    @GetMapping("/accounts/{name}/quota")
    public Result<QuotaInfo> quota(@PathVariable String name) {
        return Result.success(accountService.quota(name));
    }

    @Operation(summary = "可用 Provider 及凭证表单元数据（动态表单用）")
    @GetMapping("/providers")
    public Result<List<Map<String, Object>>> providers() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String type : cloudDriveClient.providerTypes()) {
            Map<String, Object> item = new HashMap<>();
            item.put("type", type);
            cloudDriveClient.describeProvider(type).ifPresent(desc -> {
                item.put("displayName", desc.displayName());
                if (desc.settingsSchema() != null) {
                    item.put("fields", desc.settingsSchema().fields());
                }
            });
            if (!item.containsKey("fields")) {
                item.put("fields", List.of());
            }
            result.add(item);
        }
        return Result.success(result);
    }

    private Map<String, Object> toVo(CloudAccountResource res) {
        Map<String, Object> vo = new HashMap<>();
        vo.put("name", res.getName());
        vo.put("providerType", res.getSpec().getProviderType());
        vo.put("displayName", res.getSpec().getDisplayName());
        vo.put("credentialHint", res.getSpec().getCredentialHint());
        vo.put("remark", res.getSpec().getRemark());
        vo.put("status", res.getStatus());
        vo.put("mountPath", accountService.mountPath(res));
        vo.put("createTime", res.getMetadata() == null ? null : res.getMetadata().getCreationTimestamp());
        return vo;
    }

    @Data
    public static class CreateAccountDTO {
        @NotBlank(message = "账号标识不能为空")
        private String name;
        @NotBlank(message = "Provider 类型不能为空")
        private String providerType;
        @NotBlank(message = "凭证不能为空")
        private String settingsJson;
        private String displayName;
        private String remark;
    }

    @Data
    public static class CredentialDTO {
        @NotBlank(message = "凭证不能为空")
        private String settingsJson;
    }

    @Data
    public static class UpdateAccountDTO {
        private String displayName;
        private String remark;
    }
}
