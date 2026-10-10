# 从源码编译

这个项目**不用 Gradle，也不用 Loom/ForgeGradle**。但三个平台的难度并不一样：

| 平台 | 需要重映射吗 | 为什么 |
|---|---|---|
| **26.3 + Fabric** | ❌ 不需要 | 26.3 是**去混淆**的，class 和成员名可读，直接对着原版 jar `javac` 就行 |
| 1.21.11 + NeoForge | ❌ 不需要 | NeoForge 生产环境跑的就是 **Mojang 名**，源码写什么就是什么 |
| **1.21.11 + Fabric** | ✅ **需要两遍** | Fabric 生产环境加载 **intermediary** 名（`class_2960`），源码写的是 **Mojang** 名（`Identifier`） |

Loom 平时干的就是最后那一件事。不用 Loom 就得自己接上 —— 见第四节。

> 下面用 `$MC` 表示 `.minecraft` 目录，用 `$JDK` 表示 JDK 的 `bin` 目录。

---

## 一、准备 classpath

### 1.21.11 (NeoForge)

从版本 json 里读出运行期库，**按 json 里的顺序**放进 classpath，再补上 NeoForge 自己的 jar。

```powershell
$MC = "$env:APPDATA\.minecraft"
$jv = Get-Content "$MC\versions\1.21.11\1.21.11.json" -Raw | ConvertFrom-Json

$cp = New-Object System.Collections.Generic.List[string]
foreach ($lib in $jv.libraries) {
  if ($lib.downloads.artifact.path) {
    $p = Join-Path "$MC\libraries" ($lib.downloads.artifact.path -replace '/','\')
    if (Test-Path $p) { $cp.Add($p) }
  }
}
# NeoForge 的 jar（注意只取 21.11.42 这一代，别的版本会引入错误 API）
Get-ChildItem "$MC\libraries\net\neoforged" -Recurse -Filter *.jar |
  Where-Object { $_.FullName -match '21\.11\.42|47\.2\.2|11\.0\.2' } |
  ForEach-Object { $cp.Add($_.FullName) }
```

### 26.3 (Fabric)

```powershell
$jv = Get-Content "$MC\versions\26.3-Fabric 0.19.5\26.3-Fabric 0.19.5.json" -Raw | ConvertFrom-Json
# 同上，再加上 fabric-loader / sponge-mixin / asm
```

### 1.21.11 (Fabric)

和 NeoForge 那份基本一样，但**要把 Fabric API 的各个模块换成 Mojang 名的那一份** ——
Fabric API 官方发布的是 intermediary 名，直接拿来编译会解析不到 MC 类型。
（本项目用 `tiny-remapper` 把 43 个 Fabric API 模块做了一次 intermediary → Mojang。）

---

## 二、四个必须注意的坑（编译期）

这四个我都实际踩过，任何一个都会让你 debug 很久。

### 坑 1：`@argfile` 里反斜杠是转义字符

classpath 有 100 多段、上万字符时，得写进 argfile 用 `@file` 传给 javac。但 **javac 的 argfile 里 `\` 是转义字符**：

```
写成  -cp "D:\.minecraft\libraries\..."
javac 读到  D:.minecraftlibraries...        ← 路径全废，报「找不到包」
```

**必须用正斜杠**：

```
-cp "D:/.minecraft/libraries/..."
```

> 顺带：argfile **不能带 UTF-8 BOM**，否则 javac 会把 BOM 当成参数的一部分，报
> `无效的标记: ?-nowarn`。`[IO.File]::WriteAllText($p, $s, (New-Object Text.UTF8Encoding($false)))`。

### 坑 2：classpath 上不能有同一个库的多个版本，尤其不能旧的在前

javac 取 classpath 上**第一个**匹配的类。如果 classpath 里同时有 guava 20 和 guava 33，而 20 排在前面，就会按 guava 20 的 API 编译，运行时加载 33 —— 报 `NoSuchMethodError`。

我遇到的具体例子：`com.mojang:authlib` 在 classpath 上有 4.0.43 / 7.0.61 / 7.0.63 三份，4.0.43 排第一。

```java
// 编译过了 —— 因为 4.0.43 里的 GameProfile 有 getName()
info.getProfile().getName()
// 运行时炸 —— 7.x 起 GameProfile 是 record，只有 name()
```

**所以：让运行期版本的库排在 classpath 最前面。**（`tools/rebuild-fabric-1.21.11.ps1` 就是先读版本 json 生成运行期列表，再拼上其余部分。）

### 坑 3：混入错误世代的加载器 jar

`$MC\libraries\net\neoforged` 下通常躺着好几个版本的 NeoForge（我这台机器上有 21.11.42 / 21.11.45 / 26.1.2.95 三份）。如果不加过滤全塞进 classpath，会解析到错误的 API。

### 坑 4：脚本文件写成 UTF-8 无 BOM，中文注释会被按 ANSI 读

Windows PowerShell 5.1 读没有 BOM 的 `.ps1` 时按系统 ANSI 代码页解码。文件里有中文注释时，
某些字节序列会把后面一行的内容吃掉，表现为「脚本语法没问题，但某个变量莫名其妙是空的」。
我遇到的是 classpath 变量变成空字符串，javac 报一堆「找不到包」。

**要么整份脚本只用 ASCII，要么写文件时带 BOM。**

---

## 三、编译

```powershell
# 1.21.11 —— 用 Minecraft 自带的 JDK 21
& "$MC\runtime\java-runtime-delta\bin\javac.exe" "@build\javac.args"

# 26.3 —— 用 JDK 25
$cp = ([IO.File]::ReadAllLines("build\javac.args"))[6].Trim('"')
& "$MC\runtime\java-runtime-epsilon\bin\javac.exe" `
    -nowarn -encoding UTF-8 -d build\classes -cp $cp (Get-ChildItem src\*.java).FullName
```

argfile 的格式：

```
-nowarn
-encoding
UTF-8
-d
"<绝对路径>/build/classes"
-cp
"<正斜杠的 classpath，分号分隔>;<第三方库>"
"<绝对路径>/src/MusicMod.java"
...
```

---

## 四、1.21.11 Fabric 的两遍重映射

### 4.1 两个命名空间的来源（都是磁盘上现成的，不用下载）

```
Mojang 的 ProGuard 映射
  $MC\libraries\net\minecraft\client\1.21.11\client-1.21.11-mappings.txt
  official → Mojang

Fabric 的 tiny 映射
  $MC\libraries\net\fabricmc\intermediary\1.21.11\intermediary-1.21.11.jar
  → mappings/mappings.tiny      official → intermediary

原版混淆 jar
  $MC\versions\1.21.11\1.21.11.jar      （第 2 遍的 classpath 要用它）

Fabric 自带的 tiny-remapper
  $MC\libraries\net\fabricmc\fabric-loader\<ver>\fabric-loader-<ver>.jar
  → net.fabricmc.loader.impl.lib.tinyremapper
```

### 4.2 为什么是两遍，而不是把两个映射文件合成一张

想省事的话会去合成一张 `Mojang ↔ intermediary` 的表（用 `official` 当公共主键）。
这条路走不通：

```
DataComponentHolder   official `c`  →  intermediary method_57826   （Mojang 名：has）
DataComponentMap      official `c`  →  intermediary method_57832   （Mojang 名：has）
```

两个不同类里的方法，在 **Mojang 命名下同名同描述符**，intermediary 名却不一样。
以「命名名」为源做 `mojang → intermediary`，tiny-remapper 会看到一个源名对应两个目标，直接抛：

```
java.lang.RuntimeException: Unfixable conflicts
```

**走 `official` 中转就没有歧义**，因为 official 名在每个类里唯一。
而且两个映射文件都成了**原生**文件（源命名空间就是文件里的第一个命名空间），
不需要任何 join，因此一个条目都不丢。

```
第 1 遍   mojang   → official        tools/proguard_to_tiny.py 生成的 mojmap-official.tiny
                                     classpath = Mojang 名的 client jar
第 2 遍   official → intermediary    直接用 Fabric 自带的 mappings.tiny
                                     classpath = 原版混淆 1.21.11.jar
```

### 4.3 ⚠️ 第 2 遍的 classpath 必须是「源命名空间」的 jar

这一条最坑：给 tiny-remapper 一个 intermediary 名的 client jar 当 classpath，
它解析不到被改名后的类，于是**静默跳过所有成员改名** —— 不报错、不警告，
产出的 jar 里类名换好了、方法名一个没换。进游戏才炸：

```
NoSuchMethodError: 'net.minecraft.class_2960
   net.minecraft.class_2960.fromNamespaceAndPath(java.lang.String, java.lang.String)'
```

同理，**Tiny v2 的成员描述符必须用第一个命名空间书写**。写成 intermediary 名的话，
键一个都对不上，结果同样是「一个成员都没改名」，而且**一点提示都没有**。

### 4.4 生成第 1 遍的映射文件

```powershell
python tools\proguard_to_tiny.py `
    "$MC\libraries\net\minecraft\client\1.21.11\client-1.21.11-mappings.txt" `
    "D:\dsh-build\mojmap-official.tiny"
# -> tiny 2 0 mojang official    （描述符按规范写在第一个命名空间，即 Mojang 名）
```

### 4.5 跑两遍

`tools/RemapTool.java` 是个薄壳，直接用 Fabric Loader 里打包的 tiny-remapper：

```
RemapTool <in.jar> <out.jar> <mappings.tiny> <fromNs> <toNs> [classpath...]
```

现成的脚本：`tools/rebuild-fabric-1.21.11.ps1`（编译 + 两遍 + 字节校验）、
`tools/install-fabric-1.21.11.ps1`（校验 + 装机）。

### 4.6 怎么确认映射真的生效了

**别信编译通过，也别信接口签名。** 读 jar 里的 class 字节：

```powershell
$txt = [Text.Encoding]::ASCII.GetString($classBytes)
$txt.Contains('method_60655')                 # 有 → 静态方法改名成功
$txt.Contains('net/minecraft/resources/')     # 有 → 还有 Mojang 名残留，映射漏了
```

`rebuild-fabric-1.21.11.ps1` 每次都跑这一步，21 个类必须全部 `clean`。

> 反例（我踩过）：拿 `HudElement` / `render(GuiGraphics, DeltaTracker)` 做「签名验证」是**假阳性** ——
> 这两个名字是 **Fabric 自己的**，intermediary 根本不会重映射它们，所以怎么验都对。

---

## 五、打包

jar 里需要包含：

```
dev/dsh/music/*.class             编译产物（Fabric 版要重映射过）
META-INF/neoforge.mods.toml       或  fabric.mod.json
assets/dshmusic/icon.png
javazoom/**                       JLayer + mp3spi，MP3 解码
org/tritonus/**
META-INF/services/javax.sound.sampled.spi.*
```

Fabric 1.21.11 版的 `rebuild-fabric-1.21.11.ps1` 是**从已发布的 26.3 jar 里把这 204 个
条目拷过来**的 —— 它们是纯 Java、不含任何 `net/minecraft` 引用，所以不需要重映射，
而且脚本会顺带断言这一点（`MC refs inside: 0`）。

---

## 六、第三方依赖

编译和运行都需要，**未随仓库分发**（许可是 LGPL），但**已打进发布出来的 jar**，
所以使用者不用单独安装：

| 库 | 用途 |
|---|---|
| [JLayer](https://sourceforge.net/projects/javalayer/) | MP3 解码 |
| [MP3SPI](https://sourceforge.net/projects/mp3spi/) | Java Sound 的 MP3 支持 |
| [Tritonus](https://sourceforge.net/projects/tritonus/) | MP3SPI 的依赖 |

放到 `lib/` 下即可（`lib/` 已在 `.gitignore` 里）。

---

## 七、ffmpeg

只有网络播放需要。运行期会自动查找，也可以显式指定：

```
-Ddshmusic.ffmpeg=D:\path\to\ffmpeg.exe
```

**必须是有 MP4 解复用器的完整版**。精简版（比如某些软件自带的 2 MB 版本）能运行但会报
`Invalid data found when processing input` —— 模组会主动探测并拒绝这种。
