package com.gameplatform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gameplatform.entity.Host;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 主机信息Mapper接口
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Mapper
public interface HostMapper extends BaseMapper<Host> {

    /**
     * 根据IP地址查询主机
     *
     * @param ipAddress IP地址
     * @return 主机实体
     */
    @Select("SELECT * FROM host_info WHERE ip_address = #{ipAddress} AND is_deleted = 0")
    Host selectByIpAddress(@Param("ipAddress") String ipAddress);

    /**
     * 查询所有在线主机
     *
     * @return 在线主机列表
     */
    @Select("SELECT * FROM host_info WHERE online_status = 1 AND is_deleted = 0")
    List<Host> selectOnlineHosts();

    /**
     * 更新主机在线状态
     *
     * @param hostId       主机ID
     * @param onlineStatus 在线状态
     * @return 影响行数
     */
    @Update("UPDATE host_info SET online_status = #{onlineStatus}, update_time = datetime('now', 'localtime') WHERE id = #{hostId}")
    int updateOnlineStatus(@Param("hostId") Long hostId, @Param("onlineStatus") Integer onlineStatus);

    /**
     * 更新主机资源使用率
     *
     * @param hostId      主机ID
     * @param cpuUsage    CPU使用率
     * @param memoryUsage 内存使用率
     * @param diskUsage   磁盘使用率
     * @return 影响行数
     */
    @Update("UPDATE host_info SET cpu_usage = #{cpuUsage}, memory_usage = #{memoryUsage}, disk_usage = #{diskUsage}, last_check_time = datetime('now', 'localtime'), update_time = datetime('now', 'localtime') WHERE id = #{hostId}")
    int updateResourceUsage(@Param("hostId") Long hostId, @Param("cpuUsage") java.math.BigDecimal cpuUsage, @Param("memoryUsage") java.math.BigDecimal memoryUsage, @Param("diskUsage") java.math.BigDecimal diskUsage);

}
