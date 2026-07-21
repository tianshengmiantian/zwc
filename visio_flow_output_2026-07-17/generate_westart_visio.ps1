$ErrorActionPreference = 'Stop'

$OutputDirectory = if ($env:WESTART_VISIO_OUTPUT_DIR) {
    $env:WESTART_VISIO_OUTPUT_DIR
} else {
    Split-Path -Parent $MyInvocation.MyCommand.Path
}
$VsdxPath = Join-Path $OutputDirectory 'WeStart全项目运行流程_Visio.vsdx'
$PdfPath = Join-Path $OutputDirectory 'WeStart全项目运行流程_Visio.pdf'
$SvgPath = Join-Path $OutputDirectory 'WeStart全项目运行流程_Visio.svg'

$DesignWidth = 2000.0
$DesignHeight = 1400.0
# A3 landscape: suitable for Visio editing, Word insertion and PDF printing.
$PageWidth = 16.5
$PageHeight = 11.7

function X([double]$Value) { return $Value * $PageWidth / $DesignWidth }
function Y([double]$TopValue) { return $PageHeight - ($TopValue * $PageHeight / $DesignHeight) }

$Colors = @{
    Navy = 'RGB(23,50,77)'
    DarkBlue = 'RGB(31,78,120)'
    Blue = 'RGB(79,129,189)'
    BlueFill = 'RGB(234,242,248)'
    Green = 'RGB(91,155,107)'
    GreenFill = 'RGB(234,245,236)'
    Amber = 'RGB(199,154,43)'
    AmberFill = 'RGB(255,244,214)'
    Gray = 'RGB(104,115,125)'
    GrayFill = 'RGB(242,243,245)'
    Red = 'RGB(201,93,93)'
    RedFill = 'RGB(253,236,236)'
    Teal = 'RGB(60,143,168)'
    TealFill = 'RGB(232,244,248)'
    SectionFill = 'RGB(250,251,252)'
    SectionHead = 'RGB(220,230,241)'
    SectionLine = 'RGB(188,199,209)'
    White = 'RGB(255,255,255)'
}

$Visio = $null
$Document = $null

try {
    $Visio = New-Object -ComObject Visio.Application
    $Visio.Visible = $false
    $Visio.AlertResponse = 7
    $Document = $Visio.Documents.Add('')
    $Page = $Document.Pages.Item(1)
    $Page.Name = 'WeStart 全项目运行流程'
    ($Page.PageSheet.CellsU('PageWidth')).ResultIU = $PageWidth
    ($Page.PageSheet.CellsU('PageHeight')).ResultIU = $PageHeight

    function Set-ShapeStyle {
        param(
            $Shape,
            [string]$Fill,
            [string]$Line,
            [string]$TextColor,
            [double]$FontSize = 10.5,
            [double]$LineWeight = 1.0,
            [bool]$Rounded = $true,
            [int]$Align = 1
        )
        ($Shape.CellsU('FillPattern')).FormulaU = '1'
        ($Shape.CellsU('FillForegnd')).FormulaU = $Fill
        ($Shape.CellsU('LinePattern')).FormulaU = '1'
        ($Shape.CellsU('LineColor')).FormulaU = $Line
        ($Shape.CellsU('LineWeight')).FormulaU = "$LineWeight pt"
        ($Shape.CellsU('Char.Font')).FormulaU = 'FONT("Microsoft YaHei")'
        ($Shape.CellsU('Char.Size')).FormulaU = "$FontSize pt"
        ($Shape.CellsU('Char.Color')).FormulaU = $TextColor
        ($Shape.CellsU('Para.HorzAlign')).FormulaU = "$Align"
        ($Shape.CellsU('VerticalAlign')).FormulaU = '1'
        foreach ($CellName in @('LeftMargin','RightMargin','TopMargin','BottomMargin')) {
            if ($Shape.CellExistsU($CellName, 0)) {
                ($Shape.CellsU($CellName)).FormulaU = '0.05 in'
            }
        }
        if ($Rounded -and $Shape.CellExistsU('Rounding', 0)) {
            ($Shape.CellsU('Rounding')).FormulaU = '0.10 in'
        }
    }

    function Add-Box {
        param(
            [double]$Left,
            [double]$Top,
            [double]$Width,
            [double]$Height,
            [string]$Text,
            [string]$Fill = $Colors.BlueFill,
            [string]$Line = $Colors.Blue,
            [string]$TextColor = $Colors.Navy,
            [double]$FontSize = 10.5,
            [double]$LineWeight = 1.0,
            [bool]$Rounded = $true,
            [int]$Align = 1
        )
        $Shape = $Page.DrawRectangle((X $Left), (Y ($Top + $Height)), (X ($Left + $Width)), (Y $Top))
        $Shape.Text = $Text
        Set-ShapeStyle $Shape $Fill $Line $TextColor $FontSize $LineWeight $Rounded $Align
        return $Shape
    }

    function Add-Label {
        param(
            [double]$Left,
            [double]$Top,
            [double]$Width,
            [double]$Height,
            [string]$Text,
            [double]$FontSize = 10.0,
            [string]$TextColor = $Colors.Navy,
            [int]$Align = 1
        )
        $Shape = $Page.DrawRectangle((X $Left), (Y ($Top + $Height)), (X ($Left + $Width)), (Y $Top))
        $Shape.Text = $Text
        Set-ShapeStyle $Shape $Colors.White $Colors.White $TextColor $FontSize 0 $false $Align
        ($Shape.CellsU('FillPattern')).FormulaU = '0'
        ($Shape.CellsU('LinePattern')).FormulaU = '0'
        return $Shape
    }

    function Add-Section {
        param([double]$Left,[double]$Top,[double]$Width,[double]$Height,[string]$Title)
        $Background = Add-Box $Left $Top $Width $Height '' $Colors.SectionFill $Colors.SectionLine $Colors.Navy 8 0.7 $true 0
        $Header = Add-Box $Left $Top $Width 38 '' $Colors.SectionHead $Colors.SectionHead $Colors.Navy 8 0 $true 0
        $TitleShape = Add-Label ($Left + 18) ($Top + 4) ($Width - 36) 30 $Title 13.5 $Colors.Navy 0
        $Background.SendToBack()
        $Header.SendToBack()
        return @($Background,$Header,$TitleShape)
    }

    function Add-Diamond {
        param([double]$CenterX,[double]$CenterY,[double]$Width,[double]$Height,[string]$Text)
        $Side = [Math]::Min((X $Width), ((Y ($CenterY - $Height/2)) - (Y ($CenterY + $Height/2)))) / [Math]::Sqrt(2)
        $Cx = X $CenterX
        $Cy = Y $CenterY
        $Diamond = $Page.DrawRectangle($Cx - $Side/2, $Cy - $Side/2, $Cx + $Side/2, $Cy + $Side/2)
        ($Diamond.CellsU('Angle')).FormulaU = '45 deg'
        Set-ShapeStyle $Diamond $Colors.AmberFill $Colors.Amber $Colors.Navy 9.5 1.0 $false 1
        $Label = Add-Label ($CenterX - $Width/2) ($CenterY - $Height/2) $Width $Height $Text 9.3 'RGB(91,67,20)' 1
        return @($Diamond,$Label)
    }

    function Add-Arrow {
        param(
            [object[]]$Points,
            [string]$Color = $Colors.Gray,
            [bool]$Dashed = $false,
            [string]$Label = '',
            [double]$LabelX = 0,
            [double]$LabelY = 0,
            [double]$LabelWidth = 140,
            [double]$LabelHeight = 24
        )
        for ($Index = 0; $Index -lt $Points.Count - 1; $Index++) {
            $A = $Points[$Index]
            $B = $Points[$Index + 1]
            $Segment = $Page.DrawLine((X $A[0]), (Y $A[1]), (X $B[0]), (Y $B[1]))
            ($Segment.CellsU('LineColor')).FormulaU = $Color
            ($Segment.CellsU('LineWeight')).FormulaU = '0.9 pt'
            ($Segment.CellsU('LinePattern')).FormulaU = $(if ($Dashed) { '2' } else { '1' })
            if ($Index -eq $Points.Count - 2) {
                ($Segment.CellsU('EndArrow')).FormulaU = '13'
                ($Segment.CellsU('EndArrowSize')).FormulaU = '2'
            }
        }
        if ($Label) {
            Add-Label ($LabelX - $LabelWidth/2) ($LabelY - $LabelHeight/2) $LabelWidth $LabelHeight $Label 8.5 $Color 1 | Out-Null
        }
    }

    # Four formal Visio-style swimlane sections.
    Add-Section 35 82 1930 300 '① 应用启动与多账号初始化' | Out-Null
    Add-Section 35 400 1930 385 '② 运行期入口与消息接收' | Out-Null
    Add-Section 35 803 1930 265 '③ 核心业务服务与结果返回' | Out-Null
    Add-Section 35 1086 1930 270 '④ 数据与外部平台' | Out-Null

    # Startup nodes.
    Add-Box 70 178 170 64 "启动`nWeStart`nApplication" $Colors.DarkBlue 'RGB(22,58,92)' $Colors.White 9.0 1.0 $true 1 | Out-Null
    Add-Box 275 165 245 90 "读取配置与环境变量`nAPI Key / 模型 / 数据库`n微信 iLink 配置" | Out-Null
    Add-Box 555 165 250 90 "Spring Boot 初始化`n扫描 Controller / Service`nRepository / Entity" | Out-Null
    Add-Box 850 105 250 82 "H2 + JPA 初始化`n自动更新数据表" $Colors.GrayFill $Colors.Gray $Colors.Navy 10.3 | Out-Null
    Add-Box 850 230 250 90 "WechatAccountManager`n加载账号定义`n创建独立会话" $Colors.GreenFill $Colors.Green $Colors.Navy 10.3 | Out-Null
    Add-Diamond 1225 275 180 96 "有可恢复`n登录凭据？" | Out-Null
    Add-Box 1370 128 225 80 "构建 ILinkClient`n绑定消息监听器" $Colors.GreenFill $Colors.Green $Colors.Navy 10.2 | Out-Null
    Add-Box 1345 260 250 88 "登录接口 → 申请二维码`n扫码 / 确认 / 验证码" | Out-Null
    Add-Box 1680 128 235 80 "每个账号独立启动`ngetUpdates 长轮询" $Colors.GreenFill $Colors.Green $Colors.Navy 10.2 | Out-Null

    # Runtime nodes.
    Add-Box 70 474 185 70 "HTTP 客户端`n浏览器 / 测试工具" | Out-Null
    Add-Box 305 455 285 110 "Controller 层`n/api/ai · /api/weather`n/api/status · /api/wechat`n参数与权限校验" | Out-Null
    Add-Box 70 645 185 64 '微信用户' | Out-Null
    Add-Box 305 632 285 90 "腾讯微信 iLink`n接收消息 / 下载媒体`n发送回复" $Colors.RedFill $Colors.Red $Colors.Navy 10.2 | Out-Null
    Add-Box 650 620 270 110 "WechatBotService`n账号对应的独立会话`n消息进入线程池处理" $Colors.GreenFill $Colors.Green $Colors.Navy 10.2 | Out-Null
    Add-Diamond 1040 675 190 100 "accountId + messageId`n首次处理？" | Out-Null
    Add-Box 950 735 180 36 '否：忽略重复消息' $Colors.TealFill $Colors.Teal $Colors.Navy 8.8 | Out-Null
    Add-Box 1190 605 320 140 "识别消息类型与意图`n文本 / 语音转写`n图片 / 视频`n天气 / 清空记忆" | Out-Null
    Add-Box 1580 490 325 205 "业务分流`n普通文本与对话记忆`n图片 / 视频多模态识别`n地点天气查询`n账号登录管理`n接口状态检查" | Out-Null

    # Core services.
    Add-Box 65 875 360 145 "ConversationService`n按 conversationId 加锁`n读取最近 12 条上下文`n保存 user + assistant`n最多保留 200 条 / 可清空" $Colors.GreenFill $Colors.Green $Colors.Navy 9.7 | Out-Null
    Add-Box 455 875 330 145 "BailianService`nqwen-flash：文字问答`nqwen3-vl-flash：图片/视频`n统一返回 AiResult" $Colors.GreenFill $Colors.Green $Colors.Navy 9.7 | Out-Null
    Add-Box 815 875 300 145 "QWeatherService`n地点解析`n查询当前天气`n返回 CurrentWeather" $Colors.GreenFill $Colors.Green $Colors.Navy 9.7 | Out-Null
    Add-Box 1145 875 390 145 "账号与登录服务`n创建 / 查询 / 删除账号`n二维码 / 验证 / 断开`n每个账号独立 WechatBotService" $Colors.GreenFill $Colors.Green $Colors.Navy 9.7 | Out-Null
    Add-Box 1615 875 300 145 "结果封装与发送`nHTTP：JSON`n微信：文本回复`n超过 1800 字分段" $Colors.TealFill $Colors.Teal $Colors.Navy 9.7 | Out-Null

    # Data and external platforms.
    Add-Box 65 1140 500 155 "H2 持久化数据库`nCONVERSATION_MESSAGES`nWECHAT_ACCOUNTS`nWECHAT_ACCOUNT_PROCESSED_MESSAGES" $Colors.GrayFill $Colors.Gray 'RGB(41,50,58)' 9.3 1.0 $true 1 | Out-Null
    Add-Box 635 1140 330 125 "阿里云百炼 DashScope`n文字模型 qwen-flash`n视觉模型 qwen3-vl-flash" $Colors.RedFill $Colors.Red $Colors.Navy 9.7 | Out-Null
    Add-Box 1035 1140 330 125 "和风天气 API`nGeoAPI：地点 → locationId`n实时天气 API：now" $Colors.RedFill $Colors.Red $Colors.Navy 9.7 | Out-Null
    Add-Box 1435 1140 480 125 "腾讯微信 iLink`n二维码登录 / getUpdates`n媒体下载 / 文本回复" $Colors.RedFill $Colors.Red $Colors.Navy 9.7 | Out-Null
    Add-Box 635 1282 1280 50 '上下文隔离：HTTP = api:<id>；微信 = wechat:<accountId>:<fromUserId>。一个账号可服务多名用户，多账号之间也不会共用对话记忆。' $Colors.GrayFill $Colors.Gray $Colors.Navy 8.5 0.8 $true 1 | Out-Null

    # Startup arrows.
    Add-Arrow @(@(240,210),@(275,210))
    Add-Arrow @(@(520,210),@(555,210))
    Add-Arrow @(@(805,210),@(825,210),@(825,146),@(850,146))
    Add-Arrow @(@(805,210),@(825,210),@(825,275),@(850,275))
    Add-Arrow @(@(1100,275),@(1135,275))
    Add-Arrow @(@(1315,257),@(1340,257),@(1340,168),@(1370,168)) $Colors.Gray $false '是' 1332 220 40 20
    Add-Arrow @(@(1225,323),@(1225,333),@(1345,333)) $Colors.Gray $false '否' 1280 326 40 20
    Add-Arrow @(@(1595,304),@(1625,304),@(1625,168),@(1595,168))
    Add-Arrow @(@(1595,168),@(1680,168))

    # Runtime arrows.
    Add-Arrow @(@(255,509),@(305,509))
    Add-Arrow @(@(590,509),@(1580,509))
    Add-Arrow @(@(255,677),@(305,677))
    Add-Arrow @(@(590,677),@(650,677))
    Add-Arrow @(@(920,675),@(945,675))
    Add-Arrow @(@(1040,725),@(1040,735)) $Colors.Gray $false '否' 1065 730 40 20
    Add-Arrow @(@(1135,675),@(1190,675)) $Colors.Gray $false '是' 1162 664 40 20
    Add-Arrow @(@(1510,675),@(1545,675),@(1545,610),@(1580,610))
    Add-Arrow @(@(1798,208),@(1798,390),@(785,390),@(785,620)) $Colors.Green $false '轮询得到新消息' 1260 392 170 22

    # Business routing and service calls.
    Add-Arrow @(@(1742,695),@(1742,846),@(245,846),@(245,875)) $Colors.Blue
    Add-Arrow @(@(1742,695),@(1742,853),@(620,853),@(620,875)) $Colors.Blue
    Add-Arrow @(@(1742,695),@(1742,860),@(965,860),@(965,875)) $Colors.Blue
    Add-Arrow @(@(1742,695),@(1742,867),@(1340,867),@(1340,875)) $Colors.Blue
    Add-Arrow @(@(425,947),@(455,947)) $Colors.Green $false '调用文字模型' 440 934 110 20
    Add-Arrow @(@(245,1020),@(245,1035),@(1580,1035),@(1580,947),@(1615,947)) $Colors.Teal
    Add-Arrow @(@(620,1020),@(620,1043),@(1588,1043),@(1588,955),@(1615,955)) $Colors.Teal
    Add-Arrow @(@(965,1020),@(965,1051),@(1596,1051),@(1596,963),@(1615,963)) $Colors.Teal
    Add-Arrow @(@(1340,1020),@(1340,1059),@(1604,1059),@(1604,971),@(1615,971)) $Colors.Teal

    # Persistence and external API arrows.
    Add-Arrow @(@(245,1020),@(245,1115),@(315,1115),@(315,1140)) $Colors.Gray $false '读取 / 保存对话' 390 1110 140 22
    Add-Arrow @(@(1040,725),@(1040,780),@(440,780),@(440,1128),@(500,1128),@(500,1140)) $Colors.Gray $true '消息去重记录' 470 1120 110 22
    Add-Arrow @(@(1340,1020),@(1340,1090),@(520,1090),@(520,1140)) $Colors.Gray $false '保存账号定义' 1025 1086 130 22
    Add-Arrow @(@(620,1020),@(620,1100),@(800,1100),@(800,1140)) $Colors.Red $false 'HTTPS' 710 1096 70 20
    Add-Arrow @(@(965,1020),@(965,1110),@(1200,1110),@(1200,1140)) $Colors.Red $false 'HTTPS' 1080 1106 70 20
    Add-Arrow @(@(1340,1020),@(1340,1120),@(1675,1120),@(1675,1140)) $Colors.Red $false '登录 / 会话' 1510 1116 105 20
    Add-Arrow @(@(1765,1020),@(1765,1140)) $Colors.Red $false '微信回复' 1810 1080 90 20

    # Title and legend are native text/shapes too.
    Add-Label 300 14 1400 48 'WeStart 全项目运行流程' 20 $Colors.DarkBlue 1 | Out-Null
    Add-Label 420 58 1160 28 '启动、多账号微信 iLink、AI 多模态、天气、对话记忆与持久化' 10.5 $Colors.Gray 1 | Out-Null

    $LegendItems = @(
        @(70,$Colors.BlueFill,$Colors.Blue,'应用流程'),
        @(235,$Colors.GreenFill,$Colors.Green,'项目服务'),
        @(400,$Colors.GrayFill,$Colors.Gray,'数据库'),
        @(565,$Colors.RedFill,$Colors.Red,'外部平台'),
        @(730,$Colors.TealFill,$Colors.Teal,'返回结果')
    )
    foreach ($Item in $LegendItems) {
        Add-Box $Item[0] 1368 28 16 '' $Item[1] $Item[2] $Colors.Navy 7 0.6 $false 1 | Out-Null
        Add-Label ($Item[0] + 34) 1363 105 26 $Item[3] 8.2 $Colors.Gray 0 | Out-Null
    }

    # Add document metadata and save in native Visio format.
    $Document.Title = 'WeStart 全项目运行流程'
    $Document.Subject = 'Spring Boot、百炼、和风天气、微信 iLink 与 H2 数据流'
    $Document.Creator = 'Codex'
    $Document.SaveAs($VsdxPath)

    # Native Visio vector exports for Word/printing and quick preview.
    $Missing = [System.Reflection.Missing]::Value
    $Document.ExportAsFixedFormat(1, $PdfPath, 1, 0, 1, 1, $false, $true, $true, $true, $false, $Missing)
    $Page.Export($SvgPath)

    Write-Output $VsdxPath
    Write-Output $PdfPath
    Write-Output $SvgPath
}
finally {
    if ($Document -ne $null) {
        try { $Document.Close() } catch { }
    }
    if ($Visio -ne $null) {
        try { $Visio.Quit() } catch { }
    }
    [System.GC]::Collect()
    [System.GC]::WaitForPendingFinalizers()
}
