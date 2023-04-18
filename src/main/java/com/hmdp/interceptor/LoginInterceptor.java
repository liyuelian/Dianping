package com.hmdp.interceptor;

import cn.hutool.http.HttpStatus;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.utils.UserHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * @author 李
 * @version 1.0
 * 登录拦截器
 */
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.获取session中的用户
        HttpSession session = request.getSession();
        UserDTO userDTO = (UserDTO) session.getAttribute("user");
        //2.判断用户是否登录过
        if (userDTO == null) {
            //如果没有登录过，拦截，返回状态码-401
            response.setStatus(HttpStatus.HTTP_UNAUTHORIZED);
            return false;//拦截
        }
        //3.如果存在，保存用户到ThreadLocal
        UserHolder.saveUser(userDTO);//这里是一个工具类，自动创建ThreadLocal
        ThreadLocal<UserDTO> userThreadLocal = new ThreadLocal<UserDTO>();
        userThreadLocal.set(userDTO);
        //4.放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //移除用户（根据当前线程，移除用户信息）
        /**
         * 为什么要移除用户？
         * 因为ThreadLocal对应的是一个线程的中数据
         * 每次http请求，tomcat都会创建一个新的线程
         * 但是一次http请求结束后，如果web容器使用了线程池（线程被重复利用），
         * 问题一：ThreadLocal的生命周期不等于一次Request的生命周期，造成获取threadLocal内数据异常
         * 问题二：内存溢出，ThreadLocal依赖没有释放，无法GC
         * 因此，需要手动移除数据
         */
        UserHolder.removeUser();
    }
}
