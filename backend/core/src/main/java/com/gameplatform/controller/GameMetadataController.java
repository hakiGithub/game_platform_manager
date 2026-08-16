package com.gameplatform.controller;

import com.gameplatform.common.result.PageResult;
import com.gameplatform.common.result.Result;
import com.gameplatform.dto.GameCreateDTO;
import com.gameplatform.dto.GameUpdateDTO;
import com.gameplatform.dto.PageQueryDTO;
import com.gameplatform.service.GameMetadataScanner;
import com.gameplatform.service.GameService;
import com.gameplatform.vo.DeployConfigVO;
import com.gameplatform.vo.GameVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 游戏元数据控制器
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Tag(name = "游戏管理", description = "游戏元数据相关接口")
@RestController
@RequestMapping("/games")
@RequiredArgsConstructor
@Validated
@Slf4j
public class GameMetadataController {

    private final GameService gameService;
    private final GameMetadataScanner gameMetadataScanner;

    /**
     * 获取游戏列表（支持关键词搜索）
     */
    @Operation(summary = "获取游戏列表", description = "获取所有游戏列表，支持按游戏名称/编码关键词搜索")
    @GetMapping("/list")
    public Result<List<GameVO>> list(
            @Parameter(description = "关键词（游戏名称/编码，模糊匹配）") @RequestParam(required = false) String keyword) {
        List<GameVO> games = gameService.searchGames(keyword);
        return Result.success(games);
    }

    /**
     * 分页获取游戏列表
     */
    @Operation(summary = "分页获取游戏列表", description = "分页获取游戏列表")
    @GetMapping
    public Result<PageResult<GameVO>> page(PageQueryDTO queryDTO) {
        PageResult<GameVO> result = gameService.pageGames(queryDTO);
        return Result.success(result);
    }

    /**
     * 获取游戏详情
     */
    @Operation(summary = "获取游戏详情", description = "根据ID获取游戏详情")
    @GetMapping("/{id}")
    public Result<GameVO> getById(@Parameter(description = "游戏ID") @PathVariable Long id) {
        GameVO gameVO = gameService.getGameById(id);
        return Result.success(gameVO);
    }

    /**
     * 获取部署配置
     */
    @Operation(summary = "获取部署配置", description = "根据游戏ID和部署类型获取部署配置（变量元信息、compose模板等）")
    @GetMapping("/{id}/deploy-config/{deployType}")
    public Result<DeployConfigVO> getDeployConfig(
            @Parameter(description = "游戏ID") @PathVariable Long id,
            @Parameter(description = "部署类型（docker/linuxgsm/docker-compose）") @PathVariable String deployType) {
        DeployConfigVO vo = gameService.getDeployConfig(id, deployType);
        return Result.success(vo);
    }

    /**
     * 根据游戏代码获取游戏
     */
    @Operation(summary = "根据游戏代码获取游戏", description = "根据游戏代码获取游戏信息")
    @GetMapping("/code/{gameCode}")
    public Result<GameVO> getByCode(@Parameter(description = "游戏代码") @PathVariable String gameCode) {
        GameVO gameVO = gameService.getGameByCode(gameCode);
        return Result.success(gameVO);
    }

    /**
     * 新增游戏元数据
     */
    @Operation(summary = "新增游戏", description = "新增游戏元数据")
    @PostMapping
    public Result<GameVO> create(@Valid @RequestBody GameCreateDTO dto) {
        GameVO gameVO = gameService.createGame(dto);
        return Result.success(gameVO);
    }

    /**
     * 更新游戏元数据
     */
    @Operation(summary = "更新游戏", description = "更新游戏元数据")
    @PutMapping("/{id}")
    public Result<GameVO> update(@Parameter(description = "游戏ID") @PathVariable Long id,
                                  @Valid @RequestBody GameUpdateDTO dto) {
        dto.setId(id);
        GameVO gameVO = gameService.updateGame(dto);
        return Result.success(gameVO);
    }

    /**
     * 删除游戏元数据
     */
    @Operation(summary = "删除游戏", description = "删除游戏元数据")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "游戏ID") @PathVariable Long id) {
        gameService.deleteGame(id);
        return Result.success();
    }

    // ==================== YAML配置管理相关接口 ====================

    /**
     * 重新扫描游戏元数据配置文件
     */
    @Operation(summary = "重新扫描游戏配置", description = "重新扫描并加载所有游戏元数据YAML配置文件")
    @PostMapping("/scan")
    public Result<Map<String, Object>> rescanMetadata() {
        log.info("手动触发游戏元数据重新扫描");
        GameMetadataScanner.ScanResult scanResult = gameMetadataScanner.scanAndLoad();

        Map<String, Object> result = new HashMap<>();
        result.put("totalFiles", scanResult.getTotalFiles());
        result.put("successCount", scanResult.getSuccessCount());
        result.put("updateCount", scanResult.getUpdateCount());
        result.put("errorCount", scanResult.getErrorCount());
        result.put("loadedGames", scanResult.getLoadedGames());
        result.put("errors", scanResult.getErrors());

        return Result.success(result);
    }

    /**
     * 从外部目录扫描游戏配置
     */
    @Operation(summary = "从外部目录扫描", description = "从指定外部目录扫描游戏元数据配置文件")
    @PostMapping("/scan/external")
    public Result<Map<String, Object>> scanExternalDirectory(
            @Parameter(description = "外部目录路径") @RequestParam String path) {
        log.info("从外部目录扫描游戏配置: {}", path);
        GameMetadataScanner.ScanResult scanResult = gameMetadataScanner.scanExternalDirectory(path);

        Map<String, Object> result = new HashMap<>();
        result.put("totalFiles", scanResult.getTotalFiles());
        result.put("successCount", scanResult.getSuccessCount());
        result.put("updateCount", scanResult.getUpdateCount());
        result.put("errorCount", scanResult.getErrorCount());
        result.put("loadedGames", scanResult.getLoadedGames());
        result.put("errors", scanResult.getErrors());

        return Result.success(result);
    }

    /**
     * 导出游戏配置为YAML文件
     */
    @Operation(summary = "导出游戏配置", description = "将指定游戏的元数据导出为YAML配置文件")
    @GetMapping("/{gameCode}/export")
    public ResponseEntity<Resource> exportGameConfig(
            @Parameter(description = "游戏代码") @PathVariable String gameCode) {
        log.info("导出游戏配置: {}", gameCode);
        String yamlContent = gameMetadataScanner.exportGameConfig(gameCode);

        ByteArrayResource resource = new ByteArrayResource(yamlContent.getBytes(StandardCharsets.UTF_8));

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + gameCode + ".yml\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(resource.contentLength())
                .body(resource);
    }

    /**
     * 导入游戏配置
     */
    @Operation(summary = "导入游戏配置", description = "从YAML文件导入游戏元数据")
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Map<String, Object>> importGameConfig(
            @Parameter(description = "YAML配置文件") @RequestParam("file") MultipartFile file) {
        log.info("导入游戏配置文件: {}", file.getOriginalFilename());

        Map<String, Object> result = new HashMap<>();

        try {
            // 读取文件内容
            String yamlContent = new String(file.getBytes(), StandardCharsets.UTF_8);

            // 验证YAML格式
            GameMetadataScanner.ValidationResult validationResult = gameMetadataScanner.validateYaml(yamlContent);

            if (!validationResult.isValid()) {
                result.put("success", false);
                result.put("error", validationResult.getError());
                return Result.success(result);
            }

            // 解析并保存
            var config = gameMetadataScanner.parseYamlString(yamlContent);

            // 这里需要调用service保存
            // 由于scanner中没有直接提供保存方法，我们使用scanExternalDirectory的逻辑
            // 临时保存到临时文件然后扫描
            java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("game-import");
            java.nio.file.Path tempFile = tempDir.resolve(file.getOriginalFilename());
            java.nio.file.Files.write(tempFile, yamlContent.getBytes(StandardCharsets.UTF_8));

            GameMetadataScanner.ScanResult scanResult = gameMetadataScanner.scanExternalDirectory(tempDir.toString());

            // 清理临时文件
            java.nio.file.Files.deleteIfExists(tempFile);
            java.nio.file.Files.deleteIfExists(tempDir);

            result.put("success", scanResult.getErrorCount() == 0);
            result.put("gameCode", validationResult.getGameCode());
            result.put("gameName", validationResult.getGameName());
            result.put("errors", scanResult.getErrors());

            return Result.success(result);

        } catch (IOException e) {
            log.error("导入游戏配置失败", e);
            result.put("success", false);
            result.put("error", "文件读取失败: " + e.getMessage());
            return Result.success(result);
        }
    }

    /**
     * 验证YAML配置
     */
    @Operation(summary = "验证YAML配置", description = "验证YAML配置文件格式是否正确")
    @PostMapping("/validate")
    public Result<Map<String, Object>> validateYaml(
            @Parameter(description = "YAML配置文件") @RequestParam("file") MultipartFile file) {
        Map<String, Object> result = new HashMap<>();

        try {
            String yamlContent = new String(file.getBytes(), StandardCharsets.UTF_8);
            GameMetadataScanner.ValidationResult validationResult = gameMetadataScanner.validateYaml(yamlContent);

            result.put("valid", validationResult.isValid());
            result.put("gameCode", validationResult.getGameCode());
            result.put("gameName", validationResult.getGameName());

            if (!validationResult.isValid()) {
                result.put("error", validationResult.getError());
            }

            return Result.success(result);

        } catch (IOException e) {
            result.put("valid", false);
            result.put("error", "文件读取失败: " + e.getMessage());
            return Result.success(result);
        }
    }

    /**
     * 获取扫描统计信息
     */
    @Operation(summary = "获取扫描统计", description = "获取游戏元数据扫描统计信息")
    @GetMapping("/scan/stats")
    public Result<Map<String, Object>> getScanStats() {
        List<GameVO> allGames = gameService.getAllGames();

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalGames", allGames.size());

        // 按部署类型统计
        Map<String, Integer> deployTypeCount = new HashMap<>();
        for (GameVO game : allGames) {
            if (game.getSupportedDeployTypes() != null) {
                for (String type : game.getSupportedDeployTypes()) {
                    deployTypeCount.merge(type, 1, Integer::sum);
                }
            }
        }
        stats.put("deployTypeStats", deployTypeCount);

        return Result.success(stats);
    }
}
