package com.westart.ai.westart.wechat.message;

import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class WechatImageMessageBufferTest {

    @Test
    void letsFollowingTextTakeThePendingImage() throws Exception {
        CountDownLatch automaticallyDispatched = new CountDownLatch(1);
        WeixinMessage image = new WeixinMessage();
        try (WechatImageMessageBuffer buffer = new WechatImageMessageBuffer(
                Duration.ofMillis(100),
                (userId, message) -> automaticallyDispatched.countDown()
        )) {
            buffer.accept("user-1", image);

            assertThat(buffer.take("user-1")).isSameAs(image);
            assertThat(automaticallyDispatched.await(150, TimeUnit.MILLISECONDS)).isFalse();
        }
    }

    @Test
    void dispatchesStandaloneImageAfterCaptionWindow() throws Exception {
        CountDownLatch dispatched = new CountDownLatch(1);
        List<String> users = new CopyOnWriteArrayList<>();
        WeixinMessage image = new WeixinMessage();
        try (WechatImageMessageBuffer buffer = new WechatImageMessageBuffer(
                Duration.ofMillis(40),
                (userId, message) -> {
                    users.add(userId);
                    dispatched.countDown();
                }
        )) {
            buffer.accept("user-1", image);

            assertThat(dispatched.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(users).containsExactly("user-1");
            assertThat(buffer.take("user-1")).isNull();
        }
    }

    @Test
    void keepsImagesFromDifferentUsersSeparate() {
        WeixinMessage first = new WeixinMessage();
        WeixinMessage second = new WeixinMessage();
        try (WechatImageMessageBuffer buffer = new WechatImageMessageBuffer(
                Duration.ofSeconds(1),
                (userId, message) -> {
                }
        )) {
            buffer.accept("user-a", first);
            buffer.accept("user-b", second);

            assertThat(buffer.take("user-a")).isSameAs(first);
            assertThat(buffer.take("user-b")).isSameAs(second);
        }
    }
}
