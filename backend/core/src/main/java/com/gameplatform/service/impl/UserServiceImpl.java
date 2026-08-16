package com.gameplatform.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.crypto.SecureUtil;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.config.JwtTokenProvider;
import com.gameplatform.dto.LoginDTO;
import com.gameplatform.entity.User;
import com.gameplatform.mapper.UserMapper;
import com.gameplatform.service.UserService;
import com.gameplatform.vo.LoginVO;
import com.gameplatform.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 用户服务实现类
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final JwtTokenProvider jwtTokenProvider;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginVO login(LoginDTO dto, String ipAddr) {
        // 查询用户
        User user = userMapper.selectByUsername(dto.getUsername());
        if (user == null) {
            throw new BusinessException("用户名或密码错误");
        }

        // 验证密码
        String passwordHash = SecureUtil.sha256(dto.getPassword());
        if (!passwordHash.equals(user.getPasswordHash())) {
            throw new BusinessException("用户名或密码错误");
        }

        // 生成Token
        String token = jwtTokenProvider.generateToken(user.getUsername());

        // 更新登录信息
        userMapper.updateLoginInfo(user.getId(), LocalDateTime.now(), ipAddr);

        // 记录登录日志

        // 构建响应
        UserVO userVO = convertToVO(user);
        return LoginVO.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.getExpiration())
                .user(userVO)
                .build();
    }

    @Override
    public void logout() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            String username = authentication.getName();
        }
    }

    @Override
    public UserVO getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BusinessException("用户未登录");
        }
        
        String username = authentication.getName();
        User user = userMapper.selectByUsername(username);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        
        return convertToVO(user);
    }

    @Override
    public UserVO getUserByUsername(String username) {
        User user = userMapper.selectByUsername(username);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return convertToVO(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(String oldPassword, String newPassword) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BusinessException("用户未登录");
        }
        
        String username = authentication.getName();
        User user = userMapper.selectByUsername(username);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        // 验证旧密码
        String oldPasswordHash = SecureUtil.sha256(oldPassword);
        if (!oldPasswordHash.equals(user.getPasswordHash())) {
            throw new BusinessException("旧密码错误");
        }

        // 更新密码
        String newPasswordHash = SecureUtil.sha256(newPassword);
        user.setPasswordHash(newPasswordHash);
        userMapper.updateById(user);
        
    }

    /**
     * 转换为VO
     */
    private UserVO convertToVO(User user) {
        UserVO vo = new UserVO();
        BeanUtil.copyProperties(user, vo);
        return vo;
    }

}
