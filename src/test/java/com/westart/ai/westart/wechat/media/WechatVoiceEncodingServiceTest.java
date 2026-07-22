package com.westart.ai.westart.wechat.media;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WechatVoiceEncodingServiceTest {

    @Test
    void encodesPcmAsTencentSilk() {
        WechatVoiceEncodingService service = new WechatVoiceEncodingService("ffmpeg");
        byte[] pcm = oneSecondSineWave();

        byte[] silk = service.encodePcmToSilk(pcm);

        assertThat(silk).isNotEmpty();
        assertThat(new String(silk, 1, 9, StandardCharsets.US_ASCII)).isEqualTo("#!SILK_V3");
    }

    private static byte[] oneSecondSineWave() {
        byte[] pcm = new byte[WechatVoiceEncodingService.WECHAT_SAMPLE_RATE * 2];
        for (int sample = 0; sample < WechatVoiceEncodingService.WECHAT_SAMPLE_RATE; sample++) {
            short value = (short) (Math.sin(2 * Math.PI * 440 * sample
                    / WechatVoiceEncodingService.WECHAT_SAMPLE_RATE) * 4_000);
            pcm[sample * 2] = (byte) value;
            pcm[sample * 2 + 1] = (byte) (value >>> 8);
        }
        return pcm;
    }
}
