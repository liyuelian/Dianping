package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

/**
 * 服务实现类
 *
 * @author 李
 * @version 1.0
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    //关注or取关功能
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        if (UserHolder.getUser() == null) {
            return Result.fail("用户未登录");
        }
        //1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        //2.判断是关注还是取关功能
        if (isFollow) {
            //3.关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            save(follow);
        } else {
            //4.取关，删除数据 delete form tb_follow where user_id = ? and follow_user_id = ?
            remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
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
}
