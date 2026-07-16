package com.westart.ai.westart.controller;

import com.westart.ai.westart.service.ApiIntegrationException;
import com.westart.ai.westart.wechat.WechatBotService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;

@RestController
@RequestMapping("/api/wechat")
public class WechatController {

    private final WechatBotService wechatBotService;

    public WechatController(WechatBotService wechatBotService) {
        this.wechatBotService = wechatBotService;
    }

    @PostMapping("/login")
    public WechatBotService.LoginStartResult login(
            HttpServletRequest request,
            @RequestHeader(name = "X-WeStart-Admin-Key", required = false) String adminKey
    ) {
        requireLocalOrAdmin(request, adminKey);
        return wechatBotService.startLogin();
    }

    @GetMapping(value = "/login/qrcode", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> loginQrCode(
            HttpServletRequest request,
            @RequestHeader(name = "X-WeStart-Admin-Key", required = false) String adminKey
    ) {
        requireLocalOrAdmin(request, adminKey);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_PNG)
                .body(wechatBotService.loginQrCodePng());
    }

    @GetMapping("/status")
    public WechatBotService.BotStatus status() {
        return wechatBotService.status();
    }

    @PostMapping("/login/verify")
    public WechatBotService.BotStatus verifyCode(
            HttpServletRequest request,
            @RequestHeader(name = "X-WeStart-Admin-Key", required = false) String adminKey,
            @RequestBody VerifyCodeRequest verifyCodeRequest
    ) {
        requireLocalOrAdmin(request, adminKey);
        return wechatBotService.submitVerificationCode(
                verifyCodeRequest == null ? null : verifyCodeRequest.code()
        );
    }

    @PostMapping("/disconnect")
    public WechatBotService.BotStatus disconnect(
            HttpServletRequest request,
            @RequestHeader(name = "X-WeStart-Admin-Key", required = false) String adminKey
    ) {
        requireLocalOrAdmin(request, adminKey);
        return wechatBotService.disconnect();
    }

    private void requireLocalOrAdmin(HttpServletRequest request, String suppliedAdminKey) {
        if (wechatBotService.hasAdminKey()) {
            if (!wechatBotService.adminKeyMatches(suppliedAdminKey)) {
                throw new ApiIntegrationException("管理密钥不正确");
            }
            return;
        }

        try {
            if (InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) {
                return;
            }
        } catch (Exception ignored) {
            // Fall through to the safe default below.
        }
        throw new ApiIntegrationException(
                "扫码登录仅允许从本机访问；远程使用时请配置 WECHAT_BOT_ADMIN_KEY"
        );
    }

    public record VerifyCodeRequest(String code) {
    }
}
