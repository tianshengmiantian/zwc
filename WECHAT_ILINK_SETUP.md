# 微信 iLink 接入说明

本项目使用第三方 Java SDK `io.github.lith0924:wechat-ilink-sdk:2.3.3` 对接微信 iLink Bot 协议。
它不是腾讯官方发布的 Java SDK。腾讯官方公开实现是 OpenClaw 微信插件，因此上线前应自行评估第三方依赖和协议变化风险。

## 第一次扫码登录

最简单的方式：先运行 `WestartApplication`，然后双击项目根目录下的 `wechat-login.cmd`（中文入口 `微信扫码登录.cmd` 也可以）。它会自动打开二维码、检查登录状态，并在需要时提示输入手机显示的配对数字。

下面是手动操作方式：

1. 在 IntelliJ IDEA 中运行 `WestartApplication`。
2. 使用终端启动登录：

   ```powershell
   Invoke-RestMethod -Method Post http://localhost:8080/api/wechat/login
   ```

3. 浏览器打开：

   ```text
   http://localhost:8080/api/wechat/login/qrcode
   ```

4. 使用微信扫码。扫码后查看状态：

   ```text
   http://localhost:8080/api/wechat/status
   ```

当 `loggedIn` 为 `true` 时，即可在微信里向机器人发送消息。

如果状态中的 `verificationRequired` 为 `true`，说明手机微信正在显示配对数字。将手机上的数字提交给本机接口：

```powershell
Invoke-RestMethod -Method Post `
  -ContentType "application/json" `
  -Body '{"code":"替换成手机显示的数字"}' `
  http://localhost:8080/api/wechat/login/verify
```

二维码过期时程序最多自动刷新三次。刷新浏览器中的二维码页面即可看到新二维码；不要继续扫描浏览器里已经显示为过期的旧图。

## 已支持的微信消息

- 普通文字：调用百炼文本模型。
- 包含“天气 / 气温 / 温度”的文字：调用和风天气；没有地点时使用北京。
- 图片：下载并解密后调用百炼视觉模型。
- 视频：下载并解密后调用百炼视觉模型。
- 语音：微信提供转写文字时按文字处理。

## 安全配置

API Key 继续只通过以下环境变量传入：

```text
DASHSCOPE_API_KEY
QWEATHER_API_KEY
QWEATHER_API_HOST
```

扫码登录得到的 iLink 登录令牌只保存在当前进程内存中，不会写入代码、配置文件或日志。应用重启后默认需要重新扫码。

如需在非本机访问扫码接口，必须另外配置 `WECHAT_BOT_ADMIN_KEY`，并在请求头中发送 `X-WeStart-Admin-Key`。不要把该值提交到 Git。

可选环境变量：

```text
WECHAT_DEFAULT_LOCATION=北京
WECHAT_BOT_ENABLED=true
```

如果你已经通过安全方式保存了 iLink 会话凭据，也可使用以下环境变量在重启后恢复登录；不要把真实值写入项目文件：

```text
ILINK_BOT_TOKEN
ILINK_USER_ID
ILINK_BOT_ID
ILINK_BASE_URL=https://ilinkai.weixin.qq.com
```
