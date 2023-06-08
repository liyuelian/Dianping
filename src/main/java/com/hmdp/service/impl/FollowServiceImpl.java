package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 服务实现类
 *
 * @author 李
 * @version 1.0
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    //关注or取关功能
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        if (UserHolder.getUser() == null) {
            return Result.fail("用户未登录");
        }
        //1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        //2.判断是关注还是取关功能
        if (isFollow) {
            //3.关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if (isSuccess) {
                //将关注用户的id，放入到redis的set集合中 sadd userId followerUserId
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            //4.取关，删除数据 delete form tb_follow where user_id = ? and follow_user_id = ?
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            if (isSuccess) {
                //将关注用户的id从set集合中移除
                stringRedisTemplate.opsForSet().remove(key, followUserId);
            }
        }
        return Result.ok();
    }

    //查询当前用户对某博主的关注状态
    @Override
    public Result isFollow(Long followUserId) {
        if (UserHolder.getUser() == null) {
            return Result.fail("用户未登录");
        }
        //1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        //2.查询是否关注 select count(*) from tb_follow where user_id =? and follow_user_id =?
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);//如果count>0,表示已关注，返回true，反之，返回false
    }

    @Override
    public Result followCommons(Long id) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        String key2 = "follows:" + id;
        //2.求交集
        //结果为交集的所有用户id
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (intersect == null || intersect.isEmpty()) {
            //如果没有交集
            return Result.ok(Collections.emptyList());
        }
        //3.解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //4.查询用户
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}
