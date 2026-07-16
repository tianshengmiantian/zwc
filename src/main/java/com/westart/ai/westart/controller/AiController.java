package com.westart.ai.westart.controller;

import com.westart.ai.westart.service.ApiIntegrationException;
import com.westart.ai.westart.service.BailianService;
import com.westart.ai.westart.service.BailianService.AiResult;
import com.westart.ai.westart.service.BailianService.MediaTypeName;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;

@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final BailianService bailianService;

    public AiController(BailianService bailianService) {
        this.bailianService = bailianService;
    }

    @PostMapping("/text")
    public AiResult text(@RequestBody TextRequest request) {
        if (request == null) {
            throw new ApiIntegrationException("请求体不能为空");
        }
        return bailianService.askText(request.prompt());
    }

    @PostMapping(value = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AiResult image(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String prompt
    ) {
        return bailianService.analyzeImage(file, prompt);
    }

    @PostMapping(value = "/video", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AiResult video(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String prompt,
            @RequestParam(required = false) Double fps
    ) {
        return bailianService.analyzeVideo(file, prompt, fps);
    }

    @PostMapping("/media-url")
    public AiResult mediaUrl(@RequestBody MediaUrlRequest request) {
        if (request == null || request.type() == null) {
            throw new ApiIntegrationException("type 不能为空，应为 image 或 video");
        }
        MediaTypeName type;
        try {
            type = MediaTypeName.valueOf(request.type().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ApiIntegrationException("type 只能是 image 或 video");
        }
        return bailianService.analyzeMediaUrl(
                type,
                request.url(),
                request.prompt(),
                request.fps()
        );
    }

    public record TextRequest(String prompt) {
    }

    public record MediaUrlRequest(String type, String url, String prompt, Double fps) {
    }
}
