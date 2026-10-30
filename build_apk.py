#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
精灵点击器 一键构建脚本：编译带正式签名的 release APK，并做体积门禁。

用法：
    python build_apk.py                      # 编译 release，复制 APK 到根目录
    python build_apk.py --install            # 编译后安装到已连接设备
    python build_apk.py --install --device <序列号>   # 多设备时明确指定目标
    python build_apk.py --clean              # clean 后全新编译

签名：密钥库根目录按以下顺序找（找到即用）：
    1) 环境变量 JINN_KEYSTORE_ROOT（**设了就只用它**，打错路径会明确报错）
    2) <workspace>/GLOBAL/credentials/JinnKeyStores
    3) %USERPROFILE%\JinnKeyStores
  根目录下按包名取密钥：<根目录>/<applicationId>/release.jks，密码读同级 JinnPassword.md。
  任何密钥与口令都不写入仓库文件。

体积门禁：APK 超过 2.5MB 直接失败退出（设计硬指标，见 docs/设计方案.md 第 5 节）。
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent
APPLICATION_ID = "com.blackberry.keyboard"
MAX_APK_BYTES = int(2.5 * 1024 * 1024)
DEFAULT_JDK = r"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"


def keystore_root_candidates():
    env = (os.environ.get("JINN_KEYSTORE_ROOT") or "").strip()
    if env:
        return [Path(env)]
    return [
        ROOT.parent.parent / "GLOBAL" / "credentials" / "JinnKeyStores",
        Path.home() / "JinnKeyStores",
    ]


def resolve_keystore_root():
    tried = keystore_root_candidates()
    for root in tried:
        if (root / APPLICATION_ID / "release.jks").is_file():
            return root
    raise RuntimeError(
        "未找到签名密钥（已试过下列位置）：\n"
        + "\n".join(f"  - {root / APPLICATION_ID / 'release.jks'}" for root in tried)
        + "\n  请设置 JINN_KEYSTORE_ROOT 指向密钥库根目录后重试，例如：\n"
        r"  set JINN_KEYSTORE_ROOT=<密钥库根目录>"
    )


def load_signing_env():
    """读出本包密钥与口令，仅注入当前进程环境，不落盘、不打印。"""
    root = resolve_keystore_root()
    keystore = root / APPLICATION_ID / "release.jks"
    password_file = root / "JinnPassword.md"
    if not password_file.is_file():
        raise RuntimeError(f"未找到签名密码文件: {password_file}")

    text = password_file.read_text(encoding="utf-8")

    def find_option(option):
        for line in text.splitlines():
            if not re.search(rf"`-{option}`", line, re.IGNORECASE):
                continue
            cells = [cell.strip().strip("`") for cell in line.split("|")]
            values = [cell for cell in cells[1:] if cell]
            if values:
                return values[-1]
        raise RuntimeError(f"签名密码文件缺少 -{option} 配置")

    os.environ.update({
        "JINN_KEYSTORE_FILE": str(keystore),
        "JINN_KEYSTORE_PASSWORD": find_option("storepass"),
        "JINN_KEY_PASSWORD": find_option("keypass"),
        "JINN_KEY_ALIAS": APPLICATION_ID,
    })
    return keystore


def setup_java():
    """显式锁定 JDK 21：PATH 里 JDK 25 排在前面，会让 Gradle 8.9 直接拒绝启动。"""
    java_home = (os.environ.get("JINN_JAVA_HOME") or DEFAULT_JDK).strip()
    if not Path(java_home, "bin", "java.exe").is_file() and not Path(java_home, "bin", "java").is_file():
        print(f"[错误] 未找到 JDK: {java_home}", file=sys.stderr)
        sys.exit(1)
    os.environ["JAVA_HOME"] = java_home
    os.environ["PATH"] = str(Path(java_home, "bin")) + os.pathsep + os.environ.get("PATH", "")
    return java_home


def find_gradle():
    """项目 wrapper 优先（版本与仓库锁定），其次 GRADLE_HOME / PATH。"""
    if os.name == "nt":
        names, exe = ("gradlew.bat", "gradlew"), "gradle.bat"
    else:
        names, exe = ("gradlew", "gradlew.bat"), "gradle"
    candidates = [ROOT / name for name in names]
    candidates.append(Path(os.environ.get("GRADLE_HOME", "")) / "bin" / exe)
    found = shutil.which(exe) or shutil.which("gradle")
    if found:
        candidates.append(Path(found))
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    return None


def sdk_root_candidates():
    local = os.environ.get("LOCALAPPDATA") or str(Path.home() / "AppData" / "Local")
    raw = [
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
        r"C:\Android\sdk",
        os.path.join(local, "Android", "Sdk"),
        str(Path.home() / "Android" / "Sdk"),
    ]
    seen, out = set(), []
    for item in raw:
        if not item or not str(item).strip():
            continue
        item = str(item).strip()
        key = os.path.normcase(os.path.normpath(item))
        if key not in seen:
            seen.add(key)
            out.append(item)
    return out


def find_build_tool(name):
    candidates = []
    for sdk_root in sdk_root_candidates():
        build_tools = Path(sdk_root) / "build-tools"
        if build_tools.is_dir():
            candidates.extend(path / name for path in build_tools.iterdir() if path.is_dir())

    def version_key(path):
        return tuple(int(chunk) if chunk.isdigit() else -1 for chunk in path.parent.name.split("."))

    candidates.sort(key=version_key, reverse=True)
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    found = shutil.which(name) or shutil.which(Path(name).stem)
    if found:
        return found
    raise RuntimeError(f"未找到 Android SDK 工具: {name}")


def run(cmd, cwd=None):
    print(f"> {cmd}")
    result = subprocess.run(cmd, cwd=cwd, shell=True)
    if result.returncode != 0:
        print(f"[错误] 命令失败: {cmd}", file=sys.stderr)
        sys.exit(result.returncode)


def parse_adb_devices(output):
    """只认第二列恰为 device 的行：表头与 no permissions 行也含 device 子串。"""
    devices = []
    for line in output.splitlines():
        line = line.strip()
        if not line or line.startswith("List of devices"):
            continue
        cols = line.split()
        if len(cols) >= 2 and cols[1] == "device":
            devices.append(cols[0])
    return devices


def verify_apk(apk):
    apksigner = find_build_tool("apksigner.bat" if os.name == "nt" else "apksigner")
    zipalign = find_build_tool("zipalign.exe" if os.name == "nt" else "zipalign")

    result = subprocess.run(
        [apksigner, "verify", "--verbose", str(apk)],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    output = result.stdout + result.stderr
    if result.returncode != 0:
        print(output, file=sys.stderr)
        print("[错误] APK 签名校验失败", file=sys.stderr)
        sys.exit(1)
    # minSdk 28：Android 9+ 全部支持 v3，AGP 不再单独生成 v2 块；校验以 v3 为准。
    if not re.search(r"v3\s+scheme[^:]*:\s*true", output, re.IGNORECASE):
        print(output, file=sys.stderr)
        print("[错误] APK 未启用 v3 签名", file=sys.stderr)
        sys.exit(1)
    print("签名校验: V1=false V3=true (minSdk 28)")

    align = subprocess.run(
        [zipalign, "-c", "4", str(apk)],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if align.returncode != 0:
        print(align.stdout, file=sys.stderr)
        print("[错误] 产物未通过 4 字节对齐校验", file=sys.stderr)
        sys.exit(1)
    print("对齐校验: 4 字节对齐通过")


def ensure_dex_compressed(apk, keystore):
    """minSdk≥28 时 AGP 把 dex 不压缩打包；这里重压缩 dex 再重签名，减小下载体积。

    只改 dex 的存储方式，其余不动；重签名后 v2/v3 由 apksigner 重新生成。
    """
    with zipfile.ZipFile(apk) as z:
        dex_entries = [i for i in z.infolist() if i.filename.endswith(".dex")]
        if not dex_entries or all(i.compress_type == zipfile.ZIP_DEFLATED for i in dex_entries):
            return apk  # 已是压缩态，无需处理

    repacked = apk.with_name("app-release.repacked.apk")
    aligned = apk.with_name("app-release.aligned.apk")
    signed = apk.with_name("app-release.signed.apk")
    for p in (repacked, aligned, signed):
        if p.exists():
            p.unlink()

    with zipfile.ZipFile(apk) as zin, zipfile.ZipFile(repacked, "w") as zout:
        for item in zin.infolist():
            name = item.filename
            # v1 已禁用：丢掉旧的 v1 签名文件，避免残留失效条目
            upper = name.upper()
            if upper.startswith("META-INF/") and upper.endswith((".RSA", ".SF", ".MF")):
                continue
            data = zin.read(name)
            # resources.arsc 必须保持不压缩（Android 11+ 要求），其余压缩
            ctype = zipfile.ZIP_STORED if name.endswith((".arsc", ".so")) else zipfile.ZIP_DEFLATED
            zout.writestr(item, data, compress_type=ctype)

    zipalign = find_build_tool("zipalign.exe" if os.name == "nt" else "zipalign")
    apksigner = find_build_tool("apksigner.bat" if os.name == "nt" else "apksigner")
    subprocess.run([zipalign, "-f", "-p", "4", str(repacked), str(aligned)], check=True)
    subprocess.run(
        [
            apksigner, "sign",
            "--ks", str(keystore),
            "--ks-pass", "pass:" + os.environ.get("JINN_KEYSTORE_PASSWORD", ""),
            "--key-pass", "pass:" + os.environ.get("JINN_KEY_PASSWORD", ""),
            "--ks-key-alias", os.environ.get("JINN_KEY_ALIAS", ""),
            "--min-sdk-version", "28",
            "--v1-signing-enabled", "false",
            "--v2-signing-enabled", "true",
            "--v3-signing-enabled", "true",
            "--out", str(signed), str(aligned),
        ],
        check=True,
    )
    for p in (repacked, aligned):
        if p.exists():
            p.unlink()
    shutil.move(str(signed), str(apk))
    print(f"dex 已压缩并重签: {apk.stat().st_size:,} 字节")
    return apk


def main():
    parser = argparse.ArgumentParser(description="构建正式签名 release APK")
    parser.add_argument("--install", action="store_true", help="编译后安装到设备")
    parser.add_argument("--device", help="安装目标设备序列号（多设备必须指定；也可用 JINN_ADB_DEVICE）")
    parser.add_argument("--clean", action="store_true", help="clean 后全新编译")
    args = parser.parse_args()

    gradle = find_gradle()
    if not gradle:
        print("[错误] 找不到 gradle（项目 wrapper 缺失且 PATH 无 gradle）", file=sys.stderr)
        sys.exit(1)
    java_home = setup_java()
    try:
        keystore = load_signing_env()
    except (OSError, RuntimeError) as exc:
        print(f"[错误] {exc}", file=sys.stderr)
        sys.exit(1)

    print(f"Gradle: {gradle}")
    print(f"JDK: {java_home}")
    print(f"签名密钥: {keystore}")

    tasks = ["clean", "assembleRelease"] if args.clean else ["assembleRelease"]
    run(f'"{gradle}" {" ".join(tasks)}', cwd=str(ROOT))

    output_dir = ROOT / "app" / "build" / "outputs" / "apk" / "release"
    apk = output_dir / "app-release.apk"
    if not apk.exists():
        # 无签名配置时 AGP 产出 -unsigned；本项目要求正式签名，缺签名即视为构建失败
        unsigned = output_dir / "app-release-unsigned.apk"
        if unsigned.exists():
            print("[错误] 产物未签名（检查 JINN_KEYSTORE_* 环境变量）", file=sys.stderr)
        else:
            print(f"[错误] 产物不存在: {apk}", file=sys.stderr)
        sys.exit(1)

    apk = ensure_dex_compressed(apk, keystore)
    verify_apk(apk)

    size = apk.stat().st_size
    print(f"APK 体积: {size:,} 字节（{size / 1024:.0f} KB，上限 {MAX_APK_BYTES:,}）")
    if size > MAX_APK_BYTES:
        print(
            f"[错误] APK 体积 {size:,} 字节超过 2.5MB 门禁，请排查依赖与资源",
            file=sys.stderr,
        )
        sys.exit(1)

    output_apk = ROOT / f"{APPLICATION_ID}.apk"
    shutil.copy2(apk, output_apk)
    print(f"已生成: {output_apk} ({size / 1024:.0f} KB)")

    if args.install:
        result = subprocess.run("adb devices -l", shell=True, capture_output=True, text=True)
        if result.returncode != 0 or "List of devices attached" not in result.stdout:
            print(
                "[错误] adb 不可用（不在 PATH 上，或 adb server 没起来）：\n"
                f"  {(result.stderr or result.stdout or '').strip()[:500]}",
                file=sys.stderr,
            )
            sys.exit(1)
        devices = parse_adb_devices(result.stdout)
        if not devices:
            print("[错误] 未检测到可用设备（adb devices -l 中没有状态为 device 的行）", file=sys.stderr)
            sys.exit(1)
        device = args.device or os.environ.get("JINN_ADB_DEVICE")
        if not device:
            if len(devices) > 1:
                print(
                    f"[错误] 检测到 {len(devices)} 台设备（{'、'.join(devices)}），"
                    f"请用 --device <序列号> 或 JINN_ADB_DEVICE 指定目标",
                    file=sys.stderr,
                )
                sys.exit(1)
            device = devices[0]
        if device not in devices:
            print(f"[错误] 指定设备 {device} 不在已连接列表（{'、'.join(devices)}）", file=sys.stderr)
            sys.exit(1)
        print(f"安装到: {device}")
        run(f'adb -s {device} install -r "{output_apk}"')
        print("安装完成")


if __name__ == "__main__":
    main()
