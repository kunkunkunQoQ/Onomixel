<img src="./src/main/resources/icon.png" width="96" height="96" alt="Onomixel 图标">

# Onomixel

Onomixel 把 Minecraft 里的声音变成两种视觉提示：身后苦力怕点燃引线时，屏幕边缘的雷达会标出方向和距离；开启空间拟声词后，挖掘、拾取和生物叫声也会在声源旁显示为漫画文字。两种提示可以独立开关，原版字幕可以同时使用。

*Client-side sound visualization for Minecraft. Edge radar points to off-screen threats, and optional comic-style captions appear where sounds originate.*

| Minecraft | NeoForge | 游戏运行环境 | 安装位置 |
| --- | --- | --- | --- |
| 26.3 | 26.3.0.99 起的 26.3 系列，构建版本为 26.3.0.103 | Java 25 | 客户端 |

## 安装与首次启用

1. 从[正式版发布页](https://github.com/kunkunkunQoQ/Onomixel/releases/tag/v0.11) 下载 `onomixel-0.11.jar`，将它放入启动器中**当前游戏实例**的 `mods/` 目录。
2. 用 NeoForge 启动游戏。新配置默认开启**雷达**。进入世界后，视野外的苦力怕引信等受支持的威胁声会触发边缘提示。
3. 要显示空间文字，在模组列表中选中 Onomixel，打开配置界面，开启**拟声词**并点击**保存并应用**。

拟声词会随视角在声源附近调整位置，给准星和附近文字留出空间。实墙会遮挡文字，隔着玻璃仍可看见。方块破坏、物品拾取和战斗命中各有不同的动效。可按声音 ID 在仓库中查阅原版声音的纳入与排除记录。[声音目录](./sound-catalogs.json)

## 从源码构建

将 `JAVA_HOME` 指向 JDK 25，并将 `JAVA21_HOME` 指向供 NeoForm 工具使用的 Java 21 安装目录（JRE 或 JDK）。在克隆的仓库根目录运行对应命令。

**Windows PowerShell**

```powershell
.\gradlew.bat build
```

**Linux / macOS**

```sh
./gradlew build
```

安装包生成在 `build/libs/`。同目录的 `-sources.jar` 供阅读源码使用。

## 自定义拟声词

在配置界面点击**打开词库目录**，在该目录创建或编辑 `custom_words.json`。下面的规则会把苦力怕的引信声显示为“嘶——！”，并设置鲜艳配色下的红色和抖动效果。

```json
{
  "overrides": [
    {
      "match": "minecraft:entity.creeper.primed",
      "text": "嘶——！",
      "style": {
        "vibrant_color": "#EF4444",
        "kinetic_motion": "SHAKE"
      }
    }
  ]
}
```

保存文件后，点击**重新加载词库**或按 `F3 + T`。本地规则优先于资源包与扩展 Mod 的规则。通配符、加权随机文字和其他动效可参考仓库内的示例。[完整词库示例](./config/onomixel/custom_words.example.json)

## 反馈与支持

遇到雷达方向、文字遮挡或声音漏报问题时，请记录触发声音的动作、当时的视角和已开启的显示选项。[提交问题反馈](https://github.com/kunkunkunQoQ/Onomixel/issues/new?template=bug_report.yml) · [已有反馈](https://github.com/kunkunkunQoQ/Onomixel/issues)

作者的演示与开发动态见 [Bilibili · 困困困QAQzZZ](https://space.bilibili.com/2115481637)，也可以在[爱发电 · koukou021](https://afdian.com/a/koukou021) 支持开发。

## 许可

Onomixel 原创源码与资源版权所有 © 2026 kunkun，保留所有权利。NeoForged MDK 派生模板使用 MIT 许可证。[模板许可全文](./TEMPLATE_LICENSE.txt)
