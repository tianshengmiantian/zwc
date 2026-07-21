package com.westart.ai.westart.tool.image;

import com.westart.ai.westart.ai.model.image.DashScopeImageModelAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

class ImageGenerationServiceTest {

    @Test
    void generatesAndDownloadsImage() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        DashScopeImageModelAdapter imageModel = DashScopeImageModelAdapter.builder()
                .restClient(builder.build())
                .apiKey("test-key")
                .imageUrl("https://dashscope.example.test/generation")
                .modelName("qwen-image-2.0")
                .imageSize("1024*1024")
                .build();

        ImageGenerationService service = new ImageGenerationService(imageModel);
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};

        server.expect(once(), requestTo("https://dashscope.example.test/generation"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("qwen-image-2.0"))
                .andExpect(jsonPath("$.input.messages[0].content[0].text").value("一只橘猫"))
                .andExpect(jsonPath("$.parameters.size").value("1024*1024"))
                .andRespond(withSuccess("""
                        {
                          "output": {
                            "choices": [{
                              "message": {
                                "content": [{"image": "https://images.example.test/cat.png"}]
                              }
                            }]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://images.example.test/cat.png"))
                .andExpect(method(GET))
                .andRespond(withSuccess(png, MediaType.IMAGE_PNG));

        ImageGenerationService.GeneratedImage result = service.generate("一只橘猫");

        assertThat(result.model()).isEqualTo("qwen-image");
        assertThat(result.fileName()).isEqualTo("qwen-image.png");
        assertThat(result.bytes()).isEqualTo(png);
        server.verify();
    }
}
