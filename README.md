# oppowatch-ghostlock-ksu

KernelSU 管理器在 **OPPO Watch 3（OWW212，Android 11 / armv7 / Linux 5.4）** 上的移植版，
包名 **`cn.classfruit.ksu`**。

本仓库只包含管理器与 GhostLock 的接入层，**不包含 CVE-2026-43499（GhostLock）漏洞利用代码**。
根权限由设备所有者自行通过 GhostLock 获取，管理器只负责接管它的 root shell。

## 工作方式

- 越狱 root 由 GhostLock 在设备上留下：`127.0.0.1:25555` 的 uid=0 shell。
- 管理器启动时探测该端口并执行 `id -u`，**只有 uid=0 才算接入**；未接入时首页显示“未越狱”，
  不显示电源菜单、模块安装等 root 专属入口。
- root 命令通过 `manager/app/src/main/jniLibs/armeabi-v7a/libksud.so`（`manager/tools/jailsu.c`
  编译的 TCP 代理）交给 libsu；`manager/app/src/main/assets/ksud-shim.sh` 实现 ksud 子集命令。

## 已实现

- 手表 UI：保留 KernelSU 原布局，按 372×430 屏幕缩放（`pageScale` 默认 0.62）。
- 应用列表：ColorOS Watch 对第三方应用屏蔽 `getInstalledPackages()` 枚举（即使 targetSdk 29 也返回 0），
  因此改为：
  - root 下 `pm list packages -U`（完整 stdout）+ 按包名 `getPackageInfo(name, 0)` 取图标/名称；
  - 无 root 时扫描 `/system/app`、`/system/priv-app`、`/product/*` 等系统 APK，用
    `getPackageArchiveInfo()` 解析包名，再按名查询；launcher activity 补齐用户应用。
- 模块管理：列表/安装 zip/启用/禁用/卸载/执行 `action.sh`（脚本型）。
- GhostLock 能力适配：libsu 需要 stdout/stderr 各自收到结束 UUID，代理会把重复 UUID 分流到 stderr。

## 限制

- 没有 armv7 5.4 的 KernelSU 内核模块，GhostLock root 也没有 `CAP_SYS_ADMIN`：
  `system/` 覆盖、Zygisk、LSPosed 等依赖 mount 的模块**不会生效**。
- `post-fs-data.sh` / `service.sh` 目前不会开机自动执行，只支持手动 `action.sh`。
- GhostLock 缺 `CAP_DAC_OVERRIDE` / `CAP_CHOWN`，涉及改属主或写非 root 所有目录的模块脚本会失败。
- 真机 GhostLock root 下的最终联调仍需在设备上完成。

## 构建

需要 JDK 25（Android Studio JBR）、Android SDK（compileSdk 37）与 build-tools 36.0.0。

```bash
cd manager
./gradlew :app:assembleRelease
```

产物在 `manager/app/build/outputs/apk/release/`。仓库已包含预编译的 `libksud.so`；
如需重编代理（NDK r29），运行 `manager/tools/build-jailsu.sh`。

release 构建当前使用本机 Android debug keystore 签名，仅适合自用安装。

## 许可

基于 [KernelSU](https://github.com/tiann/KernelSU)，遵循 GPL-3.0，见 `LICENSE`。
GhostLock 漏洞利用不属于本仓库，使用风险自负。
