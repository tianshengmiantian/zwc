# 微信 iLink 接入说明

本项目使用第三方 Java SDK `io.github.lith0924:wechat-ilink-sdk:2.3.3` 对接微信 iLink Bot 协议。
它不是腾讯官方发布的 Java SDK。腾讯官方公开实现是 OpenClaw 微信插件，因此上线前应自行评估第三方依赖和协议变化风险。

## 第一次扫码登录

最简单的方式：先运行 `WestartApplication`，然后双击项目根目录下的 `微信扫码登录.cmd`。窗口会列出已有机器人账号；输入账号前面的编号即可打开对应二维码，输入 `N` 可以创建新账号并立即打开它的二维码。

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

## 多微信机器人账号

系统默认最多管理 3 个机器人账号（包含 `default` 默认账号），账号定义保存在数据库中。

普通使用不需要修改任何终端命令：

1. 运行 `WestartApplication`。
2. 双击项目根目录中的 `微信扫码登录.cmd`。
3. 输入 `N`，再输入第二个机器人的显示名称；账号编号会自动生成为 `bot-2`。
4. 浏览器会自动打开该账号的新二维码，使用另一个微信账号扫码。
5. 以后再次双击同一个文件，输入列表中的账号编号即可选择对应机器人。

下面的 PowerShell 命令只是可选的手动管理方式，并非正常扫码登录所必需。手动创建第二个账号：

```powershell
$body = @{
  accountId = "bot-2"
  displayName = "微信机器人二号"
} | ConvertTo-Json

Invoke-RestMethod -Method Post `
  -ContentType "application/json" `
  -Body $body `
  http://localhost:8080/api/wechat/accounts
```

创建完成后，也可以在项目终端运行下面的命令直接登录指定账号：

```powershell
.\微信扫码登录.cmd bot-2
```

查看全部账号和各自的登录状态：

```powershell
Invoke-RestMethod http://localhost:8080/api/wechat/accounts
```

每个账号拥有独立的二维码、iLink 连接、消息轮询和登录状态。聊天记忆使用“机器人账号 + 微信用户”共同隔离，同一个用户向两个机器人提问时不会混用历史。

需要更多账号时可设置环境变量后重启：

```powershell
setx WECHAT_BOT_MAX_ACCOUNTS "10"
```

非默认账号的扫码令牌只保存在当前进程内存中，应用重启后账号定义仍然存在，但需要分别重新扫码。

## 已支持的微信消息

- 普通文字：先由百炼 Function Calling 判断是普通聊天还是需要执行工具。
- 天气查询：调用和风天气；没有地点时使用北京。也支持“用语音告诉我北京天气”。
- 绘图指令：调用 `qwen-image-2.0` 真正生成图片，再通过 iLink 发送图片。
- 语音回复指令：调用百炼 TTS 生成 MP3，优先转换成 SILK 并发送微信原生语音；转换失败时自动发送 MP3 文件。
- 图片：下载并解密后调用百炼视觉模型。
- 视频：下载并解密后调用百炼视觉模型。
- 微信语音和音频文件：识别 M4A、MP3、WAV、AMR、OGG、Opus、AAC、FLAC 及 SILK，转成文字后继续使用同一套聊天记忆和工具调度。

天气、绘图和语音回复只允许调用代码中登记的白名单工具。每条用户消息最多执行一个外部动作，避免误判导致重复生成图片或重复产生语音费用。

## 对话记忆与数据库

- 每个微信用户使用独立的对话记忆，用户之间不会共享历史。
- 默认把最近 12 条消息发送给百炼作为上下文，数据库最多保存每个用户最近 200 条消息。
- 发送“清空对话”“清除聊天”“重置对话”或“重新开始”可以删除当前用户的历史记录。
- 已处理的微信消息 ID 会保存 7 天，应用重启后仍可防止重复回复。
- 数据保存在项目根目录的 `data` 目录中，该目录已加入 `.gitignore`，不会提交到 Git。

通过 `/api/ai/text` 调试文字上下文时，可以传入固定的 `conversationId`：

```json
{
  "prompt": "我叫小明，请记住",
  "conversationId": "demo-user"
}
```

使用相同 `conversationId` 的后续请求会带上最近对话。清空接口为 `POST /api/ai/conversation/clear`，请求体为：

```json
{
  "conversationId": "demo-user"
}
```

## 安全配置

API Key 继续只通过以下环境变量传入：

```text
DASHSCOPE_API_KEY
QWEATHER_API_KEY
QWEATHER_API_HOST
```

语音识别、图片生成、文本聊天、Function Calling 和语音合成都复用 `DASHSCOPE_API_KEY`。TTS 默认使用百炼北京地域的 `qwen-audio-3.0-tts-flash` 与 `longanlingxi` 音色，无需新增密钥。

扫码登录得到的 iLink 登录令牌只保存在当前进程内存中，不会写入代码、配置文件或日志。应用重启后默认需要重新扫码。

如需在非本机访问扫码接口，必须另外配置 `WECHAT_BOT_ADMIN_KEY`，并在请求头中发送 `X-WeStart-Admin-Key`。不要把该值提交到 Git。

可选环境变量：

```text
WECHAT_DEFAULT_LOCATION=北京
WECHAT_BOT_ENABLED=true
WECHAT_BOT_MAX_ACCOUNTS=3
FFMPEG_PATH=ffmpeg
DASHSCOPE_TTS_MODEL=qwen-audio-3.0-tts-flash
DASHSCOPE_TTS_VOICE=longanlingxi
```

`FFMPEG_PATH` 未设置时会直接使用系统 PATH 中的 `ffmpeg`。如果 FFmpeg 不可用，机器人仍会合成语音，但会退回发送 MP3 文件，而不是微信原生语音气泡。

如果你已经通过安全方式保存了 iLink 会话凭据，也可使用以下环境变量在重启后恢复登录；不要把真实值写入项目文件：

```text
ILINK_BOT_TOKEN
ILINK_USER_ID
ILINK_BOT_ID
ILINK_BASE_URL=https://ilinkai.weixin.qq.com
```
