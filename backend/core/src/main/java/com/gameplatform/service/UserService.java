package com.gameplatform.service;

import com.gameplatform.dto.LoginDTO;
import com.gameplatform.vo.LoginVO;
import com.gameplatform.vo.UserVO;

/**
 * 用户服务接口
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface UserService {

    /**
     * 用户登录
     *
     * @param dto    登录DTO
     * @param ipAddr IP地址
     * @return 登录响应
     */
    LoginVO login(LoginDTO dto, String ipAddr);

    /**
     * 用户登出
     */
    void logout();

    /**
     * 获取当前用户信息
     *
     * @return 用户信息
     */
    UserVO getCurrentUser();

    /**
     * 根据用户名查询用户
     *
     * @param username 用户名
     * @return 用户信息
     */
    UserVO getUserByUsername(String username);

    /**
     * 修改密码
     *
     * @param oldPassword 旧密码
     * @param newPassword 新密码
     */
    void changePassword(String oldPassword, String newPassword);

}
