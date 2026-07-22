package com.westart.ai.westart.wechat.message;

import com.westart.ai.westart.wechat.message.WechatImageGenerationCoordinator.GenerationTicket;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class WechatImageGenerationCoordinatorTest {

    @Test
    void continuationInvalidatesInFlightGeneration() {
        WechatImageGenerationCoordinator coordinator = new WechatImageGenerationCoordinator(
                Duration.ofSeconds(1)
        );
        GenerationTicket original = coordinator.begin("user-1", "生成海边美景图");

        String revised = coordinator.reviseIfRecent("user-1", "加上一些人");

        assertThat(revised).isEqualTo("生成海边美景图\n加上一些人");
        assertThat(coordinator.claimDelivery(original)).isFalse();
        GenerationTicket replacement = coordinator.begin("user-1", revised);
        assertThat(coordinator.claimDelivery(replacement)).isTrue();
    }

    @Test
    void completedGenerationCanStillReceiveARecentRevision() {
        WechatImageGenerationCoordinator coordinator = new WechatImageGenerationCoordinator(
                Duration.ofSeconds(1)
        );
        GenerationTicket original = coordinator.begin("user-1", "画一只小猫图片");
        assertThat(coordinator.claimDelivery(original)).isTrue();
        coordinator.complete(original);

        assertThat(coordinator.reviseIfRecent("user-1", "改成橘猫"))
                .isEqualTo("画一只小猫图片\n改成橘猫");
    }

    @Test
    void keepsUsersIndependent() {
        WechatImageGenerationCoordinator coordinator = new WechatImageGenerationCoordinator(
                Duration.ofSeconds(1)
        );
        coordinator.begin("user-a", "生成海边图片");
        coordinator.begin("user-b", "生成雪山图片");

        assertThat(coordinator.reviseIfRecent("user-a", "增加人物"))
                .isEqualTo("生成海边图片\n增加人物");
        assertThat(coordinator.reviseIfRecent("user-b", "改成夜景"))
                .isEqualTo("生成雪山图片\n改成夜景");
    }
}
