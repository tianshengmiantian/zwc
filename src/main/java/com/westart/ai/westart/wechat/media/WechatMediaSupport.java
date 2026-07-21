package com.westart.ai.westart.wechat.media;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.westart.ai.westart.common.exception.ApiIntegrationException;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Message-item selection, media type detection and attachment integrity checks.
 */
public final class WechatMediaSupport {

    private static final int DOWNLOAD_ATTEMPTS = 3;

    private WechatMediaSupport() {
    }

    public static String firstText(List<MessageItem> items) {
        for (MessageItem item : items) {
            if (item != null && item.getText_item() != null) {
                String text = trim(item.getText_item().getText());
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    public static MessageItem firstImage(List<MessageItem> items) {
        return items.stream()
                .filter(item -> item != null && item.getImage_item() != null)
                .findFirst()
                .orElse(null);
    }

    public static MessageItem firstVideo(List<MessageItem> items) {
        return items.stream()
                .filter(item -> item != null && item.getVideo_item() != null)
                .findFirst()
                .orElse(null);
    }

    public static MessageItem firstVoice(List<MessageItem> items) {
        return items.stream()
                .filter(item -> item != null && item.getVoice_item() != null)
                .findFirst()
                .orElse(null);
    }

    public static MessageItem firstFile(List<MessageItem> items) {
        return items.stream()
                .filter(item -> item != null && item.getFile_item() != null)
                .findFirst()
                .orElse(null);
    }

    public static long parseFileSize(String value) {
        try {
            return value == null || value.isBlank() ? -1L : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    public static byte[] downloadFileWithIntegrity(
            ILinkClient activeClient,
            MessageItem file,
            long declaredSize,
            String expectedMd5
    ) throws IOException {
        byte[] downloaded = new byte[0];
        String issue = "未知错误";
        for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
            downloaded = activeClient.downloadFileFromMessageItem(file);
            issue = mediaIntegrityIssue(downloaded, declaredSize, expectedMd5);
            if (issue.isBlank()) {
                return downloaded;
            }
            if (attempt < DOWNLOAD_ATTEMPTS) {
                pauseBeforeDownloadRetry(attempt);
            }
        }
        throw new ApiIntegrationException(
                "微信附件下载不完整，已自动重试 " + DOWNLOAD_ATTEMPTS + " 次：" + issue
                        + "。请重新发送该文件。"
        );
    }

    public static String mediaIntegrityIssue(byte[] bytes, long declaredSize, String expectedMd5) {
        if (bytes == null || bytes.length == 0) {
            return "下载内容为空";
        }
        if (declaredSize >= 0 && bytes.length != declaredSize) {
            return "消息标注 " + declaredSize + " 字节，实际下载 " + bytes.length + " 字节";
        }
        if (expectedMd5 != null && !expectedMd5.isBlank()) {
            String actualMd5;
            try {
                actualMd5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("当前 Java 环境不支持 MD5 完整性校验", impossible);
            }
            if (!actualMd5.equalsIgnoreCase(expectedMd5.trim())) {
                return "文件 MD5 校验不一致";
            }
        }
        return "";
    }

    public static String detectImageContentType(byte[] bytes) {
        if (bytes.length >= 8
                && bytes[0] == (byte) 0x89 && bytes[1] == 0x50
                && bytes[2] == 0x4E && bytes[3] == 0x47) {
            return "image/png";
        }
        if (bytes.length >= 3
                && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF) {
            return "image/jpeg";
        }
        if (bytes.length >= 6
                && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return "image/gif";
        }
        if (bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return "image/jpeg";
    }

    public static String mediaDescription(String type, String prompt) {
        String normalizedPrompt = trim(prompt);
        return normalizedPrompt.isBlank()
                ? "[用户发送了" + type + "]"
                : "[用户发送了" + type + "] " + normalizedPrompt;
    }

    private static void pauseBeforeDownloadRetry(int attempt) throws IOException {
        try {
            Thread.sleep(250L * attempt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("微信附件下载重试被中断", exception);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
