package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 服务实现类
 *
 * @author 李
 * @version 1.0
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

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

        //4.保存验证码到 redis中，这里的 key 最好使用业务前缀作为区分
        // set key value ex
        stringRedisTemplate.opsForValue()
                .set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

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

        //2.从redis获取验证码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("验证码错误");
        }

        //3.根据手机号查询用户是否已经注册
        User user = query().eq("phone", phone).one();
        if (user == null) {
            //若不存在，则在创建用户
            user = createUserWithPhone(phone);
        }

        //4.保存用户到redis中 (这里只保存id、昵称、手机号)
        //随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        //将user对象转为redis-hash
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //使用工具类转(指定转换后的数据类型为String)
        Map<String, Object> map = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                        CopyOptions.create()
                        .setIgnoreNullValue(true)
                                .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        //存储到redis中,设置有效期为30分钟
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, map);
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);

        //5.将token返回客户端
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        //1.创建用户
        User user = new User();
        //设置手机号
        user.setPhone(phone);
        //设置昵称，初始值为随机的15位字符串
        user.setNickName(LOGIN_USER_KEY + RandomUtil.randomString(10));
        //2.保存用户到DB
        save(user);
        return user;
    }
}
