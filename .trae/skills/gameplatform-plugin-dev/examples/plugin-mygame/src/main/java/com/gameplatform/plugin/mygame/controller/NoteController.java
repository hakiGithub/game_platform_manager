package com.gameplatform.plugin.mygame.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.extension.ListOptions;
import com.gameplatform.plugin.extension.exception.DuplicateExtensionException;
import com.gameplatform.plugin.extension.exception.ExtensionNotFoundException;
import com.gameplatform.plugin.mygame.extension.NoteResource;
import com.gameplatform.plugin.mygame.extension.NoteSpec;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 笔记管理控制器（demo）。
 * <p>
 * 演示要点：
 * <ul>
 *   <li>路径必须以 {@code /api/plugin/{gameCode}/} 开头（这里 = /api/plugin/mygame/）</li>
 *   <li>注入 {@link ExtensionClient} 做 CRUD（绑定 pluginId，自动 group_name + kind 过滤）</li>
 *   <li>create 时 name 必须同类型唯一，否则抛 {@link DuplicateExtensionException}</li>
 *   <li>update 需带读到的 version（乐观锁）；并发改写抛 OptimisticLockException</li>
 *   <li>ListOptions.specFilter 使用 JSONPath 过滤 spec 内字段</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/mygame/notes")
@RequiredArgsConstructor
@Validated
public class NoteController {

    private final ExtensionClient extensionClient;

    /**
     * 列出某实例下的全部笔记
     */
    @GetMapping
    public Result<List<NoteVO>> list(@RequestParam Long instanceId) {
        ListOptions opts = ListOptions.builder()
                .specFilter("$.instanceId", "=", instanceId)
                .orderBy("creation_timestamp")
                .build();
        List<NoteVO> result = extensionClient.list(NoteResource.class, opts).stream()
                .map(this::toVO)
                .collect(Collectors.toList());
        return Result.success(result);
    }

    /**
     * 按 ID 获取
     */
    @GetMapping("/{id}")
    public Result<NoteVO> getById(@PathVariable String id) {
        return extensionClient.getById(NoteResource.class, id)
                .map(r -> Result.success(toVO(r)))
                .orElseGet(() -> Result.fail("笔记不存在"));
    }

    /**
     * 创建笔记
     */
    @PostMapping
    public Result<NoteVO> create(@Valid @RequestBody CreateNoteDTO dto) {
        NoteResource resource = new NoteResource();
        // name 同类型内唯一；用 instanceId + uuid 保证不冲突
        resource.setName(dto.getInstanceId() + "-" + UUID.randomUUID());

        NoteSpec spec = new NoteSpec();
        spec.setInstanceId(dto.getInstanceId());
        spec.setTitle(dto.getTitle());
        spec.setContent(dto.getContent());
        spec.setPinned(Boolean.FALSE);
        resource.setSpec(spec);

        try {
            extensionClient.create(resource);
        } catch (DuplicateExtensionException e) {
            return Result.fail("笔记已存在");
        }
        return Result.success(toVO(resource));
    }

    /**
     * 更新笔记内容（乐观锁：需带 version）
     */
    @PutMapping("/{id}")
    public Result<NoteVO> update(@PathVariable String id,
                                 @Valid @RequestBody UpdateNoteDTO dto) {
        NoteResource resource = extensionClient.getById(NoteResource.class, id)
                .orElse(null);
        if (resource == null) {
            return Result.fail("笔记不存在");
        }

        // 必须带回读到的 version，否则触发 OptimisticLockException
        resource.setVersion(dto.getVersion());

        NoteSpec spec = resource.getSpec();
        if (spec == null) {
            spec = new NoteSpec();
            resource.setSpec(spec);
        }
        if (dto.getTitle() != null) {
            spec.setTitle(dto.getTitle());
        }
        if (dto.getContent() != null) {
            spec.setContent(dto.getContent());
        }
        if (dto.getPinned() != null) {
            spec.setPinned(dto.getPinned());
        }

        try {
            extensionClient.update(resource);
        } catch (ExtensionNotFoundException e) {
            return Result.fail("笔记不存在");
        }
        return Result.success(toVO(resource));
    }

    /**
     * 按 ID 删除
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        try {
            extensionClient.deleteById(NoteResource.class, id);
        } catch (ExtensionNotFoundException e) {
            return Result.fail("笔记不存在");
        }
        return Result.success();
    }

    // ==================== 私有方法 ====================

    private NoteVO toVO(NoteResource resource) {
        NoteVO vo = new NoteVO();
        vo.setId(resource.getId());
        vo.setName(resource.getName());
        vo.setVersion(resource.getVersion());
        if (resource.getMetadata() != null && resource.getMetadata().getCreationTimestamp() != null) {
            vo.setCreateTime(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(resource.getMetadata().getCreationTimestamp()),
                    ZoneId.systemDefault()));
        }
        NoteSpec spec = resource.getSpec();
        if (spec != null) {
            vo.setInstanceId(spec.getInstanceId());
            vo.setTitle(spec.getTitle());
            vo.setContent(spec.getContent());
            vo.setPinned(spec.getPinned());
        }
        return vo;
    }

    // ==================== DTO / VO ====================

    @Data
    public static class CreateNoteDTO {
        @NotBlank
        private Long instanceId;
        @NotBlank
        private String title;
        private String content;
    }

    @Data
    public static class UpdateNoteDTO {
        private Long version;  // 乐观锁
        private String title;
        private String content;
        private Boolean pinned;
    }

    @Data
    public static class NoteVO {
        private String id;
        private String name;
        private Long version;
        private LocalDateTime createTime;
        private Long instanceId;
        private String title;
        private String content;
        private Boolean pinned;
    }
}
