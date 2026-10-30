# Onomixel 🗯️

[![Minecraft](https://img.shields.io/badge/Minecraft-26.3-informational?style=flat-square&logo=minecraft)](https://minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-26.3.0.99-orange?style=flat-square)](https://neoforged.net/)
[![Java](https://img.shields.io/badge/Java-25-blue?style=flat-square&logo=openjdk)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-All%20Rights%20Reserved-lightgrey?style=flat-square)](#-license)

**Onomixel** 是专为 Minecraft 打造的新一代**空间拟声词可视化**与**视界外战术威胁预警**纯客户端 Mod。通过在 3D 世界空间中动态呈现具有漫画质感的拟声字形与动效，并将视界外的致命威胁实时映射到屏幕边缘雷达，为玩家带来沉浸式且具战术价值的视听体验。

---

## ✨ 核心特性

### 1. 🗯️ 3D 空间动态拟声词 (Spatial Kinetic Typography)
- **自然空间定位**：声音发生处实时生成 3D 拟声文字，随玩家视角平滑旋转与自适应避让，避免遮挡视线与准星。
- **世界深度与遮挡**：遵循真实的物理遮挡规则——实体墙体后方隐藏，玻璃等透明/半透明方块后方正常可见。
- **漫画级动力学动效 (Kinetic Typography)**：
  - **Hitstop Snap-back**：重击与暴击瞬间定格蓄力并在爆开时回弹。
  - **Radial Speedlines**：命中冲击时迸发高对比度放射状漫画爆刺速度线。
  - **Shake / Pop / Float**：爆炸震颤、方块轻弹、物品拾取跃动，节奏层次分明。
- **智能避让与软排斥**：同帧邻近文字自动应用软排斥力场，密集声音不会重叠穿模。

### 2. 🎯 视界外战术边缘雷达 (Tactical Edge Radar)
- **致命威胁感知**：当苦力怕嘶嘶蓄力、恶魂哭泣蓄火等高危音效在视线之外响起时，屏幕边缘即刻浮现战术胶囊（例如 `⚠ 苦力怕 3.2m ▶`）。
- **距离自适应不透明度**：近距离（≤3m）100% 压迫感强提示，远距离柔和感知，主次分明。
- **四边缘流式排版**：智能避让游戏原生 HUD（经验条、快捷栏、生命护甲条）与首领血条。

### 3. 🎨 原生与自定义拟声词扩展引擎 (Custom Words Engine)
- **四级优先级层叠**：
  `本地用户配置` > `资源包/数据包` > `第三方 Mod API` > `原版官方词典`
- **灵活的匹配规则**：
  - 支持精准音效 ID、通配符（`entity.creeper.*`）与正则表达式。
  - 支持单字面量（`literal`）、加权变体池（轮盘赌随机）与多语言翻译键。
- **零 GC 高频纳秒索引**：基于优化的只读索引与内存缓存，高频声音事件纳秒级匹配，杜绝 GC 掉帧。
- **热重载支持**：游戏中随时按下 `F3 + T` 或在配置界面点击按钮，无需重启游戏即刻刷新词库。

### 4. 🖥️ 60FPS 实时沙盒预览配置屏 (Live Sandbox GUI)
- 游戏内交互配置界面内置 310px 动态沙盒，同帧实时预览 3D 拟声字形、动效、色盲滤镜与透明度滑块效果。
- 界面内嵌战术雷达胶囊与中央文字联动避让演示，直观展现最终游戏视觉效果。

---

## 📦 环境要求

| 依赖项 | 要求版本 |
| :--- | :--- |
| **Minecraft** | `26.3` |
| **Mod 加载器** | `NeoForge 26.3.0.99+` |
| **Java** | `Java 25` |
| **客户端/服务端** | **纯客户端 Mod (Client-Only)**，单人游戏或加入任何原版/模组服务器均可使用 |

---

## 🔨 从源码构建

确保本地已配置好 **Java 25** 开发环境，在项目根目录下执行：

```bash
# Windows
.\gradlew.bat build

# Linux / macOS
./gradlew build
```

构建成功后，生成的 Mod 安装包位于：
```
build/libs/onomixel-<version>.jar
```

---

## ⚙️ 自定义配置示例

模组自动在 `.minecraft/config/onomixel/` 目录下生成样例配置文件。你可以创建 `custom_words.json` 来覆写或扩展拟声词：

```json
{
  "version": 1,
  "rules": [
    {
      "pattern": "entity.creeper.primed",
      "display": {
        "text": "BOOM!!",
        "style": { "color": "#FF2020", "bold": true }
      },
      "kinetic_motion": "hitstop"
    },
    {
      "pattern": "entity.zombie.ambient",
      "display": {
        "type": "weighted_random",
        "variants": [
          { "text": "呃...", "weight": 70 },
          { "text": "嗬——", "weight": 30 }
        ]
      }
    }
  ]
}
```

---

## 📄 License

- NeoForged MDK 派生模板遵循 [TEMPLATE_LICENSE.txt](TEMPLATE_LICENSE.txt) (MIT License)。
- 本项目其余原创源码及资产版权所有 © 2026 kunkun。
