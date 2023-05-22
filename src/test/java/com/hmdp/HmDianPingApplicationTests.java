package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

/**
 * @author 李
 * @version 1.0
 */
@SpringBootTest
public class HmDianPingApplicationTests {
    @Resource
    private CacheClient cacheClient;
    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    public void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        //每个任务都生成100个id
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id=" + id);
            }
            latch.countDown();
        };
        long start = System.currentTimeMillis();
        //共执行300次任务
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        //让所有线程执行完才计时
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("共用时=" + (end - start));
    }

    //提前存储热点key
    @Test
    public void testSaveShop() {
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(
                CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS);
    }

    @Test
    public void loadShopData() {
        //1.在DB中查询店铺信息
        List<Shop> list = shopService.list();
        //2.将店铺按照typeId分组，相同的放到同一个集合
        Map<Long, List<Shop>> map = list.stream()
                .collect(Collectors.groupingBy(Shop::getTypeId));
        //3.分批完成写入redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            //3.1获取类型id
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            //3.2获取同类型的店铺的集合
            List<Shop> value = entry.getValue();
            //GeoLocation中的属性有 T name，Point point;
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            //写入redis GEOADD key 经度 纬度 member
            for (Shop shop : value) {
                //stringRedisTemplate.opsForGeo()
                // .add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());

                //一个locations就代表一组typeId的商店
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY()))
                );
            }
            //将一组typeId的商店信息批量写入redis中
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

}
