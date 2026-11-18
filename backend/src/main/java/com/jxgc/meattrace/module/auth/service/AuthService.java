package com.jxgc.meattrace.module.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxgc.meattrace.common.BizException;
import com.jxgc.meattrace.common.ErrorCode;
import com.jxgc.meattrace.module.auth.dto.LoginRequest;
import com.jxgc.meattrace.module.auth.dto.LoginResponse;
import com.jxgc.meattrace.module.auth.dto.UserVO;
import com.jxgc.meattrace.module.auth.entity.SysUser;
import com.jxgc.meattrace.module.auth.mapper.SysUserMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** 登录与当前用户查询。 */
@Service
public class AuthService {

    private final SysUserMapper userMapper;
    private final JwtTokenService jwtTokenService;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(SysUserMapper userMapper, JwtTokenService jwtTokenService) {
        this.userMapper = userMapper;
        this.jwtTokenService = jwtTokenService;
    }

    public LoginResponse login(LoginRequest request) {
        SysUser user = userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, request.username()));
        // 用户不存在与口令错误返回同一个错误码，避免暴露账号是否存在
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (!user.isEnabled()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号已停用");
        }
        String token = jwtTokenService.issue(
                user.getId(), user.getUsername(), user.getRole(), user.getSupplierId());
        return new LoginResponse(token, jwtTokenService.expiresInSeconds(), toVO(user));
    }

    public UserVO currentUser(long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null || !user.isEnabled()) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return toVO(user);
    }

    private UserVO toVO(SysUser user) {
        return new UserVO(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getRole(), user.getSupplierId());
    }
}
