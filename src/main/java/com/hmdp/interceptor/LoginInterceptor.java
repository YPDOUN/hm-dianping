package com.hmdp.interceptor;

import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1、判断LocalThread里是否有用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            //没有则拦截，设置401状态码
            response.setStatus(401);
            return false;
        }
        //有则放行
        return true;
    }
}
