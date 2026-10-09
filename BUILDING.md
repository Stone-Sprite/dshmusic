# 从源码编译

这个项目**不用 Gradle，也不用 Loom/ForgeGradle**。

原因：**Minecraft 26.3 是去混淆的**（class 和成员名可读），所以不存在 remap 问题，直接对着原版 jar 用 `javac` 编译就行。这样省掉了整个构建工具链，代价是 classpath 要自己拼。

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

---

## 二、三个必须注意的坑

这三个我都实际踩过，任何一个都会让你debug很久。

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

### 坑 2：classpath 上不能有同一个库的多个版本，尤其不能旧的在前

javac 取 classpath 上**第一个**匹配的类。如果 classpath 里同时有 guava 20 和 guava 33，而 20 排在前面，就会按 guava 20 的 API 编译，运行时加载 33 —— 报 `NoSuchMethodError`。

我遇到的具体例子：`com.mojang:authlib` 在 classpath 上有 4.0.43 / 7.0.61 / 7.0.63 三份，4.0.43 排第一。

```java
// 编译过了 —— 因为 4.0.43 里的 GameProfile 有 getName()
info.getProfile().getName()
// 运行时炸 —— 7.x 起 GameProfile 是 record，只有 name()
```

**所以：让运行期版本的库排在 classpath 最前面。**

### 坑 3：混入错误世代的加载器 jar

`$MC\libraries\net\neoforged` 下通常躺着好几个版本的 NeoForge（我这台机器上有 21.11.42 / 21.11.45 / 26.1.2.95 三份）。如果不加过滤全塞进 classpath，会解析到错误的 API。

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

## 四、打包

jar 里需要包含：

```
dev/dsh/music/*.class        编译产物
META-INF/neoforge.mods.toml  或  fabric.mod.json
logo.png / assets/dshmusic/icon.png
com/jcraft/jlayer/**         第三方库解出来一起打进去
org/tritonus/**
javazoom/**
```

```powershell
# 把第三方库解到 stage，再放 class 和元数据，最后打包
& jar.exe --create --file out.jar -C build\stage .
```

---

## 五、第三方依赖

编译和运行都需要，但**未随仓库分发**（许可是 LGPL）：

| 库 | 用途 |
|---|---|
| [JLayer](https://sourceforge.net/projects/javalayer/) | MP3 解码 |
| [MP3SPI](https://sourceforge.net/projects/mp3spi/) | Java Sound 的 MP3 支持 |
| [Tritonus](https://sourceforge.net/projects/tritonus/) | MP3SPI 的依赖 |

放到 `lib/` 下即可（`lib/` 已在 `.gitignore` 里）。

---

## 六、ffmpeg

只有网络播放需要。运行期会自动查找，也可以显式指定：

```
-Ddshmusic.ffmpeg=D:\path\to\ffmpeg.exe
```

**必须是有 MP4 解复用器的完整版**。精简版（比如某些软件自带的 2 MB 版本）能运行但会报 `Invalid data found when processing input` —— 模组会主动探测并拒绝这种。
