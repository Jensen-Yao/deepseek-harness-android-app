# 🤖 DeepSeek Harness Android App

<div align="center">

**把 DeepSeek Harness 装进你的安卓手机 —— 从零到跑起来，全程点按完成，无需命令行**

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-7.0%2B-brightgreen.svg)](https://www.android.com)
[![DeepSeek Harness](https://img.shields.io/badge/DeepSeek%20Harness-0.1.0--rc.6-4D6BFE.svg)](https://github.com/deepseek-ai/deepseek-harness)
[![Termux](https://img.shields.io/badge/Termux-F--Droid-orange.svg)](https://f-droid.org/packages/com.termux/)

[特性](#-特性) · [快速开始](#-快速开始) · [架构](#-架构) · [存储位置](#-存储位置) · [FAQ](#-faq) · [致谢](#-致谢)

</div>

---

## ✨ 特性

| 能力 | 说明 |
|---|---|
| 🧭 **Termux 引导安装** | 自动识别机型与架构（arm64/armv7/x86），App 内直接下载匹配的 Termux 安装包并调起系统安装器 |
| 🚀 **一键部署** | 自动测速选择最快软件源（清华/北外/中科大等 7 源）→ 安装工具链 → 编译原生模块 → 启动服务，全程实时日志 |
| 📟 **三通道日志** | 部署日志从第 0 秒起同步显示在 App（脚本心跳上报 / 早期进度服务 / 控制守护进程三通道） |
| 🌐 **内置浏览器** | WebView 内直接使用 Harness，顶部工具栏支持返回/前进/刷新/外部打开 |
| 🎛 **服务控制** | 启动 / 停止 / 重启 / 状态监控 / 日志查看，全在手机点按完成 |
| 📁 **存储管理** | 查看/编辑 harness 全部数据（会话、附件、技能、MCP 配置档案）；单项或**统一数据根**一键迁移到共享存储 |
| 🧭 **浏览器选择** | 外部打开 Harness 可选择任意已装浏览器（默认 Via，可选系统默认） |
| 🔋 **防冻结引导** | 按机型（ColorOS/鸿蒙/MIUI/OriginOS/One UI）提示电池优化设置路径 |
| 🔧 **自服务更新** | 手机端守护进程可经 App 一键更新，不依赖电脑 |
| 🎨 **Apple 设计语言** | iOS 系统色板、大标题负字距、弹簧动画（阻尼/响应参数化）、按下变暗+震动、触觉反馈、减少动态效果适配 |

## 📦 快速开始

> 只需一次：把 `install/dsh-harness.apk` 传到手机并安装（微信/QQ/数据线/网盘均可）。之后的每一步都在 App 内完成。

1. 打开 **DeepSeek Harness** App →「部署」页
2. 点「下载 Termux 安装包」（自动按机型匹配、自动选最快源）→「安装 Termux」
3. 点「一键部署 DeepSeek Harness」→ 等待 5~15 分钟（日志实时滚动）
4. 出现「✓ 部署完成」→「打开 Harness」→ 填入 DeepSeek API Key，开始使用 🎉

> 若自动部署被系统拦截（部分 ColorOS/鸿蒙机型），App 会自动弹出「粘贴部署」万能通道：一键复制命令 → 打开 Termux 粘贴回车，效果相同。

## 🏗 架构

```
┌─────────────────────────────────────────────┐
│           DeepSeek Harness App（原生 APK）     │
│  控制 · 部署 · 关于 · 内置 WebView · 存储管理    │
└───────────────┬─────────────────────────────┘
                │ HTTP (127.0.0.1:8045 / 8023)
┌───────────────▼─────────────────────────────┐
│            Termux 环境（手机本机）             │
│  ┌───────────────────────────────────────┐  │
│  │  控制守护进程 server.mjs (8023)         │  │
│  │  status/start/stop/restart/storage…   │  │
│  └───────────────┬───────────────────────┘  │
│                  │ spawn / pkill             │
│  ┌───────────────▼───────────────────────┐  │
│  │  DeepSeek Harness  (127.0.0.1:3080)   │  │
│  │  ~/.dsh: 会话/附件/技能/配置档案        │  │
│  └───────────────────────────────────────┘  │
│  runit 自启 · 部署脚本服务(8045) · sshd      │
└─────────────────────────────────────────────┘
```

## 📁 存储位置

Harness 全部数据位于单一数据根 **`~/.dsh`**（源码 `@deepseek-ai/dsh-home-paths`，可由 `DSH_HOME` 覆盖）：

| 数据 | 路径 |
|---|---|
| 凭证 | `~/.dsh/.credentials.yaml` |
| 设置 | `~/.dsh/settings.yaml` |
| 会话记录 | `~/.dsh/sessions/<项目>/<会话>/session.jsonl[.zstd]` |
| 会话缓存 / 工作区记录 | `~/.dsh/storages/*.json` |
| 附件 | `~/.dsh/attachments/v1/objects/<sha256[:2]>/<sha256>` |
| 用户技能 | `~/.dsh/skills`（另有 `~/.agents/skills`、项目级 `.dsh/skills`） |
| MCP 配置 | 在 `~/.dsh/profiles/` 的配置档案中（`cordis.patch.yml` 等） |

App「关于 → 存储位置」可浏览/编辑以上全部，并支持**统一数据根**一键迁移到 `/sdcard/dsh-data/`（软链接回原位，harness 无感知）。

## 🛠 从源码构建

无 Gradle，仅需 JDK 17 + Android SDK build-tools 36：

```powershell
cd app
# 1) 生成内嵌部署资源（把 daemon/server.mjs 等打包进 APK）
powershell -File gen-assets.ps1
# 2) 编译并签名
powershell -File build2.ps1
# 产物：build2/dsh-harness.apk
```

目录结构：

```
app/        安卓端源码（纯 Java，无第三方依赖）+ 无 Gradle 构建脚本
daemon/     Termux 侧控制守护进程与自启脚本（Node 零依赖）
install/    预编译 APK
docs/       项目主页（GitHub Pages）
```

## ❓ FAQ

- **自动部署被拦截？** 使用 App 弹出的「粘贴部署」万能通道（无需任何权限）。
- **切后台服务被冻结？** 按 App 内「电池优化（防冻结）」卡片完成机型对应设置。
- **App 不显示日志？** 已三通道冗余（心跳上报/早期服务/守护进程）；终端侧也可 `tail -f ~/dsh/storage/deploy.log`。
- **换手机？** 新手机装 APK → 重走一遍部署向导即可，全部自动化。

## 🙏 致谢

- [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) — DeepSeek 官方 agent harness
- [FunnelCakes/deepseek-harness-android](https://github.com/FunnelCakes/deepseek-harness-android) — Android/Termux 部署参考与兼容补丁
- [termux/termux-app](https://github.com/termux/termux-app)

## 📄 License

[MIT](LICENSE)
