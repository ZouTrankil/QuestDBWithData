#!/usr/bin/env python3
"""检查公开配置、当前跟踪文件及指定发行包；只输出位置，不输出凭据。"""
import argparse
import io
import subprocess
import zipfile
from pathlib import Path
import yaml

ROOT = Path(__file__).resolve().parents[1]


def check(packages=()):
    errors = []
    public_files = [ROOT / "config/application.yml",
                    *sorted((ROOT / "config/markets").glob("*.yml")),
                    *sorted((ROOT / "config/apps").glob("*.yml"))]

    def walk(value, path=""):
        if isinstance(value, dict):
            for key, child in value.items():
                walk(child, f"{path}.{key}" if path else key)
        elif isinstance(value, list):
            for child in value:
                walk(child, path)
        elif any(word in path.lower() for word in ("password", "token", "authorization")):
            if value and not (isinstance(value, str) and value.startswith("${")):
                errors.append(f"公开配置含私密字面值：{path}")

    for public in public_files:
        for doc in yaml.safe_load_all(public.read_text()):
            walk(doc, str(public.relative_to(ROOT)))
    local = ROOT / "config/application-local.yml"
    values = yaml.safe_load(local.read_text()) if local.is_file() else {}
    # 短的默认密码容易与协议或库名同名；公开配置仍逐项检查，内容扫描只匹配长私密值。
    secrets = {key: str(value).encode() for key, value in (values or {}).items()
               if value and len(str(value)) >= 12}

    def inspect(label, content, nested=False):
        for key, secret in secrets.items():
            if secret in content:
                errors.append(f"私密值进入公开文件：{label}（{key}）")
        if nested and zipfile.is_zipfile(io.BytesIO(content)):
            with zipfile.ZipFile(io.BytesIO(content)) as archive:
                for item in archive.infolist():
                    if item.is_dir():
                        continue
                    if Path(item.filename).name == "application-local.yml":
                        errors.append(f"发行包包含本机覆盖：{label}!{item.filename}")
                    inspect(f"{label}!{item.filename}", archive.read(item), item.filename.endswith(".jar"))

    tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode().split("\0")
    for name in filter(None, tracked):
        path = ROOT / name
        if name == "config/application-local.yml":
            errors.append("本机覆盖被 Git 跟踪")
        elif path.is_file():
            inspect(name, path.read_bytes())
    for package in packages:
        path = Path(package)
        inspect(str(path), path.read_bytes(), True)
    if errors:
        raise RuntimeError("\n".join(errors))
    print(f"公开配置和当前跟踪文件检查通过；已检查 {len(packages)} 个发行文件。未打印私密值。")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", action="append", default=[])
    check(parser.parse_args().package)
