# 沙箱原生程序:来源、许可证与源码发布

Mika 的沙箱带三个可执行文件,都在 `app/src/main/jniLibs/arm64-v8a/`,随 APK 安装到 `nativeLibraryDir`。
Mika 只以子进程方式运行它们,不链接它们。

| 文件 | 是什么 | 来源 | 版本 | 许可证 |
|---|---|---|---|---|
| `libproot.so` | PRoot 本体,静态链接 talloc | PRoot:`sources.lock` 里的 `proot` 源码包,加 `patches/proot-*.patch`;talloc:`sources.lock` 里的 `talloc` 源码包,只编 `talloc.c`,未修改 | PRoot 5.4.0、talloc 2.4.3 | GPL-2.0-or-later(PRoot)、LGPL-3.0-or-later(talloc) |
| `libproot_loader.so` | PRoot 的载入器(静态、`-nostdlib`) | 同一个 PRoot 源码包 | 5.4.0 | GPL-2.0-or-later |
| `libmikarun.so` | Mika 的沙箱启动器(超时、断网、清场) | `app/src/main/cpp/mikarun/mikarun.c` | 随 Mika | MIT |

许可证全文在仓库根目录的 `licenses/`,版权声明见 `THIRD_PARTY_NOTICES.md`。

## 自带的运行环境

运行环境(Alpine 3.22 最小根文件系统 + Python、Node 等软件包)随 APK 发布,在
`app/src/main/assets/runtime/rootfs.tar.xz`。手机首次启动 Mika 时在后台解到 `files/sandbox/rootfs`,不联网。

- `runtime/sources.lock`:最小根文件系统的地址与 SHA-256,以及每个软件包的仓库、版本、APKINDEX 校验值、`.apk` 的 SHA-256。
- `build-runtime.py`:按 `sources.lock` 下载、校验后拼成 `rootfs.tar.xz`。全程不运行 apk:按 apk 的布局解开软件包,
  并用软件包签过名的元数据写出 `/lib/apk/db/installed`,所以运行环境里的 apk 看到的数据库是一致的。
  每个 `.apk` 都要核对:锁文件里的 SHA-256、它自己的 RSA 签名(公钥取自钉住的最小根文件系统)、控制段校验值与 APKINDEX 一致、
  数据段与签过名的 `.PKGINFO` 里的 `datahash` 一致。会在手机上执行的安装脚本与触发器必须逐个列进 `SCRIPTS_NOT_RUN`
  (按内容的 SHA-256 钉住,并写明为什么不跑也正确),否则构建失败。
  同时生成 `app/src/main/java/com/wangzan101/mika/cap/sandbox/BundledRuntime.kt`(版本、SHA-256、大小)与
  `app/src/main/assets/runtime/packages.json`(所有软件包、版本、许可证;「开源许可」页从它生成)。
- 产物逐字节可重现:

```bash
python3 tools/sandbox/build-runtime.py --resolve   # 换 Alpine 小版本或软件包后重新求依赖,改写 runtime/sources.lock
python3 tools/sandbox/build-runtime.py             # 生成 rootfs.tar.xz、BundledRuntime.kt、packages.json
python3 tools/sandbox/build-runtime.py --check     # 重新生成并与仓库里的产物比较,不一致退出 1
```

`build-runtime.py` 里的 `WANT` 必须与 core 的 `SANDBOX_PACKAGES` 一致(`BundledRuntimeTest` 核对)。

## 文件

- `sources.lock`:每行 `名称 版本 下载地址 SHA-256`。编译和打源码包都只用这里列出的源码包,下载后先核对 SHA-256。
- `patches/`:对上游源码的修改,文件名 `<名称>-<序号>-<说明>.patch`,按文件名顺序以 `patch -p1` 打上。补丁跟随被修改组件的许可证。
  - `proot-0001-exitkill.patch`:给 PRoot 的跟踪选项加上 `PTRACE_O_EXITKILL`。这样 PRoot 退出时(即使被 SIGKILL 杀掉),内核会杀掉所有被跟踪的进程,沙箱里的命令不会在后台继续运行。
  - `proot-0002-seccomp-trap-enosys.patch`:安卓给 App 装的 seccomp 策略对新系统调用(io_uring、clone3、faccessat2、epoll_pwait2、rseq 等)发 SIGSYS,且比后装的任何过滤器优先,沙箱里的程序一试探就被杀。PRoot 在这种 SIGSYS(`si_code == SYS_SECCOMP`)的信号停止处把返回值改成 `-ENOSYS` 并取消信号,程序走自己的退路;arm64 上只带 `AT_EACCESS` 的 faccessat2 改发成 faccessat(App 进程实际与有效身份总相同,两者等价),权限判断仍由内核给出。
  - `proot-0003-arm64-new-path-syscalls.patch`:PRoot 5.4.0 的 arm64 调用号表只到 statx(291),之后新增的调用 PRoot 认不出,平台放行时它们带着没转换的路径直达内核,按宿主路径执行(例如 musl 的 `faccessat(…, AT_EACCESS)` 先试 faccessat2,判断的是错误的文件)。补上这些编号并加进 PRoot 的 seccomp 跟踪列表:faccessat2、fchmodat2 与 faccessat、fchmodat 一样转换路径;execveat(会绕过 execve 的处理)、openat2(`RESOLVE_*` 规则针对沙箱内的目录树)、io_uring_setup(路径操作不经系统调用)、新挂载接口(open_tree、move_mount、fspick、mount_setattr、open_tree_attr)与 setxattrat 等 *xattrat、file_getattr/file_setattr 一律返回 `-ENOSYS`,调用方退回老的调用,由 PRoot 正常转换。同时修正查表时的越界判断(`index > length` 改为 `>=`)。
  - `proot-0004-link2symlink-fstat-nonfile.patch`:link2symlink 扩展在 fstat 返回时把描述符的 `/proc` 链接当路径 lstat;管道、套接字等(`pipe:[…]`)lstat 失败后,原代码接着读未初始化的 stat 结构,随栈上残留值不同,时而把成功的 fstat 改成 -1(EPERM)。启动时检查标准描述符的程序(node 会直接 abort)因此随机失败。改为 lstat 失败即不处理;`" (deleted)"` 后缀只在读到链接后检查;链接目标消失时返回 -errno 而不是 -1。
- `build-native.sh`:从 `sources.lock` 下载源码、核对 SHA-256、打补丁,用 NDK 28.2.13676358(API 30)交叉编译三个程序。

## 重现编译

```bash
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/28.2.13676358
tools/sandbox/build-native.sh           # 编译并覆盖 app/src/main/jniLibs/arm64-v8a/ 里的产物
tools/sandbox/build-native.sh --check   # 只重编并与仓库里的产物比较 SHA-256,不一致退出 1
```

编译时会做路径前缀映射,并且不写 build-id,所以同一个 NDK 编出的产物逐字节相同。用 `--check` 可以确认仓库里的二进制确实来自这些源码。

## 发布 APK 时一起发布源码包

每次发布 APK,都要把 `sources.lock` 列出的源码包、`patches/`、编译脚本和许可证打包成 `mika-<版本>-gpl-sources.tar.gz`,和 APK 放在同一个发布页。在仓库根目录运行:

```bash
VERSION=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
NAME=mika-$VERSION-gpl-sources
OUT=build/gpl-sources/$NAME
rm -rf "$OUT" && mkdir -p "$OUT"
while read -r name version url sum; do
  case "$name" in ''|'#'*) continue ;; esac
  curl -fsSL "$url" -o "$OUT/$name-$version.tar.gz"
  echo "$sum  $OUT/$name-$version.tar.gz" | shasum -a 256 -c -
done < tools/sandbox/sources.lock
cp -R tools/sandbox/patches tools/sandbox/sources.lock tools/sandbox/build-native.sh tools/sandbox/README.md "$OUT/"
cp licenses/GPL-2.0.txt licenses/GPL-3.0.txt licenses/LGPL-3.0.txt "$OUT/"
# 运行环境里每个 GPL/LGPL/MPL 软件包:Alpine 的构建配方(钉在软件包记录的 aports 提交)与上游源码,逐个按 SHA-512 核对
python3 tools/sandbox/runtime-sources.py "$OUT/runtime"
cp tools/sandbox/build-runtime.py tools/sandbox/runtime-sources.py app/src/main/assets/runtime/packages.json "$OUT/runtime/"
cp -R tools/sandbox/runtime "$OUT/runtime/lock"
COPYFILE_DISABLE=1 tar -czf "build/gpl-sources/$NAME.tar.gz" -C build/gpl-sources "$NAME"
```

运行后得到 `build/gpl-sources/mika-<版本>-gpl-sources.tar.gz`。上传前用 `tar -tzf` 看一眼,确认里面有两个源码包、`patches/`,
以及 `runtime/` 下每个 GPL/LGPL/MPL 软件包的目录和 `runtime/SOURCES.txt`(gcc 等源码较大,整包约数百 MB)。
