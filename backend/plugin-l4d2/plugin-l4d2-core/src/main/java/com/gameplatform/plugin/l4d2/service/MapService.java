package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.util.VpkParser;
import com.gameplatform.plugin.l4d2.vo.MapListVO;
import com.gameplatform.plugin.l4d2.vo.MissionInfoVO;
import com.gameplatform.plugin.l4d2.vo.VpkTrimResultVO;
import com.gameplatform.plugin.service.FileAccessService;
import com.gameplatform.plugin.service.FileTransferProgressCallback;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * L4D2 地图管理服务。
 *
 * <p>负责 VPK 地图的列表/上传/删除/缓存刷新，以及地图热重载、VPK 裁剪、mission 解析等增强能力。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MapService {

    private final VpkParserService vpkParserService;
    private final VpkTrimService vpkTrimService;
    private final InstanceQueryService instanceQueryService;
    private final InstanceFileService instanceFileService;
    private final L4D2RconService rconService;
    private final L4D2Config config;
    private final L4D2PathResolver pathResolver;

    /**
     * 列出实例的所有地图（VPK 战役）。
     *
     * <p>经 InstanceFileService 列举与读取（Native=installPath，Docker=容器内
     * workingDir）——与 doUpload/deleteMap 的落位通道一致。此前走 VpkParserService
     * 的本机 Files.list，对任何远程/容器部署都只能看到后端进程工作目录（缺陷 #02）。
     */
    public List<MapListVO> listMaps(Long instanceId) {
        log.info("获取地图列表, instanceId: {}", instanceId);
        requireInstance(instanceId);
        String addonsPath = pathResolver.getAddonsPath();

        List<MapListVO> voList = new ArrayList<>();
        List<FileAccessService.FileInfo> files = instanceFileService.listFiles(instanceId, addonsPath);
        for (FileAccessService.FileInfo file : files) {
            if (file.isDirectory() || !file.getName().toLowerCase().endsWith(".vpk")) {
                continue;
            }
            String filename = file.getName();
            try {
                // 下载到临时文件复用 VpkParser 完整解析（含 missions 提取）
                Path tempVpk = Files.createTempFile("l4d2_list_", ".vpk");
                try {
                    instanceFileService.downloadFile(instanceId,
                            addonsPath + "/" + filename, tempVpk.toString());
                    VpkParser.VpkArchive archive = new VpkParser().parse(tempVpk.toFile());
                    if (archive == null) {
                        continue;
                    }
                    MapListVO vo = buildMapListVOFromArchive(archive, filename);
                    boolean exists = voList.stream()
                            .anyMatch(v -> v.getTitle().equals(vo.getTitle()));
                    if (!exists) {
                        voList.add(vo);
                    }
                } finally {
                    Files.deleteIfExists(tempVpk);
                }
            } catch (Exception e) {
                log.warn("解析 VPK 失败，跳过: {}, err={}", filename, e.getMessage());
            }
        }
        return voList;
    }

    /**
     * 暂存上传文件（同步阶段，ADR-0018 异步化）：
     * 校验扩展名/文件名合法性后落盘暂存目录，返回暂存信息供任务提交。
     * 重活（VPK 解析/SSH 上传/自动裁剪）由 map-upload 任务异步执行。
     */
    /** 支持的上传格式：VPK 直传，压缩包解包提取（ADR-0018） */
    private static final java.util.Set<String> SUPPORTED_EXTENSIONS = java.util.Set.of(".vpk", ".zip", ".rar", ".7z");

    /** 按文件名判断是否压缩包（Handler 据此分派解包提取） */
    public static boolean isArchiveFilename(String filename) {
        String ext = extensionOf(filename);
        return ext.equals(".zip") || ext.equals(".rar") || ext.equals(".7z");
    }

    public static String extensionOf(String filename) {
        if (filename == null) return "";
        String name = filename.toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }

    public StagedUpload stageUpload(Long instanceId, MultipartFile file) {
        log.info("暂存上传地图, instanceId: {}, fileName: {}", instanceId, file.getOriginalFilename());

        String filename = file.getOriginalFilename();
        String ext = extensionOf(filename);
        if (!SUPPORTED_EXTENSIONS.contains(ext)) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "只支持 VPK / ZIP / RAR / 7Z 格式的地图文件");
        }
        validateMapName(filename);
        requireInstance(instanceId);

        Path stagedFile;
        try {
            stagedFile = Files.createTempFile("l4d2_map_", ext);
            file.transferTo(stagedFile.toFile());
        } catch (IOException e) {
            throw new L4D2PluginException(L4D2PluginException.FILE, "上传文件暂存失败: " + e.getMessage(), e);
        }
        return new StagedUpload(stagedFile, filename, file.getSize());
    }

    /**
     * 暂存上传信息。
     */
    public record StagedUpload(Path stagedFile, String filename, long size) {
    }

    /**
     * 执行上传（map-upload 任务调用，ADR-0018）：
     * VPK magic 校验 → 上传到 addons/ → 清缓存 → 生成 VO → 可选自动裁剪。
     * 暂存文件生命周期由调用方（任务 Handler）管理。
     */
    public MapListVO doUpload(Long instanceId, Path stagedFile, String filename) {
        return doUpload(instanceId, stagedFile, filename, null);
    }

    /**
     * 执行上传（map-upload 任务调用，ADR-0018），带上传进度回调：
     * VPK magic 校验 → 上传到 addons/（进度覆盖此段）→ 清缓存 → 生成 VO → 可选自动裁剪。
     * 暂存文件生命周期由调用方（任务 Handler）管理。
     *
     * @param callback 上传到 addons 目录的传输进度回调，可为 null；
     *                 自动裁剪阶段内部的下载/回传不纳入回调范围
     */
    public MapListVO doUpload(Long instanceId, Path stagedFile, String filename,
                              FileTransferProgressCallback callback) {
        log.info("执行地图上传, instanceId: {}, fileName: {}", instanceId, filename);
        String addonsPath = pathResolver.getAddonsPath();
        String targetPath = addonsPath + "/" + filename;

        try {
            VpkParser vpkParser = new VpkParser();
            VpkParser.VpkArchive archive = vpkParser.parse(stagedFile.toFile());
            if (archive == null) {
                throw new L4D2PluginException(L4D2PluginException.FILE, "VPK 文件格式无效或已损坏");
            }
            // 内容防线：无 missions 的 VPK 不是有效的 L4D2 地图（解析器对无签名文件
            // 走单文件 VPK 宽松分支，垃圾文件会在此被拦下而非污染 addons 目录）
            if (archive.getMissionFiles().isEmpty()) {
                throw new L4D2PluginException(L4D2PluginException.FILE,
                        "VPK 中未找到有效的战役（missions）信息，不是有效的 L4D2 地图文件");
            }

            // 上传到远程 addons 目录（流式 + 进度回调）
            instanceFileService.uploadLocalFile(instanceId, targetPath,
                    stagedFile.toAbsolutePath().toString(), callback);

            // 清除缓存，使下次 listMaps 重新解析
            vpkParserService.clearCache(addonsPath);

            // 提取战役信息生成 VO
            MapListVO vo = buildMapListVOFromArchive(archive, filename);

            // 自动裁剪
            if (config.getVpkTrim().isEnabled()) {
                try {
                    trimMap(instanceId, filename);
                } catch (Exception e) {
                    log.warn("自动裁剪 VPK 失败，不阻塞上传: {}", filename, e);
                }
            }

            return vo;
        } catch (L4D2PluginException e) {
            throw e;
        } catch (Exception e) {
            log.error("上传地图失败, instanceId: {}, fileName: {}", instanceId, filename, e);
            throw new L4D2PluginException(L4D2PluginException.FILE, "上传地图失败: " + e.getMessage(), e);
        }
    }

    /**
     * 删除指定地图。
     */
    public void deleteMap(Long instanceId, String mapName) {
        log.info("删除地图, instanceId: {}, mapName: {}", instanceId, mapName);
        validateMapName(mapName);

        requireInstance(instanceId);
        String addonsPath = pathResolver.getAddonsPath();
        String mapPath = addonsPath + "/" + mapName;

        instanceFileService.deleteFile(instanceId, mapPath);
        vpkParserService.clearCache(addonsPath);
    }

    /**
     * 刷新地图列表缓存。
     */
    public void refreshCache(Long instanceId) {
        log.info("刷新地图列表缓存, instanceId: {}", instanceId);
        requireInstance(instanceId);
        String addonsPath = pathResolver.getAddonsPath();
        vpkParserService.clearCache(addonsPath);
    }

    /**
     * 地图热重载：通过 RCON 触发服务端重新加载 addon / mission。
     */
    public void hotReload(Long instanceId) {
        log.info("地图热重载, instanceId: {}", instanceId);
        requireInstance(instanceId);
        String command = config.getMapHotReload().getCommand();
        try {
            rconService.executeCommand(instanceId, command);
        } catch (Exception e) {
            log.error("地图热重载 RCON 执行失败, instanceId: {}", instanceId, e);
            throw new L4D2PluginException(L4D2PluginException.RCON,
                    "地图热重载失败: " + e.getMessage(), e);
        }
    }

    /**
     * 手动裁剪指定 VPK（带备份）。
     */
    public VpkTrimResultVO trimMap(Long instanceId, String mapName) {
        log.info("VPK 裁剪, instanceId: {}, mapName: {}", instanceId, mapName);
        validateMapName(mapName);

        requireInstance(instanceId);
        String addonsPath = pathResolver.getAddonsPath();
        String remoteVpkPath = addonsPath + "/" + mapName;

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("l4d2_trim_", ".vpk");
            // 下载远程 VPK 到本地
            instanceFileService.downloadFile(instanceId, remoteVpkPath,
                    tempFile.toAbsolutePath().toString());

            // 裁剪（带备份）
            VpkTrimResultVO result = vpkTrimService.trim(tempFile.toFile(), true);

            // 防呆（ADR-0018 实测教训）：裁剪产物不足原文件 1%，说明该 VPK 主要由客户端
            // 资源构成（如 workshop 脚本/材质插件），残桩会被 srcds 加载时段错误导致
            // 崩溃循环——放弃覆盖，保留远端原文件
            if (result.getOriginalSize() > 0
                    && result.getTrimmedSize() < result.getOriginalSize() / 100) {
                log.warn("裁剪产物 {} 字节不足原文件 {} 的 1%（客户端资源型 VPK），保留原文件不覆盖",
                        result.getTrimmedSize(), result.getOriginalSize());
                result.setTrimmedSize(result.getOriginalSize());
                result.setSavedBytes(0);
                result.setTrimmedEntries(0);
                return result;
            }

            // 上传裁剪后文件覆盖原 VPK
            instanceFileService.uploadLocalFile(instanceId, remoteVpkPath,
                    tempFile.toAbsolutePath().toString());

            // 清缓存
            vpkParserService.clearCache(addonsPath);
            return result;
        } catch (L4D2PluginException e) {
            throw e;
        } catch (Exception e) {
            log.error("VPK 裁剪失败, instanceId: {}, mapName: {}", instanceId, mapName, e);
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "VPK 裁剪失败: " + e.getMessage(), e);
        } finally {
            deleteTempFile(tempFile);
        }
    }

    /**
     * 批量裁剪 VPK。
     */
    public List<VpkTrimResultVO> trimBatch(Long instanceId, List<String> mapNames) {
        log.info("批量裁剪 VPK, instanceId: {}, count: {}", instanceId, mapNames.size());
        List<VpkTrimResultVO> results = new ArrayList<>();
        for (String mapName : mapNames) {
            try {
                results.add(trimMap(instanceId, mapName));
            } catch (Exception e) {
                log.warn("批量裁剪中跳过失败的 VPK: {}", mapName, e);
                VpkTrimResultVO fail = new VpkTrimResultVO();
                fail.setFileName(mapName);
                fail.setOriginalSize(0);
                fail.setTrimmedSize(0);
                fail.setSavedBytes(0);
                fail.setTotalEntries(0);
                fail.setTrimmedEntries(0);
                fail.setBackupCreated(false);
                results.add(fail);
            }
        }
        return results;
    }

    /**
     * 解析 VPK mission 信息。
     */
    public MissionInfoVO getMission(Long instanceId, String mapName) {
        log.info("解析 mission, instanceId: {}, mapName: {}", instanceId, mapName);
        validateMapName(mapName);

        requireInstance(instanceId);
        String addonsPath = pathResolver.getAddonsPath();
        String remoteVpkPath = addonsPath + "/" + mapName;

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("l4d2_mission_", ".vpk");
            instanceFileService.downloadFile(instanceId, remoteVpkPath,
                    tempFile.toAbsolutePath().toString());
            return vpkTrimService.parseMission(tempFile.toFile());
        } catch (L4D2PluginException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析 mission 失败, instanceId: {}, mapName: {}", instanceId, mapName, e);
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "解析 mission 失败: " + e.getMessage(), e);
        } finally {
            deleteTempFile(tempFile);
        }
    }

    // ========== 私有方法 ==========

    private InstanceVO requireInstance(Long instanceId) {
        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "实例不存在: " + instanceId);
        }
        return instance;
    }

    /**
     * 校验地图名（VPK 文件名）安全性：禁止路径遍历、目录分隔符。
     */
    private void validateMapName(String mapName) {
        if (mapName == null || mapName.isBlank()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "地图名不能为空");
        }
        if (mapName.contains("..") || mapName.contains("/") || mapName.contains("\\")) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "无效的地图名称: " + mapName);
        }
    }

    private MapListVO convertToMapListVO(VpkParser.Campaign campaign) {
        MapListVO vo = new MapListVO();
        vo.setTitle(campaign.getTitle());
        vo.setVpkName(campaign.getVpkName());
        List<MapListVO.ChapterVO> chapters = new ArrayList<>();
        if (campaign.getChapters() != null) {
            for (VpkParser.Chapter chapter : campaign.getChapters()) {
                MapListVO.ChapterVO chapterVO = new MapListVO.ChapterVO();
                chapterVO.setCode(chapter.getCode());
                chapterVO.setTitle(chapter.getTitle());
                chapterVO.setModes(chapter.getModes());
                chapters.add(chapterVO);
            }
        }
        vo.setChapters(chapters);
        return vo;
    }

    /**
     * 从上传的 VPK 归档中提取战役信息生成 VO。
     * 复用 VpkParserService 的合并逻辑：先解析 mission 文件，再合并。
     */
    private MapListVO buildMapListVOFromArchive(VpkParser.VpkArchive archive, String filename) {
        List<VpkParser.VpkFileEntry> missionFiles = archive.getMissionFiles();
        if (missionFiles.isEmpty()) {
            MapListVO vo = new MapListVO();
            vo.setVpkName(filename);
            vo.setChapters(new ArrayList<>());
            return vo;
        }

        VpkParser.Campaign merged = null;
        for (VpkParser.VpkFileEntry missionFile : missionFiles) {
            try {
                VpkParser.Campaign campaign = parseMissionFromPreload(missionFile);
                if (campaign != null) {
                    if (merged == null) {
                        merged = campaign;
                    } else {
                        merged = mergeCampaigns(merged, campaign);
                    }
                }
            } catch (Exception e) {
                log.error("解析 mission 文件失败: {}", missionFile.getFullPath(), e);
            }
        }
        if (merged != null) {
            merged.setVpkName(filename);
            return convertToMapListVO(merged);
        }
        MapListVO vo = new MapListVO();
        vo.setVpkName(filename);
        vo.setChapters(new ArrayList<>());
        return vo;
    }

    private VpkParser.Campaign parseMissionFromPreload(VpkParser.VpkFileEntry missionFile) throws IOException {
        byte[] preloadData = missionFile.getPreloadData();
        if (preloadData == null || preloadData.length == 0) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(preloadData)))) {
            return VpkParser.parseMissionFile(reader);
        }
    }

    private VpkParser.Campaign mergeCampaigns(VpkParser.Campaign base, VpkParser.Campaign additional) {
        if (base == null) {
            return additional;
        }
        if (additional == null) {
            return base;
        }
        if (base.getTitle() == null || base.getTitle().isEmpty()) {
            base.setTitle(additional.getTitle());
        }
        Map<String, VpkParser.Chapter> chapterMap = new HashMap<>();
        for (VpkParser.Chapter chapter : base.getChapters()) {
            chapterMap.put(chapter.getCode(), chapter);
        }
        for (VpkParser.Chapter addChapter : additional.getChapters()) {
            VpkParser.Chapter existing = chapterMap.get(addChapter.getCode());
            if (existing != null) {
                for (String mode : addChapter.getModes()) {
                    if (!existing.getModes().contains(mode)) {
                        existing.getModes().add(mode);
                    }
                }
                if (existing.getTitle() == null || existing.getTitle().isEmpty()) {
                    existing.setTitle(addChapter.getTitle());
                }
            } else {
                base.getChapters().add(addChapter);
                chapterMap.put(addChapter.getCode(), addChapter);
            }
        }
        return base;
    }

    private void deleteTempFile(Path tempFile) {
        if (tempFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException e) {
            log.warn("删除临时文件失败: {}", tempFile, e);
            tempFile.toFile().deleteOnExit();
        }
    }
}
