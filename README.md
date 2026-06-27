# NovelMaker

> 轻量级 Android 小说创作工具 — 本地存储 + DeepSeek AI 辅助写作

![Platform](https://img.shields.io/badge/Platform-Android-green)
![Min SDK](https://img.shields.io/badge/Min%20SDK-24-blue)
![Language](https://img.shields.io/badge/Language-Kotlin-purple)
![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-blueviolet)
![Version](https://img.shields.io/badge/Version-1.2.2-orange)

---

## 📖 简介

NovelMaker 是一款运行在 Android 设备上的小说写作应用。它将专业写作工具的核心功能浓缩于移动端，支持本地项目管理、纯文本编辑和 DeepSeek AI 辅助创作，帮助你在任何地方随时捕捉灵感。

**所有数据 100% 存储在本地**，无需注册账号，无需联网（AI 功能除外）。

---

## ✨ 功能特性

### 📁 项目管理
- 创建、重命名、删除小说项目
- 项目自动统计章节数和总字数
- 项目目录结构：`大纲/` `小说主体/`

### ✍️ 编辑器
- 纯文本编辑，带行号显示
- 撤销/重做（30 步历史）
- 双指缩放调节字体大小
- 侧边栏文件树（文件夹展开/折叠）
- 自动保存

### 🤖 AI 写作助手（DeepSeek）
- **Plan 模式**：AI 辅助构思大纲、角色设定、章节规划，支持方向选择
- **Agent 模式**：AI 直接撰写章节正文
- 流式对话，逐字显示回复
- Function Calling：AI 可读写项目文件
- 对话记录按项目独立保存
- Token 用量统计（系统提示词 / 对话 / 工具调用）
- 缓存命中优化（KV-Cache）

### 🎨 界面
- Material 3 设计语言
- 支持浅色 / 深色 / 跟随壁纸三种主题
- 引导页（欢迎 → 权限 → 主题选择）

### 📊 数据管理
- Token 用量可视化（分段进度条）
- 系统提示词只读浏览
- 聊天记录管理（按项目）
- 定稿章节标记（纳入缓存前缀）

---

## 🛠️ 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin |
| UI 框架 | Jetpack Compose + Material 3 |
| 架构 | MVVM (ViewModel + StateFlow) |
| 存储 | 本地文件系统 + JSON |
| AI API | DeepSeek Chat API (OpenAI 兼容) |
| 构建 | Gradle KTS + Version Catalog |

---

## 📱 系统要求

- Android 7.0 (API 24) 及以上
- 存储权限（用于保存小说文件）
- 网络权限（AI 功能需要）

---


## ⚙️ 配置 AI 功能

1. 获取 [DeepSeek API Key](https://platform.deepseek.com/)
2. 打开应用 → 设置 → AI 写作设置
3. 填入 API 地址（默认 `https://api.deepseek.com`）
4. 填入模型名称（如 `deepseek-chat`）
5. 填入 API Key

---

## 📂 项目结构

```
novelmaker/
├── app/
│   └── src/main/java/cn/novelmaker/wg1337/
│       ├── MainActivity.kt              # 主入口
│       ├── NovelMakerApp.kt             # Application
│       ├── data/
│       │   ├── model/                   # 数据模型
│       │   └── repository/              # 数据仓库
│       ├── ui/
│       │   ├── ai/                      # AI 模块
│       │   ├── editor/                  # 编辑器
│       │   ├── home/                    # 首页
│       │   ├── navigation/              # 导航
│       │   ├── onboarding/              # 引导
│       │   ├── settings/                # 设置
│       │   └── theme/                   # 主题
│       └── utils/                       # 工具类
└── gradle/                              # 构建配置
```

---

## ⚠️ 免责声明

- 本软件仅供个人学习和创作使用
- AI 生成内容的质量取决于模型和提示词，请自行审阅
- 请妥善保管 API Key，避免泄露
- 本地数据请定期备份，卸载应用会导致数据丢失

---

## 📄 许可证

```
Copyright (c) 2025 NovelMaker. MIT.

本软件及其相关文档（"软件"）的版权归作者所有。
未经作者明确书面许可，任何人不得：
  - 复制、修改、合并、发布、分发本软件
  - 对本软件进行逆向工程、反编译或反汇编
  - 将本软件用于商业用途

本软件按"原样"提供，不提供任何明示或暗示的保证。
```

---

**Made with ❤️ for writers.**
