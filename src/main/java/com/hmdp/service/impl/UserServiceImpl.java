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
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
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

    @Override
    public Result sign() {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();//从1开始计算
        //5.写入Redis SETBIT key offset 1
        //Redis的BitMap从0开始计算，因此 dayOfMonth - 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();//从1开始计算
        //5.获取本月截止今天为止的所有签到记录，返回的是一个十进制的数字，例如 FITFIELD sign:5:202306 GET u15 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            //没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        //6.遍历
        int count = 0;
        while (true) {
            //6.1让这个数字和1做与运算，得到数字二进制的最后一个bit位，判断是否为0
            if ((num & 1) == 0) {
                //若为0，说明为签到，循环结束
                break;
            } else {
                //若不为0，说明已签到，计数器+1
                count++;

            }
            //然后数字逻辑右移，下一次循环
            num >>>= 1;
        }
        return Result.ok(count);
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
