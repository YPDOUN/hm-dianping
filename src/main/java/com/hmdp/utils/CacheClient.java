package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.sql.Time;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 保存数据到redis中
     * 设置过期时间
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 保存数据到redis中
     * 设置逻辑过期时间
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        redisData.setData(value);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存穿透
     */
    public <R, ID> R queryWithPassThrough(
            String prefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        //1、从redis中查询店铺缓存
        String key = prefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        //2、判断缓存是否命中
        if (StrUtil.isNotBlank(json)) {
            //命中则返回shop数据
            return JSONUtil.toBean(json, type);
        }

        //如果不为null，证明是空字符串，则返回错误信息，反之为null查询数据库
        if (json != null) {
            return null;
        }

        //3、未命中
        //3.1、未命中则查询数据库
        R r = dbFallback.apply(id);
        //3.2、数据库没有数据则失败，并向redis写入空值防止缓存穿透
        if (r == null) {
            this.set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //3.3、有数据则写入redis中
        this.set(key, JSONUtil.toJsonStr(r), time, unit);
        //返回数据
        return r;
    }

    /**
     * 缓存击穿
     */
    public <R, ID> R queryWithMutex(
            String prefix, ID id, Class<R> type, Function<ID, R> dbFallBack, Long time, TimeUnit unit) {
        //1、从redis中查询店铺缓存
        String key = prefix + id;

        //2、判断缓存是否命中
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            //命中则返回shop数据
            return JSONUtil.toBean(json, type);
        }

        //如果不为null，证明是空字符串，则返回错误信息，反之为null查询数据库
        if (json != null) {
            return null;
        }

        // 4.缓存重建
        // 4.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        R r = null;
        try {
            // 4.2 尝试获取锁
            int count = 0;
            while (!tryLock(lockKey)) {
                //获取不超过防止死等
                Thread.sleep(50);
                if (++count >= 5) return null;
            }

            // 4.4 获取成功，判断redis中是否有缓存，存在则无需重建缓存造成多次查询
            json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                return JSONUtil.toBean(json, type);
            }

            //查询数据库重建缓存
            r = dbFallBack.apply(id);
            //模拟重建延时
            //Thread.sleep(200);
            // 5、数据库没有数据则失败，并向redis写入空值防止缓存穿透
            if (r == null) {
                this.set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6、有数据则写入redis中
            this.set(key, JSONUtil.toJsonStr(r), time, unit);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7.释放互斥锁
            unlock(lockKey);
        }
        //返回数据
        return r;
    }

    private boolean tryLock(String key) {
        Boolean result = stringRedisTemplate.opsForValue().
                setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(result);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    // 逻辑过期
    public <R, ID> R queryWithLogicalExpire(
            String prefix, ID id, Class<R> type, Function<ID, R> dbFallBack, Long time, TimeUnit unit) {
        //1、从redis中查询店铺缓存
        String key = prefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        //2、判断缓存是否命中
        if (StrUtil.isBlank(json)) {
            //2.1 未命中直接返回null
            return null;
        }

        //2.2 命中则判断缓存是否过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);

        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            // 未过期返回数据
            return r;
        }

        // 3、过期则需要缓存重建
        // 3.1 尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        // 获取成功，则开启独立线程进行缓存重建
        if (tryLock(lockKey)) {
            try {
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    // 重建缓存
                    // 1.查询数据库
                    R apply = dbFallBack.apply(id);
                    // 2.写入redis
                    this.setWithLogicalExpire(key, apply, time, unit);
                });
            } finally {
                unlock(lockKey);
            }
        }
        // 获取失败返回旧的数据
        return r;
    }
}
