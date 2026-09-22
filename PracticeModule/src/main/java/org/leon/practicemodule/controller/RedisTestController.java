package org.leon.practicemodule.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/test/redis")
public class RedisTestController {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @GetMapping("/set")
    public String set() {
        // 存入 Redis，设置 5 分钟过期
        redisTemplate.opsForValue().set("test:key", "hello redis " + System.currentTimeMillis(), 5, TimeUnit.MINUTES);
        return "set ok";
    }

    @GetMapping("/get")
    public Object get() {
        return redisTemplate.opsForValue().get("test:key");
    }

    @GetMapping("/zset")
    public Object testZSet() {
        // 测试之前设计的错题ZSet：用户2在科目1答错的题目ID
        String key = "qb:user:2:wrong:1";
        long now = System.currentTimeMillis();
        redisTemplate.opsForZSet().add(key, "2", now); // 题目ID 2
        redisTemplate.opsForZSet().add(key, "6", now); // 题目ID 6
        return redisTemplate.opsForZSet().range(key, 0, -1);
    }
}