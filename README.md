# BlockShuffle

BlockShuffle（方块互换）基于 NeoForge 开发的趣味模组，欢迎品尝

## 补充：
- 本模组仅需服务端即可，但同时也支持客户端
- 启动客户端并进入世界之后，根目录config下会生成blockshuffle.toml配置文件，如果遇到大版本更新，务必删除此项让模组重新生成
- 好友联机可只需服主本人安装模组即可

ps：测试不全，如有bug请反馈，万分感谢！

## 更新日志
**2026-9-15**  
- 修复了方块互换时，限制掉落物逻辑对玩家正常交互时掉落物逻辑失效的问题  
- 默认黑名单新增所有坠落物、0硬度物品  
- 所有流体只和方块作用，可配置项
- 优化了信息提示tick，现为秒出，但不代表过程已走完，只是提示信息提前了
- 新增了mod欢迎语
- 新增必然事件自定义配置项，例如增加一个钻石矿石，则互换事件必然有钻石矿石发生互换

**2026-9-16** 
- 新增模组总开关，可通过指令/blockshuffle off关闭，也可直接在配置页一键关闭
- 默认黑名单新增地毯等容易被破坏且大量掉落的方块

## 功能

玩家每次受伤时，以玩家为中心在指定区块范围内随机抽取两种方块类型并整体互换

## 配置

模组参数通过 COMMON 配置进行管理（单人存档与专用服务器都可编辑）：

- **Swap Radius (chunks)**: 互换区块半径范围，默认 6，最大 32
- **Cooldown (seconds)**: 两次互换之间的最小冷却时间，防止持续伤害触发的频繁互换
- **Blocks per Tick**: 每 tick 处理的方块数量
- **Max Blocks per Swap**: 单次互换的最大方块数（安全阀）
- **Max Type Re-rolls**: 当安全阀拒绝时，最大重试次数
- **Fluids Participate**: 水和岩浆是否参与互换
- **Keep Plants Attached**: 保持植物附着，避免大量物品掉落
- **Limit Falling Blocks**: 限制掉落中的方块实体数量
- **Block Blacklist**: 永不参与互换的方块列表
- **Enabled Dimensions**: 启用互换的维度
- **Block Weights**: 方块被抽取的权重配置
- **Swap Message Style**: 互换信息显示方式（聊天栏/热bar上方/屏幕中央/关闭）
- **Swap Message Scope**: 哪些人能看到互换信息
- **Dropped Item Limit**: 掉落物品数量限制
- **Drop Watch Window**: 掉落监视窗口时间

客户端可通过游戏内配置界面（NeoForge 自带的 ConfigurationScreen）进行设置

## 技术信息

- **Minecraft 版本**: 1.21.1
- **NeoForge 版本**: 21.1.250
- **Java 版本**: 21

## 构建

```bash
./gradlew build
```

## 运行

```bash
./gradlew runClient  # 客户端
./gradlew runServer  # 服务端
```

## 问题反馈
mail：2274473985@qq.com
