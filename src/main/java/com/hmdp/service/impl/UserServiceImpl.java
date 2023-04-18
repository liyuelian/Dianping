package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * 服务实现类
 *
 * @author 李
 * @version 1.0
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        boolean phoneInvalid = RegexUtils.isPhoneInvalid(phone);
        //2.如果不符合，返回错误信息
        if (phoneInvalid) {//true表示不符合格式
            return Result.fail("手机号格式错误！");
        }
        //3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);//生成6位数的随机号码
        //4.保存验证码
        session.setAttribute("code", code);
        //5.发送验证码(由于这里涉及到一些其他方的工具，先不做)
        log.debug("发送短信验证码成功，验证码={}", code);
        //6.返回OK
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            //如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }

        //2.校验验证码
        Object cacheCode = session.getAttribute("code");
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.toString().equals(code)) {
            //cacheCode == null 说明没有发过验证码，因为session里面没有存放
            //!cacheCode.toString().equals(code) == true
            //说明用户输入的验证码和session的不一致
            return Result.fail("验证码错误");
        }

        //3.根据手机号查询用户是否已经注册
        User user = query().eq("phone", phone).one();
        if (user == null) {
            //若不存在，则在创建用户
            user = createUserWithPhone(phone);
        }

        //4.保存用户到session(这里只保存id、昵称、手机号)
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));

        return Result.ok();
    }

    private User createUserWithPhone(String phone) {
        //1.创建用户
        User user = new User();
        //设置手机号
        user.setPhone(phone);
        //设置昵称，初始值为随机的15位字符串
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        //2.保存用户到DB
        save(user);
        return user;
    }
}
