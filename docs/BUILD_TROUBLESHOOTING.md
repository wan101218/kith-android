# Kith · 构建故障排查手册

记录本机（Windows 11 / 16GB / Android Studio + 多项目并存）上踩过的构建坑。
每条都附**证据链**，因为这几个问题的报错信息都具有强烈误导性。

---

## 一、AAPT2 daemon 启动失败（内存耗尽）

### 症状

```
> Task :app:processDebugResources FAILED
AAPT2 aapt2-9.0.0-14304508-windows Daemon #0: Daemon startup failed
Please check if you installed the Windows Universal C Runtime.
```

```
Caused by: Aapt2InternalException: Failed to start AAPT2 process.
Caused by: java.io.IOException: Process unexpectedly exit.
```

**报错信息是误导的。** 「缺少 Windows Universal C Runtime」是 AAPT2 启动失败时的通用兜底文案，
不代表真的缺运行库；`Process unexpectedly exit` 也没有任何 stderr 可看。

### 排查过程中被排除的假设

| 假设 | 排除依据 |
|---|---|
| aapt2 二进制损坏 | 直接跑 `aapt2.exe version` 与 `aapt2.exe daemon`（输出 `Ready` → `Exiting daemon`），退出码 0 |
| 缺 UCRT | 同上，能正常加载运行 |
| 进程占用导致 dll 锁定 | 该 dll 目录可重命名成功，证明无进程持有 |
| 环境变量过大 | `env \| wc -c` = 10.4 KB / 158 项，远未到 Windows 上限 |
| 依赖缺失 / 离线索引问题 | 换联网后依旧，且任务本身在命令行可成功 |

### 真正的根因：物理内存耗尽

两条独立证据交叉确认：

**证据 A —— Gradle 自己的内存日志（`~/.gradle/daemon/9.2.1/daemon-10372.out.log`）**

```
[DefaultMemoryManager] 1694845337 physical memory requested, 1382780928 free
[WorkerDaemonExpiration] Will attempt to release 1616 of memory
```

**证据 B —— 直接测量系统内存**

```python
# 用 ctypes 调 GlobalMemoryStatusEx，不依赖 wmic/tasklist
m.ullTotalPhys = 15.8 GB     m.ullAvailPhys = 1.4 GB     m.dwMemoryLoad = 90%
```

**证据 C —— 失败守护进程的履历**

```
Starting 21st build in daemon [uptime: 37 mins 33 secs, heap usage: 0% of 2 GiB]
```

注意 `heap usage: 0%` —— **堆根本不是瓶颈**。这个守护进程的实际工作集是 1.67 GB，
全部来自 metaspace / JIT code cache / native 部分，而且它已经空转了 37 分钟没有释放。

**因果链**：可用内存跌到 ~1.3 GB → Windows 无法为 `aapt2.exe` 创建子进程（或进程刚起就被回收）
→ 进程没跑到能写 stderr 就被杀 → 上层只能报出「Daemon startup failed」和那句误导性的 UCRT 提示。

### 为什么是间歇性的

同一个 aapt2 二进制、同一个命令，有时成功有时失败 —— 取决于当时的内存余量。
这也解释了为什么**这台机器上 10 个不同守护进程的日志里都有这个失败**，
甚至包含另一个 AGP 版本的 `aapt2-8.5.1-11315950-windows`：

```bash
grep -l "Daemon startup failed" ~/.gradle/daemon/9.2.1/*.out.log
# daemon-10372 / 12660 / 13496 / 13888 / 14180 / 16260 / 16580 / 4796 / 7672 / 9072
```

**这是一个跨项目的系统性问题，不是某个工程的配置问题。**

### 解决方案

已在 `gradle.properties` 落地（围绕「降低常驻内存」，不是为了构建速度）：

```properties
# 限制 metaspace 上限，避免长时间存活的守护进程内存无界增长
org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8

# 空闲 15 分钟即退出（默认 3 小时 —— 这正是「存活 37 分钟、构建 22 次后突然失败」的成因）
org.gradle.daemon.idletimeout=900000

# 单模块工程，并行无收益只占内存
org.gradle.parallel=false
org.gradle.caching=true

# Kotlin 编译守护进程单独限堆
kotlin.daemon.jvmargs=-Xmx1536m
```

**结构性改进（收益最大）**：统一 Gradle 的 JDK。
Gradle 按 JDK 区分守护进程，本机曾同时存在两个：

| 守护进程 | Java | 内存 |
|---|---|---|
| daemon-10372 | `Eclipse Adoptium\jdk-21.0.10.7-hotspot`（命令行） | 1672 MB |
| daemon-16412 | `D:\ad1\jbr`（Android Studio 自带 JBR） | 483 MB |

同一个项目白吃两份内存。二选一解决：

- **A**：Android Studio → Settings → Build, Execution, Deployment → Build Tools → Gradle
  → Gradle JDK → 选与 `JAVA_HOME` 一致的 JDK
- **B**：在 `gradle.properties` 里钉死 `org.gradle.java.home=...`（对命令行与 AS 同时生效）

### 效果验证

停掉僵留守护进程后：

```
释放前：总 15.8 GB / 可用 1.1 GB / 占用 92%
释放后：总 15.8 GB / 可用 3.6 GB / 占用 77%
```

之后 `assembleDebug` 连续成功（`BUILD SUCCESSFUL`）。

另外单独验证了当初失败的那一步 —— **绕过 Gradle 直接跑 `aapt2 link`**：

```bash
aapt2 link -o out.apk -I android.jar --manifest <merged manifest> \
  --min-sdk-version 33 --target-sdk-version 36 --auto-add-overlay \
  app/build/intermediates/merged_res/debug/mergeDebugResources/*.flat
```

结果：**aapt2 正常启动，加载全部 124 个资源，一路跑到引用解析阶段**，
「进程起不来」的失败完全没有出现。

> 注意：`aapt2` 是原生程序，不认 MSYS 风格的 `/c/...` 路径。
> 命令行手动调用时必须用 `C:/...` 形式，并设 `MSYS_NO_PATHCONV=1`。

---

## 二、`Could not extract native JNI library`（native 目录陈旧锁）

### 症状

```
Gradle could not start your build.
> Could not initialize native services.
   > Could not extract native JNI library.

Caused by: java.io.FileNotFoundException:
  .gradle\native\<hash>\windows-amd64\native-platform.dll (另一个程序正在使用此文件)
```

### 关键点

**这不是进程占用。** 判别方法：把 `.gradle/native/<hash>` 整个目录改名 ——
能改成功就说明没有进程持有它，问题出在陈旧的 `.lock` 文件上。

另外 `./gradlew --stop` **解决不了**，因为它自己也要加载原生库，会以同样的方式失败。

### 解决

把 `~/.gradle/native/<hash>` 目录改名或删除，让 Gradle 重新解压即可。
（本机 `native.bak` 目录就是上一次同样操作的遗留。）

紧急情况下也可临时绕过：

```bash
./gradlew -Dorg.gradle.native.dir="C:/Users/Administrator/.gradle/native-fresh" ...
```

> 注意：这个 `-D` 参数经 `gradle.bat` 传递会被吞掉，只对 `./gradlew` 有效。

---

## 三、`gradle-9.2.1-bin.zip.lck (拒绝访问)`

### 症状

```
Exception in thread "main" java.io.FileNotFoundException:
  ~\.gradle\wrapper\dists\gradle-9.2.1-bin\<hash>\gradle-9.2.1-bin.zip.lck (拒绝访问。)
```

### 原因

**Android Studio 正持有 Gradle 发行版锁**。在 AS 打开该项目的状态下从命令行跑 `./gradlew`
会与之冲突。杀掉所有 java 进程也无效 —— 持有者是 `studio64.exe` 本身。

### 解决

- 从命令行构建前先关闭 Android Studio（或至少让它停止 Gradle 同步）
- 或绕开 wrapper，直接用已解包的发行版：
  `~/.gradle/wrapper/dists/gradle-9.2.1-bin/<hash>/gradle-9.2.1/bin/gradle.bat`

---

## 四、`--offline` 不可用

`navigation-common-2.8.5.aar`、`navigation-compose-2.8.5.aar` 明明在
`~/.gradle/caches/modules-2/files-2.1/` 里，但 `--offline` 报
`No cached version available for offline mode` —— Gradle 的离线索引认不出这些 artifact。

**本项目必须联网构建。** 联网后依赖解析正常（所有库版本都在本机缓存里，只是索引缺失）。

---

## 五、诊断工具箱（本机可用）

`wmic` / `tasklist` 被安全策略禁用，PowerShell 工具在本会话无回显。
以下纯 Python + ctypes 的方法可用：

```python
# 内存
import ctypes
class M(ctypes.Structure):
    _fields_=[('dwLength',ctypes.c_ulong),('dwMemoryLoad',ctypes.c_ulong),
              ('ullTotalPhys',ctypes.c_ulonglong),('ullAvailPhys',ctypes.c_ulonglong),
              ('a',ctypes.c_ulonglong),('b',ctypes.c_ulonglong),
              ('c',ctypes.c_ulonglong),('d',ctypes.c_ulonglong),('e',ctypes.c_ulonglong)]
m=M(); m.dwLength=ctypes.sizeof(m)
ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(m))

# 进程内存：CreateToolhelp32Snapshot + Process32First/Next + GetProcessMemoryInfo
```

守护进程日志是**最有价值的证据来源**（`~/.gradle/daemon/<version>/*.out.log`）：
里面有构建次数、运行时长、堆/非堆占用、内存看门狗记录、以及完整的环境变量快照。
