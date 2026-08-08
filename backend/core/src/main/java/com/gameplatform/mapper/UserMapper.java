package com.gameplatform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gameplatform.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 用户Mapper接口
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 根据用户名查询用户
     *
     * @param username 用户名
     * @return 用户实体
     */
    User selectByUsername(@Param("username") String username);

    /**
     * 更新最后登录信息
     *
     * @param userId    用户ID
     * @param loginTime 登录时间
     * @param loginIp   登录IP
     * @return 影响行数
     */
    @Update("UPDATE sys_user SET last_login_time = #{loginTime}, last_login_ip = #{loginIp}, update_time = #{loginTime} WHERE id = #{userId}")
    int updateLoginInfo(@Param("userId") Long userId, @Param("loginTime") LocalDateTime loginTime, @Param("loginIp") String loginIp);

}
