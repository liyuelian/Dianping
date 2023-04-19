package com.hmdp.interceptor;

import com.hmdp.utils.UserHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * @author 李
 * @version 1.0
 * 登录拦截器
 */
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //判断是否需要拦截(ThreadLocal中是否有用户)
        if (UserHolder.getUser() == null) {
            //没有，需要拦截，设置状态码
            response.setStatus(401);
            //拦截
            return false;
        }
        //如果有用户，则放行
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
