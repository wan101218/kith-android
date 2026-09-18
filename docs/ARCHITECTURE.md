# Kith · 架构

## 技术选型

| 项 | 值 | 理由 |
|---|---|---|
| 语言 / UI | Kotlin + Jetpack Compose (Material 3) | 与同目录下的 AIfanfiction / WANAIChat 保持一致 |
| minSdk / targetSdk | 33 / 36 | 33 起 `Modifier.blur` 可用，才能做真实毛玻璃状态栏 |
| 构建 | AGP 9.0.0 · Gradle 9.2.1 · Kotlin 2.0.21 · Compose BOM 2024.09.00 | 全部使用本机 Gradle 缓存中已有版本，保证离线可构建 |
| 网络 | OkHttp 4.12.0 | 只此一个网络依赖 |
| JSON | **Android 内置 `org.json`** | 见下方说明 |
| 图片 | Coil 2.5.0 | 头像、聊天图片、表情包 |
| 导航 | navigation-compose 2.8.5 | 六个目的地 |

### 两个刻意的「不用」

**不用 kotlinx.serialization。**
它的编译器插件版本必须与 Kotlin 版本**严格一致**，在多工程并存的环境里
（本机同时有 2.0.20 / 2.0.21 / 2.2.10 / 2.3.10 的 Kotlin 插件）极易因版本错配
导致构建失败。`org.json` 是 Android 平台内置的，零依赖、零风险，且完全够用 ——
模型目录 232KB、OpenAI 兼容请求/响应、社会存档、JSONL 日志，全部覆盖。

实测代价可控：`domain/Codecs.kt` 里 300 行手写编解码，换来的是
「存档可读、可手改、导入别人分享的社会时缺字段不崩、多字段忽略」。

**不用 Ktor。**
本机缓存里只有 Ktor 3.0.0 的 `-jvm` 变体，Android 端解析存在变体匹配风险。
OkHttp 足够，且 SSE 流式可以手动解析（见下），反而获得了完整的取消控制权。

**不用 Hilt / Koin。**
依赖图是扁平单向的（settings → catalog → store → engines），没有需要动态注入的场景。
`AppGraph.kt` 30 行手写容器，少一层注解处理器，构建更快、链路更易读。

---

## 模块结构

```
com.kith.app
├── KithApp / AppGraph          应用入口与依赖容器
├── core/
│   ├── Js.kt                   org.json 安全访问扩展、JSON 块提取、容错解析
│   └── Ids.kt                  短 id 生成、稳定哈希、时间格式化、种子随机
├── domain/
│   ├── Models.kt               全部领域模型与枚举
│   ├── Codecs.kt               模型 ↔ JSON 编解码
│   └── Templates.kt            BL/GL/BG/BZ 剧情模板、标签模板、题材建议
├── data/
│   ├── store/SocietyStore.kt   ★ 按社会隔离的文件存储 + 导入导出
│   ├── catalog/
│   │   ├── ModelCatalog.kt     内置快照 / 在线刷新 / 价格估算 / 档位挑选
│   │   ├── VendorRegistry.kt   厂商品牌色、徽记、默认 baseUrl、别名归一化
│   │   └── ImageModelPresets.kt 文生图模型预设（人工维护）
│   ├── settings/AppSettings.kt 跨社会共享的设置（SharedPreferences）
│   └── sticker/StickerLibrary.kt 表情包三级匹配 + 系统 emoji 表
├── ai/
│   ├── Http.kt                 OkHttp 封装 + SSE 流式读取
│   ├── LlmClient.kt            OpenAI 兼容对话客户端（流式/非流式/多模态）
│   ├── VisionBridge.kt         GLM-4V 视觉桥接
│   ├── ImageGenClient.kt       文生图（三种接口形态适配）
│   ├── protocol/ChatProtocol.kt ★ Human Chat Protocol 解析器
│   ├── prompt/Prompts.kt       ★ 人格规范 + 协议规则 + 旁白/角色 prompt 构造
│   └── engine/
│       ├── ImportanceScorer.kt 重要性融合打分
│       ├── NarratorEngine.kt   ★ 旁白引擎 + ModelResolver
│       └── CharacterAgent.kt   ★ 人物智能体 + token 估算
└── ui/
    ├── theme/                  Color / Type / Theme / 语义扩展色
    ├── common/Common.kt        厂商图标、人物头像、毛玻璃顶栏、通用小件
    ├── graph/RelationGraph.kt  ★ 树状 / 链式布局 + Canvas 连线 + 可点击节点
    ├── nav/KithNav.kt          导航图
    ├── home/                   社会列表（导入 / 导出 / 删除）
    ├── create/                 创建社会
    ├── society/                关系图主界面 + 旁白面板 + 人物详情 + 社会模型
    ├── character/              人物编辑器
    ├── chat/                   会话 + 协议渲染
    ├── log/                    日志
    └── settings/               设置 + 模型选择器 + 接入点编辑
```

---

## 存储布局

**每个社会一个独立文件夹，物理隔离。**

```
filesDir/societies/
  index.json                     社会索引（列表页只读这个，不扫全部目录）
  {societyId}/
    society.json                 名称 / 世界观 / 走向 / 题材 / 三个模型配置
    characters.json              人物
    relations.json               关系链
    plot.json                    当前剧情状态与已发生的剧情节点
    chats/{characterId}.json     与每个角色的独立会话（彼此闭塞）
    logs.jsonl                   追加式全量日志
    stickers.json                本社会表情包库
    media/                       头像、聊天图片、表情包文件
```

### 为什么不是一个数据库

产品硬性要求社会之间完全隔离。物理隔离是最不容易出错的实现：

1. 导出/导入天然就是「打包/解包一个目录」，不需要写迁移逻辑
2. 单个社会损坏不会波及其他社会
3. 用户可以直接在文件管理器里备份某个社会

### 写入策略

所有写入走 `writeAtomic()`：先写 `.tmp` 再 `rename`。
对用户来说，丢失一个精心配置的社会是不可接受的，不能因为写到一半被系统杀掉
就留下半截存档。

### 导入的 id 重映射

导入存档时**总是分配新的 `societyId`**，并同步重映射所有内部引用
（人物 id、关系端点、会话归属、日志里的 actor）。这样：

- 重复导入同一个存档不会互相踩
- 可以把别人分享的社会安全地并进自己的列表

---

## AI 层设计

### 统一走 OpenAI 兼容协议

要支持几十家厂商，唯有 OpenAI 兼容协议是最大公约数
（国内 DeepSeek / 智谱 / 通义 / 月之暗面 / 豆包方舟，国外 OpenAI / Groq /
Together / OpenRouter / xAI 全部兼容）。

少量非兼容服务（Stability、BFL、Ideogram 等）只用于生图，走 `ImageGenClient` 的适配分支。

### SSE 手动解析

没用 `okhttp-sse`，而是自己从 response body 逐行读：

```kotlin
while (!source.exhausted()) {
    val line = source.readUtf8Line() ?: break
    if (!line.startsWith("data:")) continue
    ...
}
```

原因：可以完全控制增量字段的解析（`delta.content` vs `responses` 格式）、
`[DONE]` 哨兵的过滤、以及流内错误（HTTP 200 但 body 里带 `error`）的识别。
另外**刻意不发送 `stream_options.include_usage`** —— 部分厂商（如通义兼容模式）
会因为这个不认识的字段直接返回 400。改为「有 usage 就用、没有就本地估算」。

### token 估算

流式下部分厂商不返回 usage，总不能把花费统计永远显示成 0。
中文与拉丁文的字符/词元比差得很远，所以分开算：

```
中日韩字符 ≈ 0.7 token/字
其余字符   ≈ 0.25 token/字
```

### 看图能力的透明补齐

```
用户发图
  → 角色的模型支持读图？ → 直接把图塞进多模态消息
  → 不支持？            → 先调 GLM-4V 拿到文字描述
                          → 以「[对方发来一张图片…]」形式注入对话
  → 桥接也关着？        → 明确告诉模型「你看不到这张图，不要编造内容」
```

关键点是**对用户完全透明**：无论选哪个模型，发图都有人接得住。
最后那条分支也很重要 —— 宁可让模型诚实地说没看清，也不能让它编造图片内容。

### 厂商鉴权差异

显式建模成 `AuthStyle` 枚举而不是散落 if-else：

| 厂商 | 头 |
|---|---|
| 绝大多数 | `Authorization: Bearer xxx` |
| Anthropic | `x-api-key` + `anthropic-version` |
| Google 原生 | `x-goog-api-key` |

---

## 关系图渲染

结构上分两层：

1. **底层 Canvas** 画连线（二次贝塞尔，轻微弯曲避免多条线重合时糊成一团）
2. **上层 Compose 组件** 放头像

之所以不全部画在 Canvas 里：头像需要支持真实的点击、长按与无障碍语义，
自己实现命中测试既麻烦又不准。

连线细节：
- 从**圆周到圆周**而不是圆心到圆心，避免线穿过头像
- 线宽由亲密度映射（1.2dp → 4.2dp）
- 颜色由关系类型决定（恋人=玫瑰、亲属=暖橙、朋友=青绿、对立=灰）
- 对称关系弯曲更多，非对称关系（上司→下属）弯曲更少，暗示方向性

内容超出视口时允许双向滚动，否则自动居中。

---

## 数据同步

| 数据 | 存储 | 位置 |
|---|---|---|
| 接入点与密钥 | SharedPreferences | 跨社会共享 |
| 已保存的模型配置 | SharedPreferences | 跨社会共享 |
| 视觉桥接 / 玩法 / 界面偏好 | SharedPreferences | 跨社会共享 |
| 模型目录刷新缓存 | `filesDir/catalog/model_catalog.json` | 全局，覆盖 assets |
| 世界观 / 人物 / 关系 / 剧情 / 会话 / 日志 / 表情包 | 文件 | **每个社会独立** |

`AppSettings` 与 `ModelCatalog` 通过 `StateFlow` 暴露，UI 用
`collectAsStateWithLifecycle()` 订阅；社会数据由各 ViewModel 持有并显式 `reload()`。

---

## 已知取舍与后续

1. **日志读取是全量的** —— `readLogs` 读整个 `logs.jsonl` 再取末尾 N 行。
   长期游玩后文件会变大，应改为从文件尾部反向读固定字节数。
2. **`VENDOR_NATIVE` 生图未实现** —— Stability / BFL / Ideogram 的私有接口目前
   返回明确的失败提示并引导用户换用 OpenAI 兼容模型，而不是假装成功。
3. **上帝视角的 NPC 对 NPC 会话** —— `CharacterAgent.dialogue()` 已实现，
   但 UI 入口尚未接出。
4. **人物头像的文生图** —— `Prompts.avatarPrompt()` 与 `ImageGenClient` 都已就绪，
   缺少一个「给 TA 生成头像」的按钮。
5. **`capabilities` 声明缺失** —— 各厂商对 `response_format: json_object`、
   `temperature` 上限的支持差异较大，目前靠「失败就报错让用户换模型」兜底。
