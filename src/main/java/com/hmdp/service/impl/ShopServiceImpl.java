package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        // 缓存穿透实现
        // Shop shop = queryWithPassThrough(id);

        //  互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("获取店铺信息失败！");
        }
        return Result.ok(shop);
    }

    // 缓存击穿
    public Shop queryWithMutex(Long id) {
        //1、从redis中查询店铺缓存
        String key = CACHE_SHOP_KEY + id;

        //2、判断缓存是否命中
        String shopJSON = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJSON)) {
            //命中则返回shop数据
            Shop shop = JSONUtil.toBean(shopJSON, Shop.class);
            return shop;
        }

        //如果不为null，证明是空字符串，则返回错误信息，反之为null查询数据库
        if (shopJSON != null) {
            return null;
        }

        // 4.缓存重建
        // 4.1获取互斥锁
        String lockKey = "lock:shop:" + id;
        Shop shop = null;
        try {
            // 4.2 尝试获取锁
            int count = 0;
            while (!tryLock(lockKey)) {
                //获取不超过防止死等
                Thread.sleep(50);
                if (++count >= 5) return null;
            }

            // 4.4 获取成功，判断redis中是否有缓存，存在则无需重建缓存造成多次查询
            shopJSON = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJSON)) {
                return JSONUtil.toBean(shopJSON, Shop.class);
            }

            //查询数据库重建缓存
            shop = getById(id);
            // 5、数据库没有数据则失败，并向redis写入空值防止缓存穿透
            if (shop == null) {
                stringRedisTemplate.opsForValue().
                        set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6、有数据则写入redis中
            stringRedisTemplate.opsForValue()
                    .set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7.释放互斥锁
            stringRedisTemplate.delete(lockKey);
        }
        //返回数据
        return shop;
    }

    private boolean tryLock(String key) {
        Boolean result = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(result);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    // 缓存穿透
    public Shop queryWithPassThrough(Long id) {
        //1、从redis中查询店铺缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJSON = stringRedisTemplate.opsForValue().get(key);

        //2、判断缓存是否命中
        if (StrUtil.isNotBlank(shopJSON)) {
            //命中则返回shop数据
            Shop shop = JSONUtil.toBean(shopJSON, Shop.class);
            return shop;
        }

        //如果不为null，证明是空字符串，则返回错误信息，反之为null查询数据库
        if (shopJSON != null) {
            return null;
        }

        //3、未命中
        //3.1、未命中则查询数据库
        Shop shop = getById(id);
        //3.2、数据库没有数据则失败，并向redis写入空值防止缓存穿透
        if (shop == null) {
            stringRedisTemplate.opsForValue().
                    set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //3.3、有数据则写入redis中
        stringRedisTemplate.opsForValue()
                .set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //返回数据
        return shop;
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空！");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除redis缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        return Result.ok();
    }
}