package com.westart.ai.westart.wechat.message;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class WechatTextMessageBufferTest {

    @Test
    void combinesRapidGenerationInstructionSupplements() throws Exception {
        CountDownLatch dispatched = new CountDownLatch(1);
        List<String> results = new CopyOnWriteArrayList<>();
        try (WechatTextMessageBuffer buffer = new WechatTextMessageBuffer(
                Duration.ofMillis(20),
                Duration.ofMillis(100),
                Duration.ofMillis(300),
                6,
                (userId, text) -> {
                    results.add(userId + "=" + text);
                    dispatched.countDown();
                }
        )) {
            buffer.accept("user-1", "用语音说你好", true);
            Thread.sleep(30);
            buffer.accept("user-1", "并且加上我的名字", true);

            assertThat(dispatched.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(results).containsExactly("user-1=用语音说你好\n并且加上我的名字");
        }
    }

    @Test
    void combinesThreePartImageGenerationInstruction() throws Exception {
        CountDownLatch dispatched = new CountDownLatch(1);
        List<String> results = new CopyOnWriteArrayList<>();
        try (WechatTextMessageBuffer buffer = new WechatTextMessageBuffer(
                Duration.ofMillis(20),
                Duration.ofMillis(90),
                Duration.ofMillis(300),
                6,
                (userId, text) -> {
                    results.add(text);
                    dispatched.countDown();
                }
        )) {
            buffer.accept("user-1", "生成海边美景图", true);
            Thread.sleep(25);
            buffer.accept("user-1", "并且", false);
            Thread.sleep(25);
            buffer.accept("user-1", "加上一些人", false);

            assertThat(dispatched.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(results).containsExactly("生成海边美景图\n并且\n加上一些人");
        }
    }

    @Test
    void keepsDifferentUsersInDifferentBuffers() throws Exception {
        CountDownLatch dispatched = new CountDownLatch(2);
        List<String> results = new CopyOnWriteArrayList<>();
        try (WechatTextMessageBuffer buffer = new WechatTextMessageBuffer(
                Duration.ofMillis(30),
                Duration.ofMillis(60),
                Duration.ofMillis(200),
                6,
                (userId, text) -> {
                    results.add(userId + "=" + text);
                    dispatched.countDown();
                }
        )) {
            buffer.accept("user-a", "你好", false);
            buffer.accept("user-b", "天气怎么样", false);

            assertThat(dispatched.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(results).containsExactlyInAnyOrder("user-a=你好", "user-b=天气怎么样");
        }
    }

    @Test
    void discardsPendingTextBeforeImmediateReset() throws Exception {
        CountDownLatch dispatched = new CountDownLatch(1);
        try (WechatTextMessageBuffer buffer = new WechatTextMessageBuffer(
                Duration.ofMillis(40),
                Duration.ofMillis(80),
                Duration.ofMillis(200),
                6,
                (userId, text) -> dispatched.countDown()
        )) {
            buffer.accept("user-1", "还没说完", false);
            buffer.discard("user-1");

            assertThat(dispatched.await(150, TimeUnit.MILLISECONDS)).isFalse();
        }
    }
}
