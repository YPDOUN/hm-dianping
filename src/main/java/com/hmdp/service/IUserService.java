package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    Result code(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);

    /**
     * 根据用户id查询用户信息，返回DTO对象
     */
    Result queryUserData(Long id);
}
