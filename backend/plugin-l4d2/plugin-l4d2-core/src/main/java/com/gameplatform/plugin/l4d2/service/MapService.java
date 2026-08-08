package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.util.VpkParser;
import com.gameplatform.plugin.l4d2.vo.MapListVO;
import com.gameplatform.plugin.l4d2.vo.MissionInfoVO;
import com.gameplatform.plugin.l4d2.vo.VpkTrimResultVO;
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
    private final RconService rconService;
    private final L4D2Config config;
    private final L4D2PathResolver pathResolver;

    /**
     * 列出实例的所有地图（VPK 战役）。
     */
    public List<MapListVO> listMaps(Long instanceId) {
        log.info("获取地图列表, instanceId: {}", instanceId);
        requireInstance(instanceId);
        String addonsPath = pathResolver.getAddonsPath();

        List<VpkParser.Campaign> campaigns = vpkParserService.getCampaignList(addonsPath);
        List<MapListVO> voList = new ArrayList<>();
        for (VpkParser.Campaign campaign : campaigns) {
            voList.add(convertToMapListVO(campaign));
        }
        return voList;
    }

    /**
     * 上传地图：VPK magic 校验 → 上传到 addons/ → 清缓存 → 可选自动裁剪。
     */
    public MapListVO uploadMap(Long instanceId, MultipartFile file) {
        log.info("上传地图, instanceId: {}, fileName: {}", instanceId, file.getOriginalFilename());

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".vpk")) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "只支持 VPK 格式的地图文件");
        }
        validateMapName(filename);

        requireInstance(instanceId);
        String addonsPath = pathResolver.getAddonsPath();
        String targetPath = addonsPath + "/" + filename;

        // 先校验 VPK magic：临时保存文件并解析
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("l4d2_upload_", ".vpk");
            file.transferTo(tempFile.toFile());

            VpkParser vpkParser = new VpkParser();
            VpkParser.VpkArchive archive = vpkParser.parse(tempFile.toFile());
            if (archive == null) {
                throw new L4D2PluginException(L4D2PluginException.FILE, "VPK 文件格式无效或已损坏");
            }

            // 上传到远程 addons 目录
            instanceFileService.uploadLocalFile(instanceId, targetPath, tempFile.toAbsolutePath().toString());

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
        } finally {
            deleteTempFile(tempFile);
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
