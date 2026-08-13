package com.gameplatform.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.common.result.PageResult;
import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.deploy.HostCredentials;
import com.gameplatform.dto.HostCreateDTO;
import com.gameplatform.dto.HostUpdateDTO;
import com.gameplatform.dto.PageQueryDTO;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.service.HostService;
import com.gameplatform.service.LogService;
import com.gameplatform.util.SshUtil;
import com.gameplatform.vo.HostResourceVO;
import com.gameplatform.vo.HostVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 主机服务实现类
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HostServiceImpl implements HostService {

    private final HostMapper hostMapper;
    private final LogService logService;
    private final SshUtil sshUtil;
    private final DeploymentAccess deployAccess;

    /**
     * 加密密钥(生产环境应从配置读取)
     */
    private static final String ENCRYPT_KEY = "GamePlatform2024";

    /**
     * SSH连接超时时间(毫秒)
     */
    private static final long SSH_TIMEOUT = 30000;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HostVO createHost(HostCreateDTO dto) {
        // 检查IP是否已存在
        Host existHost = hostMapper.selectByIpAddress(dto.getIp());
        if (existHost != null) {
            throw new BusinessException("该IP地址已存在");
        }

        Host host = new Host();
        host.setHostName(dto.getName());
        host.setIpAddress(dto.getIp());
        host.setSshPort(dto.getSshPort());
        host.setSshUser(dto.getSshUsername());
        host.setTags(dto.getTags());
        host.setRemark(dto.getRemark());
        // 局域网标识：DTO 未传时默认 false（详见 ADR-0004）
        host.setIsLanHost(Boolean.TRUE.equals(dto.getIsLanHost()));

        // 加密SSH密码
        if (dto.getSshPassword() != null && !dto.getSshPassword().isEmpty()) {
            host.setSshPassword(encrypt(dto.getSshPassword()));
        }

        // 加密SSH私钥
        if (dto.getSshPrivateKey() != null && !dto.getSshPrivateKey().isEmpty()) {
            host.setSshPrivateKey(encrypt(dto.getSshPrivateKey()));
        }

        // 初始状态为离线
        host.setOnlineStatus(0);
        
        hostMapper.insert(host);
        
        logService.log(getCurrentUser(), "CREATE", "HOST", 
                "创建主机: " + host.getHostName(), "success", null, null);
        
        return convertToVO(host);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HostVO updateHost(HostUpdateDTO dto) {
        Host host = hostMapper.selectById(dto.getId());
        if (host == null) {
            throw new BusinessException("主机不存在");
        }

        // 检查IP是否被其他主机使用
        if (dto.getIp() != null && !dto.getIp().equals(host.getIpAddress())) {
            Host existHost = hostMapper.selectByIpAddress(dto.getIp());
            if (existHost != null && !existHost.getId().equals(dto.getId())) {
                throw new BusinessException("该IP地址已被其他主机使用");
            }
        }

        // 手动映射字段
        if (dto.getName() != null) {
            host.setHostName(dto.getName());
        }
        if (dto.getIp() != null) {
            host.setIpAddress(dto.getIp());
        }
        if (dto.getSshPort() != null) {
            host.setSshPort(dto.getSshPort());
        }
        if (dto.getSshUsername() != null) {
            host.setSshUser(dto.getSshUsername());
        }
        if (dto.getTags() != null) {
            host.setTags(dto.getTags());
        }
        if (dto.getRemark() != null) {
            host.setRemark(dto.getRemark());
        }
        if (dto.getIsLanHost() != null) {
            host.setIsLanHost(dto.getIsLanHost());
        }

        // 加密SSH密码
        if (dto.getSshPassword() != null && !dto.getSshPassword().isEmpty()) {
            host.setSshPassword(encrypt(dto.getSshPassword()));
        }

        // 加密SSH私钥
        if (dto.getSshPrivateKey() != null && !dto.getSshPrivateKey().isEmpty()) {
            host.setSshPrivateKey(encrypt(dto.getSshPrivateKey()));
        }
        
        hostMapper.updateById(host);
        
        logService.log(getCurrentUser(), "UPDATE", "HOST", 
                "更新主机: " + host.getHostName(), "success", null, null);
        
        return convertToVO(host);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteHost(Long id) {
        Host host = hostMapper.selectById(id);
        if (host == null) {
            throw new BusinessException("主机不存在");
        }
        
        hostMapper.deleteById(id);
        
        logService.log(getCurrentUser(), "DELETE", "HOST", 
                "删除主机: " + host.getHostName(), "success", null, null);
    }

    @Override
    public HostVO getHostById(Long id) {
        Host host = hostMapper.selectById(id);
        if (host == null) {
            throw new BusinessException("主机不存在");
        }
        return convertToVO(host);
    }

    @Override
    public PageResult<HostVO> pageHosts(PageQueryDTO queryDTO) {
        LambdaQueryWrapper<Host> wrapper = new LambdaQueryWrapper<>();
        
        // 关键词搜索
        if (queryDTO.getKeyword() != null && !queryDTO.getKeyword().isEmpty()) {
            wrapper.like(Host::getHostName, queryDTO.getKeyword())
                    .or()
                    .like(Host::getIpAddress, queryDTO.getKeyword());
        }
        
        // 排序
        wrapper.orderByDesc(Host::getCreateTime);
        
        Page<Host> page = new Page<>(queryDTO.getCurrent(), queryDTO.getSize());
        Page<Host> result = hostMapper.selectPage(page, wrapper);
        
        List<HostVO> voList = result.getRecords().stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        
        return new PageResult<>(voList, result.getTotal(), queryDTO.getCurrent(), queryDTO.getSize());
    }

    @Override
    public List<HostVO> getOnlineHosts() {
        List<Host> hosts = hostMapper.selectOnlineHosts();
        return hosts.stream().map(this::convertToVO).collect(Collectors.toList());
    }

    @Override
    public boolean testConnection(Long id) {
        Host host = hostMapper.selectById(id);
        if (host == null) {
            throw new BusinessException("主机不存在");
        }

        log.info("测试SSH连接: {}@{}:{}", host.getSshUser(), host.getIpAddress(), host.getSshPort());

        try {
            // 凭据解析统一走 DeploymentAccess（解密私钥/密码、端口默认 22）
            HostCredentials conn = deployAccess.credentials(host);

            // 测试SSH连接
            boolean connected = sshUtil.testConnection(
                    conn.host(),
                    conn.port(),
                    conn.username(),
                    conn.privateKey(),
                    conn.password(),
                    SSH_TIMEOUT
            );

            // 更新在线状态
            hostMapper.updateOnlineStatus(id, connected ? 1 : 0);

            log.info("SSH连接测试结果: {} - {}", host.getHostName(), connected ? "成功" : "失败");

            return connected;
        } catch (Exception e) {
            log.error("SSH连接测试异常: {} - {}", host.getHostName(), e.getMessage());
            // 连接异常时更新为离线状态
            hostMapper.updateOnlineStatus(id, 0);
            return false;
        }
    }

    @Override
    public void refreshStatus(Long id) {
        Host host = hostMapper.selectById(id);
        if (host == null) {
            throw new BusinessException("主机不存在");
        }

        log.info("刷新主机状态: {}", host.getHostName());

        try {
            // 凭据解析统一走 DeploymentAccess（解密私钥/密码、端口默认 22）
            HostCredentials conn = deployAccess.credentials(host);

            // 获取主机资源信息
            SshUtil.ResourceInfo resourceInfo = sshUtil.getResourceInfo(
                    conn.host(),
                    conn.port(),
                    conn.username(),
                    conn.privateKey(),
                    conn.password()
            );

            if (resourceInfo.isSuccess()) {
                // 更新资源使用率和在线状态
                hostMapper.updateOnlineStatus(id, 1);
                // 防御性处理：部分命令可能失败导致字段为 null，此时使用 0 避免拆箱 NPE
                Double cpuUsage = resourceInfo.getCpuUsage();
                Double memoryUsage = resourceInfo.getMemoryUsage();
                Double diskUsage = resourceInfo.getDiskUsage();
                hostMapper.updateResourceUsage(
                        id,
                        BigDecimal.valueOf(cpuUsage != null ? cpuUsage : 0.0),
                        BigDecimal.valueOf(memoryUsage != null ? memoryUsage : 0.0),
                        BigDecimal.valueOf(diskUsage != null ? diskUsage : 0.0)
                );

                log.info("主机状态刷新成功: {} - CPU: {}%, 内存: {}%, 磁盘: {}%",
                        host.getHostName(),
                        cpuUsage != null ? cpuUsage : 0.0,
                        memoryUsage != null ? memoryUsage : 0.0,
                        diskUsage != null ? diskUsage : 0.0);
            } else {
                // 获取资源信息失败，更新为离线状态
                hostMapper.updateOnlineStatus(id, 0);
                log.warn("主机状态刷新失败: {} - {}", host.getHostName(), resourceInfo.getError());
            }
        } catch (Exception e) {
            log.error("主机状态刷新异常: {} - {}", host.getHostName(), e.getMessage());
            // 异常时更新为离线状态
            hostMapper.updateOnlineStatus(id, 0);
        }
    }

    /**
     * 转换为VO
     */
    private HostVO convertToVO(Host host) {
        HostVO vo = new HostVO();
        vo.setId(host.getId());
        vo.setName(host.getHostName());
        vo.setIp(host.getIpAddress());
        vo.setSshPort(host.getSshPort());
        vo.setSshUsername(host.getSshUser());
        vo.setStatus(host.getOnlineStatus());
        vo.setTags(host.getTags());
        vo.setOsType(host.getOsType());
        vo.setOsVersion(host.getOsVersion());
        vo.setCpuCores(host.getCpuCores());
        vo.setMemoryMb(host.getMemoryMb());
        vo.setDiskGb(host.getDiskGb());
        vo.setCpuUsage(host.getCpuUsage());
        vo.setMemoryUsage(host.getMemoryUsage());
        vo.setDiskUsage(host.getDiskUsage());
        vo.setLastCheckTime(host.getLastCheckTime());
        vo.setRemark(host.getRemark());
        vo.setIsLanHost(host.getIsLanHost());
        vo.setCreateTime(host.getCreateTime());
        vo.setUpdateTime(host.getUpdateTime());
        // 不返回敏感信息
        return vo;
    }

    /**
     * 加密
     */
    private String encrypt(String content) {
        return SecureUtil.aes(ENCRYPT_KEY.getBytes()).encryptBase64(content);
    }

    /**
     * 获取当前用户
     */
    private String getCurrentUser() {
        // TODO: 从SecurityContext获取当前用户
        return "admin";
    }

    @Override
    public void refreshAllHostsStatus() {
        List<Host> allHosts = hostMapper.selectList(null);

        if (allHosts.isEmpty()) {
            log.debug("没有主机需要刷新状态");
            return;
        }

        log.info("开始刷新所有主机状态, 共 {} 台主机", allHosts.size());

        int successCount = 0;
        int failCount = 0;

        for (Host host : allHosts) {
            try {
                refreshStatus(host.getId());
                successCount++;
            } catch (Exception e) {
                log.error("刷新主机状态失败: {} - {}", host.getHostName(), e.getMessage());
                failCount++;
            }
        }

        log.info("主机状态刷新完成, 成功: {}, 失败: {}", successCount, failCount);
    }

    @Override
    public SshUtil.PortCheckResult checkPort(Long id, int port) {
        Host host = hostMapper.selectById(id);
        if (host == null) {
            throw new BusinessException("主机不存在");
        }

        log.info("检查主机端口占用: {} - port={}", host.getHostName(), port);

        try {
            // 凭据解析统一走 DeploymentAccess（解密私钥/密码、端口默认 22）
            HostCredentials conn = deployAccess.credentials(host);

            // 通过SSH检查端口占用
            SshUtil.PortCheckResult result = sshUtil.checkPort(
                    conn.host(),
                    conn.port(),
                    conn.username(),
                    conn.privateKey(),
                    conn.password(),
                    port
            );

            log.info("端口检查结果: {} - port={} - available={} - usedBy={}",
                    host.getHostName(), port, result.isAvailable(), result.getUsedBy());

            return result;
        } catch (Exception e) {
            log.error("检查端口占用异常: {} - port={} - {}", host.getHostName(), port, e.getMessage());
            SshUtil.PortCheckResult result = new SshUtil.PortCheckResult();
            result.setPort(port);
            result.setAvailable(false);
            result.setError(e.getMessage());
            return result;
        }
    }

    @Override
    public HostResourceVO getHostResourceInfo(Long id) {
        Host host = hostMapper.selectById(id);
        if (host == null) {
            throw new BusinessException("主机不存在");
        }

        log.info("获取主机详细资源信息: {}", host.getHostName());

        try {
            // 凭据解析统一走 DeploymentAccess（解密私钥/密码、端口默认 22）
            HostCredentials conn = deployAccess.credentials(host);

            // 获取主机详细资源信息
            SshUtil.ResourceInfo resourceInfo = sshUtil.getResourceInfo(
                    conn.host(),
                    conn.port(),
                    conn.username(),
                    conn.privateKey(),
                    conn.password()
            );

            if (resourceInfo.isSuccess()) {
                // 更新在线状态
                hostMapper.updateOnlineStatus(id, 1);

                // 构建HostResourceVO
                HostResourceVO resourceVO = new HostResourceVO();

                // CPU信息
                HostResourceVO.CpuInfo cpuInfo = new HostResourceVO.CpuInfo();
                cpuInfo.setCores(resourceInfo.getCpuCores());
                cpuInfo.setUsage(resourceInfo.getCpuUsage());
                cpuInfo.setModel(resourceInfo.getCpuModel());
                resourceVO.setCpu(cpuInfo);

                // 内存信息
                HostResourceVO.MemoryInfo memoryInfo = new HostResourceVO.MemoryInfo();
                memoryInfo.setTotal(resourceInfo.getMemoryTotal());
                memoryInfo.setUsed(resourceInfo.getMemoryUsed());
                memoryInfo.setFree(resourceInfo.getMemoryFree());
                memoryInfo.setUsage(resourceInfo.getMemoryUsage());
                resourceVO.setMemory(memoryInfo);

                // 磁盘信息
                HostResourceVO.DiskInfo diskInfo = new HostResourceVO.DiskInfo();
                diskInfo.setTotal(resourceInfo.getDiskTotal());
                diskInfo.setUsed(resourceInfo.getDiskUsed());
                diskInfo.setFree(resourceInfo.getDiskFree());
                diskInfo.setUsage(resourceInfo.getDiskUsage());
                resourceVO.setDisk(diskInfo);

                // 网络信息
                HostResourceVO.NetworkInfo networkInfo = new HostResourceVO.NetworkInfo();
                networkInfo.setRxBytes(resourceInfo.getNetworkRxBytes());
                networkInfo.setTxBytes(resourceInfo.getNetworkTxBytes());
                resourceVO.setNetwork(networkInfo);

                log.info("获取主机资源信息成功: {} - CPU: {}%, 内存: {}%, 磁盘: {}%",
                        host.getHostName(),
                        resourceInfo.getCpuUsage(),
                        resourceInfo.getMemoryUsage(),
                        resourceInfo.getDiskUsage());

                return resourceVO;
            } else {
                // 获取资源信息失败，更新为离线状态
                hostMapper.updateOnlineStatus(id, 0);
                log.warn("获取主机资源信息失败: {} - {}", host.getHostName(), resourceInfo.getError());
                return null;
            }
        } catch (Exception e) {
            log.error("获取主机资源信息异常: {} - {}", host.getHostName(), e.getMessage());
            // 异常时更新为离线状态
            hostMapper.updateOnlineStatus(id, 0);
            return null;
        }
    }

}
