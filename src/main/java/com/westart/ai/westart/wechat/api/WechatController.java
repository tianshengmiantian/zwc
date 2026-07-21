package com.westart.ai.westart.wechat.api;

import com.westart.ai.westart.common.exception.ApiIntegrationException;
import com.westart.ai.westart.wechat.account.WechatAccountManager;
import com.westart.ai.westart.wechat.session.WechatBotService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/wechat")
public class WechatController {

    private final WechatAccountManager accountManager;

    public WechatController(WechatAccountManager accountManager) {
        this.accountManager = accountManager;
    }

    @PostMapping("/login")
    public WechatBotService.LoginStartResult login(
            HttpServletRequest request,
            @RequestHeader(name = "X-WeStart-Admin-Key", required = false) String adminKey
    ) {
        requireLocalOrAdmin(request, adminKey);
        return accountManager.defaultSession().startLogin();
    }

    @GetMapping(value = "/login/qrcode", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> loginQrCode(
            HttpServletRequest request,
            @RequestHeader(name = "X-WeStart-Admin-Key", required = false) String adminKey
    ) {
        requireLocalOrAdmin(request, adminKey);
        return qrCodeResponse(accountManager.defaultSession());
    }

    @GetMapping("/status")
    public WechatBotService.BotStatus status() {
        return accountManager.defaultSession().status();
    }

    @PostMapping("/login/verify")
    public WechatBotService.BotStatus verifyCode(
            HttpServletRequest request,
            @RequestHeader(name = "X-WeStart-Admin-Key", required = false) String adminKey,
            @RequestBody VerifyCodeRequest verifyCodeRequest
    ) {
        requireLocalOrAdmin(request, adminKey);
        return accountManager.defaultSession().submitVerificationCode(
                verifyCodeRequest == null ? null : verifyCodeRequest.code()
        );
    }

    @PostMapping("/disconnect")
    public WechatBotService.BotStatus disconnect(
            HttpServletRequest request,
            @RequestHeader(name = "X-WeStart-Admin-Key", required = false) String adminKey
    ) {
        requireLocalOrAdmin(request, adminKey);
        return accountManager.defaultSession().disconnect();
    }

    @GetMapping("/accounts")
    public List<WechatAccountManager.AccountSummary> accounts(
            HttpServletRequest request,
            @RequestHeader(name = "X-WeStart-Admin-Key", required = false) String adminKey
    ) {
        requireLocalOrAdmin(request, adminKey);
        return accountManager.listAccounts();
    }

    @PostMapping("/accounts")
    public WechatAccountManager.AccountSummary createAccount(
            HttpServletRequest request,
            @RequestHeader(name = "X-WeStart-Admin-Key", required = false) String adminKey,
            @RequestBody AccountRequest accountRequest
    ) {
        requireLocalOrAdmin(request, adminKey);
        if (accountRequest == null) {
            throw new ApiIntegrationException("请求体不能为空");
        }
        return accountManager.createAccount(
                accountRequest.accountId(),
                accountRequest.displayName()
        );
    }

    @DeleteMapping("/accounts/{accountId}")
    public Map<String, Object> removeAccount(
            HttpServletRequest request,
            @RequestHeader(name = "X-WeStart-Admin-Key", required = false) String adminKey,
            @PathVariable String accountId
    ) {
        requireLocalOrAdmin(request, adminKey);
        accountManager.removeAccount(accountId);
        return Map.of("success", true, "accountId", accountId);
    }

    @PostMapping("/accounts/{accountId}/login")
    public WechatBotService.LoginStartResult accountLogin(
            HttpServletRequest request,
            @RequestHeader(name = "X-WeStart-Admin-Key", required = false) String adminKey,
            @PathVariable String accountId
    ) {
        requireLocalOrAdmin(request, adminKey);
        return accountManager.session(accountId).startLogin();
    }

    @GetMapping(
            value = "/accounts/{accountId}/login/qrcode",
            produces = MediaType.IMAGE_PNG_VALUE
    )
    public ResponseEntity<byte[]> accountLoginQrCode(
            HttpServletRequest request,
            @RequestHeader(name = "X-WeStart-Admin-Key", required = false) String adminKey,
            @PathVariable String accountId
    ) {
        requireLocalOrAdmin(request, adminKey);
        return qrCodeResponse(accountManager.session(accountId));
    }

    @GetMapping("/accounts/{accountId}/status")
    public WechatBotService.BotStatus accountStatus(
            HttpServletRequest request,
            @RequestHeader(name = "X-WeStart-Admin-Key", required = false) String adminKey,
            @PathVariable String accountId
    ) {
        requireLocalOrAdmin(request, adminKey);
        return accountManager.session(accountId).status();
    }

    @PostMapping("/accounts/{accountId}/login/verify")
    public WechatBotService.BotStatus accountVerifyCode(
            HttpServletRequest request,
            @RequestHeader(name = "X-WeStart-Admin-Key", required = false) String adminKey,
            @PathVariable String accountId,
            @RequestBody VerifyCodeRequest verifyCodeRequest
    ) {
        requireLocalOrAdmin(request, adminKey);
        return accountManager.session(accountId).submitVerificationCode(
                verifyCodeRequest == null ? null : verifyCodeRequest.code()
        );
    }

    @PostMapping("/accounts/{accountId}/disconnect")
    public WechatBotService.BotStatus accountDisconnect(
            HttpServletRequest request,
            @RequestHeader(name = "X-WeStart-Admin-Key", required = false) String adminKey,
            @PathVariable String accountId
    ) {
        requireLocalOrAdmin(request, adminKey);
        return accountManager.session(accountId).disconnect();
    }

    private static ResponseEntity<byte[]> qrCodeResponse(WechatBotService service) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_PNG)
                .body(service.loginQrCodePng());
    }

    private void requireLocalOrAdmin(HttpServletRequest request, String suppliedAdminKey) {
        if (accountManager.hasAdminKey()) {
            if (!accountManager.adminKeyMatches(suppliedAdminKey)) {
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

    public record AccountRequest(String accountId, String displayName) {
    }
}
