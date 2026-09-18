# Kith · 安卓端

[![桌面端](https://img.shields.io/badge/桌面端-kith--desktop-2f81f7)](https://github.com/wan101218/kith-desktop)
[![平台](https://img.shields.io/badge/platform-Android%2013%2B-3ddc84)](https://github.com/wan101218/kith-android)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7f52ff)](https://github.com/wan101218/kith-android)
[![许可](https://img.shields.io/badge/license-MIT-green)](./LICENSE)

造一个社会，看它自己活起来。

你设定世界观和几个初始人物，剩下的交给旁白引擎和人物智能体。他们会自己结盟、翻脸、谈恋爱、发图、转账，而你只是被拉进群的那个普通人。你可以在群里说话，也可以退到上帝视角，直接以某个 NPC 的身份去跟别人聊。

这是 Android 手机端。**Windows 桌面端在另一个仓库，两边存档互通：**

> 👉 **https://github.com/\<your-username\>/kith-desktop**

---

## 它在跑什么

旁白引擎推进一次剧情，可能创造新 NPC、改写关系、埋下伏笔。新 NPC 的关系对齐做了三级解析（id → 姓名 → 接不上就丢弃并记日志），宁可少几条关系，也不会生成一堆连不上的孤儿节点。

人物智能体各自独立。每个角色有人格卡，15 种内置关系标签（恋人、暧昧、宿敌、债主……）驱动语气和行为。他们之间的消息是闭塞的：你在 A 面前说的话，B 不知道。

角色输出支持 Human Chat Protocol，可以夹带表情包、AI 生成的图片、转账三种东西。流式输出时半截标签会被截断保护，不会把半个 `<sticker` 吐到屏幕上。转账是模拟的，没有真实资金流动。

内置目录快照有 256 个模型、42 家厂商接入点预设。配好几个接入点之后，旁白会按价格分位自动分档挑模型：写剧情这种费脑子的事交给强的，日常闲聊用便宜的。

端侧还跑着一个内容审核引擎（Qwen3Guard 0.6B，GGUF 量化），模型推理全部在手机上完成，不上传任何内容。它只在首次开启时下载一次模型，不用可以一直不开。

---

## 装上就能用

从 Releases 下载 APK 装上即可（Android 13 及以上）。装好之后还差一步——配 API Key，见下一节。

### 从源码构建

Android Studio 打开项目根目录，直接 Run。或者命令行：

```bash
./gradlew assembleDebug     # 产物 app/build/outputs/apk/debug/
./gradlew assembleRelease   # 需要自行配置签名
```

环境要求：JDK 17+、Android Gradle Plugin 与 Kotlin 版本见 `gradle/libs.versions.toml`。`compileSdk 36`，`minSdk 33`。

---

## 配置 API Key

应用本身不提供任何模型服务，也不内置任何 Key，全部由你填自己的。设置页路径：「设置 → 模型接入点 → ＋ 添加」，选好厂商会自动带出地址，粘 Key 保存即可。

三类能力分开配，缺哪样就少哪样功能，不影响其他部分。

### 1. 对话模型（旁白和角色说话靠它）

建议配 **2 到 3 个**，档次拉开。旁白引擎按价格分位自动分档：重要剧情用强模型，日常对白用便宜模型。只配一个也能跑，只是分档没得挑，全都走同一个。

几家可以直接用的（模型名和价格以厂商当期公告为准）：

| 厂商 | 接入地址 | 说明 |
| --- | --- | --- |
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat`，便宜量大，适合当主力档 |
| 智谱开放平台 | `https://open.bigmodel.cn/api/paas/v4` | 有免费档（如 GLM-4.5-Flash），适合当便宜档 |
| 硅基流动 SiliconFlow | `https://api.siliconflow.cn/v1` | 有免费额度的小模型（Qwen2.5-7B-Instruct 一类） |

一个务实的组合：DeepSeek 当中档主力，智谱免费档跑闲聊，再加一个贵一点的模型专供旁白。

配好之后到社会页给旁白和角色默认模型分别指定，也可以打开「自动分配」，让应用自己按价格挑。

### 2. 视觉桥接（让不会看图的模型也能看懂你发的图）

推荐用 **智谱 GLM-4V-Flash，免费**：

- 接入地址：`https://open.bigmodel.cn/api/paas/v4`
- 模型名：`glm-4v-flash`
- Key：智谱开放平台的 Key（和上面对话模型可以共用同一个）

开启后你发一张图，应用先让视觉模型把图转成文字描述，再把描述交给对话模型。这样纯文本模型也能"看见"图片，角色会对着你发的照片说话。不配也能正常聊天，只是对方看不懂你发的图。

### 3. 生图（角色发图片消息）

内置了云舟生图接入点的厂商和地址（`https://cli.999554.xyz/v1`，`gpt-image-2` 通道），填上你自己的 Key 就能出图。生图返回的图床链接会被自动转存到社会媒体目录，跟存档一起走。

也可以用任何 OpenAI 兼容的生图接口，或智谱 CogView 一类。设置 → 模型接入点 → 类型选 IMAGE。

### 端侧审核模型（可选）

设置里打开内容审核后，会下载 Qwen3Guard-Gen-0.6B 的 GGUF 量化版本（Q4_K_M 约 400 MB，Q8_0 约 600 MB），走 hf-mirror 镜像，HuggingFace 作为备用源。下载一次就够，之后推理全在本地跑。嫌大可以不开，不影响其他功能。

### Key 存在哪

只写在本机应用的私有数据目录里。不进仓库，不随 `*.kith.json` 存档导出，也不会上传到任何地方。

---

## 和电脑端互通

两种方式，都不需要云端：

1. **存档互导**。手机上「导出存档」得到一个 `*.kith.json`，拷到电脑后「导入」即可；反过来一样。
2. **局域网直传**。电脑端开着 Kith，手机和电脑连同一个 Wi-Fi，手机浏览器访问电脑端首页显示的地址（形如 `http://192.168.x.x:19287/sync`），可以直接下载电脑上的社会，也可以把手机存档上传过去。

### 存档格式（两端同一份约定）

`*.kith.json`，schema `kith.society/1`。两端都按这份约定读写：

```jsonc
{
  "schema": "kith.society/1",
  "coverImageB64": "<裸 base64，不含 data: 前缀>",   // 封面随档走，顶层字段
  "society": {
    "coverImage": "img_xxx.png",                     // media 目录下的裸文件名
    "narratorModel": { "endpointId": "...", "modelId": "...", "label": "...", "vendor": "..." }
  },
  "characters": [], "relations": [], "plot": {}, "stickers": [], "chats": []
}
```

导出端把封面文件读成裸 base64 塞进顶层 `coverImageB64`；导入端解码后写进自己的 media 目录，并把文件名记到 `society.coverImage`。`coverImage` 本身是本地引用，不随档传播；如果是 http 或 data 开头的远程引用则直传。

---

## 目录结构

```
app/src/main/java/com/kith/app/
  ai/        LlmClient、ImageGenClient、VisionBridge、protocol（HC 协议）、
             engine（旁白引擎、人物智能体）、prompt、guard（端侧审核）
  data/      存储 SocietyStore、设置 AppSettings、模型目录 catalog、贴纸
  domain/    数据类 Models 与存档编解码 Codecs
  ui/        Jetpack Compose 界面：home / society / chat / character /
             graph（关系图）/ settings / library / log / create
  core/      AppGraph 依赖图与公共工具
app/src/main/assets/  model_catalog.json（目录快照）
docs/                 PRD、架构说明、品牌资源、测试数据
```

---

## 几个需要知道的事

只支持 Android 13（API 33）以上。MediaPipe tasks-genai 跑 0.6B 模型需要较好的芯片，实测在 Snapdragon 8 Elite（15 GB RAM）上可用，更老的机器建议不开端侧审核。

联网只用于调用你自己配置的模型接口：应用会申请 `INTERNET` 和 `ACCESS_NETWORK_STATE`，除此之外不收集任何数据，没有埋点。

目录里 Ollama 源模型的 vendor 标的是原厂（阿里、DeepSeek 等），给 Ollama 接入点过滤模型会显示 0 条。这是上游数据源的写法。

桌面端的规则体检（空输出、过短、重复、标签未闭合）在这里是端侧审核的补充，两者不冲突。

---

## 许可

MIT。见 [LICENSE](./LICENSE)。

第三方资源许可各不相同：模型目录快照来自 OpenRouter；端侧审核模型是 Qwen3Guard-Gen-0.6B 的社区 GGUF 量化版本（mradermacher 制作，Apache-2.0）；内置云舟接入点只是预置地址，Key 需要你自己申请。模型调用产生的费用由你自己的账号承担。
