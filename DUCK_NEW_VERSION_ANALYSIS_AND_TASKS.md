# Duck Detector 新版检测链分析 + anti_dev_pm_zygisk v2.5.7 → v2.6.0 修复方案

**结论已用真机实测验证**（2026-09-29，`adb` 直连操作，非推测）。

| 项 | 值 |
|---|---|
| 机型 / ROM | Redmi Note 11T Pro+（codename `xagapro` / 22041216UC），Android 14（SDK 34），小米官方 ROM |
| Root 环境 | KernelSU（`u:r:ksu:s0`）+ **Zygisk Next 1.5.0 (843)** |
| 已装模块 | anti_dev_pm_zygisk **v2.5.7**、zygisksu、susfs4ksu、tricky_store、hma-uidfake、hma_oss_zygisk、geekbyte_gadget、geekbyte_avc_clear、MoveCertificate、TA_enhanced |
| Duck 版本 | `2026.09.29-4f26d5f41d48`（983），**targetSdk=37**，安装在 `/data/app/~~iWMFsJJwpGAM0of6QuhYFQ==/com.eltavine.duckdetector-.../base.apk` |
| 源码对齐 | 设备上是 **`origin/main` = `4f26d5f4`**；本地 checkout 在 `master` = `d8477d35`（9/9），**两者不是同一条线，多模块重构版才是设备版本** |

> 本文只做分析与任务拆解，未修改任何 `.cpp`。真机证据文件放在 `evidence\` 子目录。

---

## 0. 真机实测结论（一次说清）

### 0.1 USB 那一行为什么会显示真值 `adb`

真机属性真值（`adb shell getprop`，非 Duck 进程）：

```
[persist.sys.usb.config]: [adb]      ← catalog 的 warningValues 命中项（exact 匹配）
[sys.usb.config]: [adb]
[sys.usb.state]: [adb]
[init.svc.adbd]: [running]
[ro.adb.secure]: [1]  [ro.secure]: [1]  [ro.debuggable]: [0]
```

模块侧日志（`logcat -s DuckVisBypass`，主进程 pid 23495 冷启动）：

```
14:45:18.146 [duckdetector] Duck inventory SDK spoof active: SDK_INT 34->29 RESOURCES_SDK_INT 34->29
14:45:18.146 [duckdetector] SystemProperties JNI hook installed
14:45:18.146 [duckdetector] BinderProxy.transactNative hook installed
14:45:18.146 [duckdetector] UNIXProcess.forkAndExec hook installed
14:45:18.146 [duckdetector] ProcessImpl.forkAndExec hook installed
14:45:18.249 [duckdetector] Duck native bridge hooks ready=6/9 newly=6
14:45:18.249 E ne.duckdetector: Failed to register native method
    com.eltavine.duckdetector.features.nativeroot.data.native.NativeRootNativeBridge
    .nativeCollectSnapshot(Z)Ljava/lang/String; in .../base.apk
14:45:18.249 E ne.duckdetector:      4: java.lang.String ...NativeRootNativeBridge.nativeCollectSnapshot()
...（16 秒重试窗口结束后仍是 ready=6/9）
```

App 侧实测（`uiautomator` 抓的 System Properties 卡片）：

```
仪表盘：  System Properties  Warn  "1 property signal(s) need review"
          "Review System Properties and Custom ROM next."
卡片头：  42 rules · 14 info · 32 native · 3 Build      Critical: None / Review: 1 / Boot: Clean / Build: Clean
行：      persist.sys.usb.config  Observed: mtp
          "USB debug configuration via reflection. Expected safe value: mtp. Cross-checked across 2 sources."
```

**因果链**（三条读通道里，Java 通道被 hook、native 通道没被 hook）：

| 读通道 | 模块状态 | Duck 实际读到 |
|---|---|---|
| REFLECTION（`SystemProperties.get`） | ✅ `android/os/SystemProperties.native_get` hook 生效 | `mtp`（模块把 `adb` token 去掉后的值） |
| GETPROP（`ProcessBuilder("getprop")`） | ✅ 子进程重定向过滤掉该行 | 空 |
| **NATIVE_LIBC（自带 `nativeCollectSnapshot`）** | ❌ **桥接没装上** | **`adb`（真值）** |
| JVM | —（`persist.*` 读不到） | 空 |

→ 两源不一致 → `Source mismatch`（**非** `criticalSourceMismatchProperties`，故为 WARNING）→ 卡片 `Review: 1`、仪表盘 "1 property signal(s) need review"，并且那条 `Diverged` 行的 detail 会逐行打印 **`Reflection: mtp` / `Native libc: adb`** —— 这就是你在页面上看到的 `persist.sys.usb.config ... adb`。

### 0.2 为什么桥接只装上 6/9：三处硬编码失配（全部已坐实）

| # | 模块里的硬编码（`jni/module.cpp`） | 设备版（`origin/main` 4f26d5f4）真实情况 | 结果 |
|---|---|---|---|
| 1 | `com.eltavine.duckdetector.features.systemproperties.data.native.SystemPropertiesNativeBridge`（+JADX 兜底 `...features.systemproperties.data.p013native...`）`:1480-1481` | 已搬迁为 **`com.eltavine.duckdetector.capability.systemproperties.data.SystemPropertiesNativeBridge`**（JNI 符号 `Java_com_eltavine_duckdetector_capability_systemproperties_data_SystemPropertiesNativeBridge_nativeCollectSnapshot`，`capability/systemproperties/data/src/main/cpp/systemproperties/native_bridge.cpp:74`） | 类加载失败 → **属性 native 源泄漏（本次 USB 问题的直接原因）** |
| 2 | `com.eltavine.duckdetector.features.dangerousapps.data.native.DangerousAppsNativeBridge` `:1498-1502` | **该类已不存在**；能力被搬到 **`com.eltavine.duckdetector.capability.packageinventory.data.PackageDataDirectoryProbe.nativeStatPackages`**（`capability/packageinventory/data/src/main/kotlin/.../PackageDataDirectoryProbe.kt:40,48`；JNI 符号 `Java_com_eltavine_duckdetector_capability_packageinventory_data_PackageDataDirectoryProbe_nativeStatPackages`，`.../cpp/packageinventory/native_bridge.cpp:54`），签名仍是 `([Ljava/lang/String;)Ljava/lang/String;` | 类加载失败 → `/data/data/<pkg>`、`/data/user/0/<pkg>` stat 探测不再被抑制 |
| 3 | `...features.nativeroot.data.native.NativeRootNativeBridge`，签名 `(Z)Ljava/lang/String;` `:1558-1566` | 类名在，**签名已变为 `()Ljava/lang/String;`**（ART 报错原文见上） | 注册失败（每次重试都失败，刷 ART 错误日志）→ native root 探针读真值 |

> 6 = 9 - 3，与 `ready=6/9` 完全吻合。Zygisk 注入本身正常（Zygisk Next `modules_info` 里 recorded inode `00736198/00736199` 与设备上 so 的 inode **逐一对应**），所以问题纯粹是**模块对新版类名/签名失配**，不是没注入。

### 0.3 顺带实测到的第二类问题（新版新增的"黄标"）

仪表盘其它卡片实测：**Custom ROM / LSPosed / Native Root 都显示 `... scan has reduced coverage`**（Kernel Check 为 Info）。原因是模块给这些桥接回的是**旧版字段格式的合成快照**，新版 parser 拿不到新键就判"覆盖不足"：

- `feature/lsposed/domain/.../LSPosedReportStatus.kt:35-44`：`hasReducedCoverage = !nativeAvailable || !nativeMapsAvailable || !nativeHeapAvailable || !zygotePermissionAvailable || !runtimeArtifactAvailable || !logcatAvailable || !dirtyPolicyAvailable || packageVisibility != FULL` —— 模块回的是 `AVAILABLE=1/HEAP_AVAILABLE=1/MAPS_HITS=0/MAPS_SCANNED=0/HEAP_HITS=0/HEAP_SCANNED=0`，缺新版字段 → 判 reduced coverage。
- `feature/nativeroot/domain/.../NativeRootReportStatus.kt:36-46`：`hasReducedCoverage = ksuSupercallBlocked || !ksuSupercallAttempted || !cgroupAvailable || pathDeniedCount>0 || !isolatedMountProbeAvailable || !ksuThroneHuntAvailable ...` —— 模块的合成串没有这些键。
- `feature/customrom/presentation/.../CustomRomCardHeader.kt:194` 同理（新版还新增了 `capability/earlypreload`、`ThroneHuntWatchNativeBridge` 等探针）。

### 0.4 第三处实测风险：SDK 谎报 `34 -> 29` 是活的

`Duck inventory SDK spoof active: SDK_INT 34->29 RESOURCES_SDK_INT 34->29` —— 而这个 APK 的 **targetSdk=37**。一个 targetSdk 37 的进程把自己报成 SDK 29，会在 Device Info 卡片（`SDK` vs `Release`）、store/权限分支上留下自相矛盾的展示面，属可被交叉核对的新增破绽。

---

## 1. 修复任务清单（v2.6.0）

> 顺序：P0-1 → P0-3 是本轮"USB 被检测"的直接收口；P0-2 决定其余卡片是否还挂黄标。P1 是非主进程/长尾。

### P0-1 桥接注册表修正 + 签名自愈 + 失败可见（`jni/module.cpp`）

1. 新建"桥接映射表"，**同时收录 `features.*` 与 `capability.*` 两套类名**（同一逻辑探针在两条分支上的位置不同，两份都要试）：
   ```c
   static const BridgeTarget kTargets[] = {
     // systemproperties
     { "com.eltavine.duckdetector.capability.systemproperties.data.SystemPropertiesNativeBridge",
       "com.eltavine.duckdetector.features.systemproperties.data.native.SystemPropertiesNativeBridge",
       "nativeCollectSnapshot", SIGS_STR_ARRAY, h_duck_props_snapshot },
     // package inventory (原 DangerousApps 能力)
     { "com.eltavine.duckdetector.capability.packageinventory.data.PackageDataDirectoryProbe",
       "com.eltavine.duckdetector.features.dangerousapps.data.native.DangerousAppsNativeBridge",
       "nativeStatPackages", SIGS_STR_ARRAY, h_duck_dangerous_stat },
     // native root（签名变了）
     { "com.eltavine.duckdetector.features.nativeroot.data.native.NativeRootNativeBridge", NULL,
       "nativeCollectSnapshot", SIGS_NOARG_AND_BOOL, h_duck_native_root_snapshot },
     ... // 其余 6 个保持现有类名（实测已能注册）
   };
   ```
2. `register_app_native_method()` 改为**签名候选数组**逐个尝试（先 `()`，再 `(Z)`；先 `([Ljava/lang/String;)Ljava/lang/String;` 再其它），任一成功即停；每次失败都要 `LOGI("bridge_missing=%s::%s sig=%s", cls, method, sig)`，并把 `ready=N/M` 里 M 改成"目标表长度"（不要硬编码 9）。
3. 类加载失败要单独打日志：`load_app_class` 返回 null 时打 `LOGI("bridge_class_missing=%s")`（现在完全静默，就是坑）。
4. 验收：`su -c "logcat -d -s DuckVisBypass | grep -E 'ready=|bridge_missing|bridge_class_missing'"`
   → 期望 `ready=9/9`，且**没有** `E ... Failed to register native method`（可用 `logcat -d | grep 'Failed to register native method'` 复查）。

### P0-2 合成快照字段升级（消除 reduced coverage 黄标）

- 以设备版 parser 为准重建键集合（逐卡片核对）：
  - LSPosed：至少提供 `nativeAvailable / nativeMapsAvailable / nativeHeapAvailable / MAPS_SCANNED>0 / HEAP_SCANNED>0`；
  - NativeRoot：至少提供 `KSU_SUPERCALL_ATTEMPTED=1`、`CGROUP_AVAILABLE=1`、`ISOLATED_MOUNT_PROBE_AVAILABLE=1`、`KSU_THRONE_HUNT_AVAILABLE=1`、`PATH_DENIED=0`（对照 `NativeRootReportStatus.kt:36-46`）；
  - CustomRom：对照 `CustomRomNativeBridge.kt` 的 parse 分支补齐（`PROPAREA_*`、`SYMBOL_AVAILABLE` 等）；
  - 建议做法：把每个桥接的 `parse()` 里"可用性字段"全部枚举出来，逐个回填"可用且无异常"的值（不要留 0/缺键）。
- 验收：仪表盘 LSPosed / Native Root / Custom ROM 三张卡不再出现 `reduced coverage`；Duck 的 `Top findings` 里不再出现 "Review ... next"。

### P0-3 USB/开发者属性"整行消除"开关（收口本轮现象）

- `inventory.conf` 新增 `hide_adb_usb_rows=1`（默认 1）。命中 `persist.sys.usb.config` / `sys.usb.config` / `sys.usb.state` 时**四个来源一致返回空**：
  - `h_sp_get1/2` → 返回空串（`NewStringUTF("")`，不要返回 nullptr）；
  - `h_duck_props_snapshot` → **不输出该 PROP 行**；
  - getprop 重定向 → 保持整行过滤（现状即可）；
  - JVM → 本来就空。
- 效果：`SystemPropertiesRepository` 里 `preferredValue.isBlank()` → 整条规则跳过 → **`persist.sys.usb.config` 行与 `Diverged` 行一起消失**，`Review` 归 0。
- 保留 `ro.adb.secure=1`、`ro.secure=1`、`ro.debuggable=0`、`service.adb.root=0` 继续回"安全值"（这几条实测都显示绿）。

### P0-4 getprop 子进程重定向保持语义（修旧问题）

- `module.cpp:503-518, 520-569`：识别 `getprop <name>` 单属性调用 → 只输出该属性（按真实 `getprop` 的 stdout 形态），不要把"全量 dump 过滤版"塞给单属性调用；全量 dump 场景改成**值级替换**（`persist.sys.usb.config`→`mtp`）而不是丢行，保证与 Java 源一致、不制造假 mismatch。

### P0-5 SDK 谎报默认关闭 / 门控

- 实测它现在无条件生效（`34->29`，而 APK targetSdk=37）。改为：默认 `inventory_sdk_fallback=0`；仅当 `inventory_worker.sh` 的 MIUI `MIUIOP(10022)` 授权失败且清单数异常时才 opt-in，并打日志区分。
- 验收：`logcat -s DuckVisBypass | grep "SDK spoof"` 默认不再出现；Duck 的 Device Info 卡片 `SDK` 与真机一致；Package visibility 卡片仍不报警（靠 appop）。

### P1-1 非主进程覆盖（实测存在且未覆盖）

真机进程树（都从 `zygote64` fork，但模块 allowlist 只放行主进程与 `:zygisk_fd_detector`）：

```
u0_a372 23495 1213 com.eltavine.duckdetector                    ← 已 hook
u0_a372 23810 1213 com.eltavine.duckdetector:zygisk_fd_detector  ← 已 hook（但未 hook fd trap 的 JNI）
u0_a372 23848 1213 com.eltavine.duckdetector:helper_probe        ← 被 skip（日志：skip helper process outside allowlist）
+ 23905 / 23877 / 23763 ... 其它 helper 进程                      ← 同样被 skip
```
- allowlist 增加 `:virtualization_probe`、`:helper_probe`（新版 `capability/helperprocess` 会把 `VirtualizationNativeBridge` 搬到 `capability.helperprocess.data.VirtualizationNativeBridge`，在该进程内直接跑 `collectSnapshot()`）。
- 若确认这些进程会读属性/挂载，再补 P1-2 的 payload 净化。

### P1-2 回传 payload 文本净化（isolated / app_zygote 无法注入时的兜底）

- 主进程 Binder 回放净化里增加"键值定点改写"：`PROC_MOUNT_VIEW_DIVERGENT=1`→`=0`、`PROC_MOUNT_VIEW_TOKEN_HIT=1`→`=0`、`TOKEN_DETAIL=`/`DETAIL=` 行内容清空（保持长度），以及 `/data/adb`→等长占位、`magisk`/`kernelsu`/`KSU` 等 token 等长替换（UTF-16 同步处理）。

### P1-3 新增桥接（按设备版类名，逐个补）

`capability.helperprocess.data.VirtualizationNativeBridge`（6 方法）、`feature.zygisk.data.fdtrap.ZygiskFdTrapNativeBridge`（`nativeVerifyTrapFd(I)I` 等 4 个）、`feature.mount.data.native.MountNativeBridge`、`capability.earlypreload.data.EarlyMountPreloadBridge` / `EarlyVirtualizationPreloadBridge`（一批 getter）、`feature.customrom.data.native.CustomRomNativeBridge`、`feature.kernelcheck.data.native.KernelCheckNativeBridge`、`feature/nativeroot.data.native.ThroneHuntWatchNativeBridge`、`feature.selinux/tee/widevine` 系列。

### P1-4 Binder 请求侧只做必要工作

`h_binder_transact` 对带 fd 的 parcel（fd trap 会传 `ParcelFileDescriptor`）跳过 marshall/请求改写，避免无意义开销与副作用。

### P2

- 重试窗口从"一次性 ~16s"改为"启动后 60s 内每 2s + 之后每 30s 兜底"（把 `newly>0` 的成功面保住，同时覆盖晚加载类）。
- 合成快照里的 `PROP_AREA_CONTEXTS` 报**真实** context 计数（现在硬编码 0，和 customrom 那张卡的 raw 扫描口径互相矛盾）。
- 别名表（`kPackageAliases`）里 `com.sukisu.ultra -> com.android.wall` 这类**设备上不存在的包名**会留在可见清单里，建议换成设备真实存在且等长的系统包名。

---

## 2. 验收清单（改完逐条跑，全部通过才算"过了"）

```sh
# 1) 注入与注册
adb shell am force-stop com.eltavine.duckdetector
adb shell monkey -p com.eltavine.duckdetector -c android.intent.category.LAUNCHER 1
adb shell "sleep 8; logcat -d -s DuckVisBypass"
#   期望：SystemProperties JNI hook installed + Binder/UNIXProcess/ProcessImpl
#         ready=9/9（P1 后为 N/N），且无 bridge_missing / bridge_class_missing
adb shell "logcat -d | grep -a 'Failed to register native method'"   # 期望：无输出

# 2) USB / 开发者属性（App 内 System Properties 卡）
#   期望：Review = None；不出现 persist.sys.usb.config 行，也不出现 Diverged 行
#        Critical=None / Boot=Clean / Build=Clean

# 3) 其它卡片
#   期望：LSPosed / Native Root / Custom ROM 不再显示 "has reduced coverage"
#        仪表盘 Top findings 不再出现 "Review ... next"

# 4) SDK 谎报
adb shell "logcat -d -s DuckVisBypass | grep -i 'SDK spoof'"        # 期望：默认无

# 5) 非 Duck 进程不受影响
adb shell getprop persist.sys.usb.config                            # 期望：adb（真值，模块只作用于 Duck 进程）
adb shell "cmd appops get com.eltavine.duckdetector 10022"          # 期望：allow（MIUI）
```

出现任一不符时的定位法：`logcat -d | grep -a -e DuckVisBypass -e 'Failed to register native method'`，再对照 §0.2 的三处失配表。

---

## 3. 需要你确认/注意

1. **源码分支**：设备装的是 `origin/main`（`4f26d5f4`，多模块 `feature/*`、`capability/*` 布局），你本地 checkout 在 `master`（`d8477d35`，单 `app/` 布局）。改模块时**以 `origin/main` 的类名为准**；看某个文件用 `git show 4f26d5f4:<path>`。
2. 本轮我只做了只读排查（`getprop`/`logcat`/`uiautomator`/进程与映射检查）与重启一次 Duck 进程；**没有改代码、没有重装模块、没有重启设备**。要我按 §1 落地 P0-1~P0-3 并编译、装机、复测（需要重启手机）请说一声。
3. `evidence\` 里留了本次实测快照：`duck_ui.xml`/`duck_sp.xml`/`duck_sp2.xml`（界面层级）、`extract_text*.ps1`（文本抽取脚本）。不需要可直接删。

---

## 4. 最终交付记录（2026-09-29 收尾，代码已冻结）

设备上当前生效版本：**anti_dev_pm_zygisk v2.6.3**（`/data/adb/modules/anti_dev_pm_zygisk/module.prop`），由 KernelSU 安装、重启后生效。`jni/module.cpp` 与 `module/module.prop` 自 v2.6.3 起**冻结，不再改动**。

| 版本 | 关键改动 | 实测证据（设备 logcat） |
|---|---|---|
| v2.6.0 | 桥接表重写（多候选类名 + 多候选签名，逐条 `bridge_ok`/`missing` 日志）；USB 属性四通道一致置空（`hide_adb_usb_rows`，默认 1）；LSPosed/NativeRoot 快照补齐新键；新增 ThroneHunt watch hook | `bridge_ok props via ...capability.systemproperties.data.SystemPropertiesNativeBridge`；`ready=12/13 missing=[throne_hunt_reset]` |
| v2.6.1 | 去掉 `nativeResetWatch` 注册（安装版 APK 无此 JNI 方法，ART dump 已证实） | `ready=12/12 missing=[]`，ART 无 `Failed to register native method` |
| v2.6.2 | `read_real_system_property` 改走 `__system_property_find`+`read_callback`（保留 legacy 兜底），并加快照诊断日志 | `props real-read mode=none (find=0 callback=0 legacy=0)`、`props snapshot: keys=57 non_blank=6` ← 暴露真实问题 |
| v2.6.3 | 用显式 `dlopen("libc.so")` 句柄解析 libc 属性符号 + 链接期符号兜底（Zygisk 模块库看不到 `RTLD_DEFAULT` 里的 libc） | `props real-read mode=libc-callback (libc=1 find=1 callback=1 legacy=1)`、`props snapshot: keys=57 non_blank=33`、`ready=12/12` |

验收结论：

- `persist.sys.usb.config` 行与 `Source mismatch` 告警消失，仪表盘 `Clear / No urgent findings`，System Properties 卡 `Critical: None / Review: None`（用户已自行确认 adb 这部分通过）。
- 非 Duck 进程不受影响：`adb shell getprop persist.sys.usb.config` → `adb`（真值）。
- MIUI inventory：`MIUIOP(10022): allow`。
- 设备侧临时文件（`/sdcard/*.xml`、`/data/local/tmp/*.zip`）已清理；本机 `evidence\` 保留证据与抽取脚本。

任务状态映射：P0-1 ✅ 完成；P0-3 ✅ 完成（默认开启，`inventory.conf` 里 `hide_adb_usb_rows=0` 可关）；P0-2 部分完成（LSPosed `MAPS_AVAILABLE`、NativeRoot 新键已补；**Custom ROM / Native Root 的 `reduced coverage` 按用户决定暂缓，不再处理**）；P0-4 / P0-5 / P1-1~P1-4 未执行（用户要求冻结，避免引入新问题）。

保留的可选项（需用户点头才动）：

- 想回退：本机 `anti_dev_pm_zygisk-v2.6.2.zip` 是上一个已知良好版本；`v2.6.0/v2.6.1` 已被 v2.6.3 取代。
- `README.md` 仍描述 v2.5.7 行为（9 个桥接、USB 回 `mtp`、PLT 相关描述），与现状不符；按"不再改动"要求本次未动它。
