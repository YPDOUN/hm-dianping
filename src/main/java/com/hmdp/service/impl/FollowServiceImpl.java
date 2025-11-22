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
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    /**
     * 实现关注逻辑
     * @param followUserId 待关注的用户ID
     * @param isFollow 关注/取消关注
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 1.获取当前登录用户id
        Long userId = UserHolder.getUser().getId();
        String key = "follow:" + userId;
        // 2.判断是关注还是取消关注
        if (isFollow) {
            // 2.2关注，新增用户
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);

            save(follow);
            stringRedisTemplate.opsForSet().add(key, followUserId.toString());
        } else {
            // 2.3取关，删除
            // delete from tb_follow where user_id = #{userId} and follow_user_id = #{followUserId}
            remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId));
            stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
        }
        return Result.ok();
    }

    /**
     * 判断用户是否已经关注，返回给前端做展示
     * @param followUserId 待关注的用户ID
     */
    @Override
    public Result isFollow(Long followUserId) {
        // 1.获取当前登录用户id
        Long userId = UserHolder.getUser().getId();
        // 2. 判断数据库中是否存在关注信息
        // select * from tb_follow where user_id = ? and follow_user_id = ?
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }

    /**
     * 查找共同关注
     * @param id 查找当前用户和id的共同关注列表
     */
    @Override
    public Result followCommons(Long id) {
        // 获取当前用户id
        Long userId = UserHolder.getUser().getId();

        String key1 = "follow:" + userId;
        String key2 = "follow:" + id;
        // 查找共同关注列表
        Set<String> followCommonList = stringRedisTemplate.opsForSet().intersect(key1, key2);

        if (followCommonList == null || followCommonList.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        List<Long> list = followCommonList.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> usersDTO = userService.listByIds(list).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(usersDTO);
    }
}
