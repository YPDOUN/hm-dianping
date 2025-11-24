package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisIdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private RedisIdGenerator redisIdGenerator;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IShopService shopService;

    private ExecutorService es = Executors.newFixedThreadPool(500);
    @Test
    void testIdGenerator() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = ()-> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdGenerator.nextId("order");
                System.out.println("id=" + id);
            }
            latch.countDown();
        };

        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();

        System.out.println("time = " + (end - begin));
    }

    @Test
    void saveToken() throws IOException {
        for (int i = 600; i < 1600; i++) {
            String token = UUID.randomUUID().toString(false);

            User user = new User();
            user.setNickName("user_" + RandomUtil.randomString(8));
            user.setId((long) i);

            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                    CopyOptions.create()
                            .setIgnoreNullValue(true)
                            .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

            stringRedisTemplate.opsForHash().putAll("login:token:" + token, userMap);
            stringRedisTemplate.expire("login:token" + token, 1, TimeUnit.HOURS);

            BufferedWriter bf = new BufferedWriter(new FileWriter("src/main/resources/tokens.txt", true));
            if (i != 1599) {
                bf.write(token);
                bf.newLine();
            } else {
                bf.write(token);
            }
            bf.close();
        }
    }


    @Test
    void loadShopData() {
        List<Shop> shops = shopService.list();

        /*for (Shop shop : shops) {
            String typeKey = "shop:geo:" + shop.getTypeId().toString();
            stringRedisTemplate.opsForGeo().add(typeKey, new Point(shop.getX(), shop.getY()), shop.getId().toString());
        }*/

        Map<Long, List<Shop>> map = shops.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            Long typeId = entry.getKey();
            String typeKey = "shop:geo:" + typeId;
            List<Shop> value = entry.getValue();

            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            for (Shop shop : value) {
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(typeKey, locations);
        }
    }
}
