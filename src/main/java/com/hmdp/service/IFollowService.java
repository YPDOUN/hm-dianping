package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    /**
     * 实现关注逻辑
     * @param followUserId 待关注的用户ID
     * @param isFollow 关注/取消关注
     */
    Result follow(Long followUserId, Boolean isFollow);

    /**
     * 判断用户是否已经关注，返回给前端做展示
     * @param followUserId 待关注的用户ID
     */
    Result isFollow(Long followUserId);

    /**
     * 查找共同关注
     */
    Result followCommons(Long id);
}
