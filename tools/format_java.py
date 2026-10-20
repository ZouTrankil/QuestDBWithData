#!/usr/bin/env python3
"""统一 Java import 与 4 空格排版。默认只检查，--apply 才修改文件。"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / '.gradle/java-format'
VERSION = '1.27.0'
SHA256 = 'ed07239f3cb72e25bf2a0eae63e76831f9f11963bd19fc36a6f1d87016ac1763'
FLAGS = ['--aosp', '--skip-reflowing-long-strings', '--skip-javadoc-formatting']


def formatter() -> Path:
    jar = CACHE / f'google-java-format-{VERSION}-all-deps.jar'
    CACHE.mkdir(parents=True, exist_ok=True)
    if not jar.exists():
        url = f'https://repo.maven.apache.org/maven2/com/google/googlejavaformat/google-java-format/{VERSION}/{jar.name}'
        with urllib.request.urlopen(url, timeout=60) as response:
            content = response.read()
        if hashlib.sha256(content).hexdigest() != SHA256:
            raise ValueError('格式器下载摘要不符，停止处理')
        jar.write_bytes(content)
    if hashlib.sha256(jar.read_bytes()).hexdigest() != SHA256:
        raise ValueError('缓存格式器摘要不符，停止处理')
    return jar


def format_outputs(outputs):
    """生成器在写入目标前整批排版；历史注释和字符串不重写。"""
    if not outputs:
        return tuple(outputs)
    with tempfile.TemporaryDirectory(prefix='java-format-') as folder:
        paths = []
        for index, (_, source) in enumerate(outputs):
            path = Path(folder) / str(index) / 'Generated.java'
            path.parent.mkdir()
            path.write_text(source, encoding='utf-8')
            paths.append(path)
        run_formatter(paths, apply=True)
        return tuple((target, path.read_text(encoding='utf-8'))
                     for (target, _), path in zip(outputs, paths))


def run_formatter(files, *, apply):
    # 格式器按行读取参数文件，含空格的路径也不加 shell 引号。
    jar = formatter()
    with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', delete=False) as stream:
        arguments = Path(stream.name)
        stream.write('\n'.join(FLAGS + (['--replace'] if apply else ['--dry-run', '--set-exit-if-changed'])))
        stream.write('\n' + '\n'.join(str(p).replace('\\', '/') for p in files))
    try:
        # 首遍移除无用 import 后，第二遍收齐空行；检查时要求已经稳定。
        for _ in range(2 if apply else 1):
            subprocess.run(['java', '-jar', str(jar), '@' + str(arguments)], check=True, cwd=ROOT)
    finally:
        arguments.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--apply', action='store_true')
    mode.add_argument('--check', action='store_true', help='只检查（默认）')
    parser.add_argument('--imports', action='store_true', help='按实际 classpath 解析符号并检查/整理限定名和通配符')
    args = parser.parse_args()
    wrapper = ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    CACHE.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(wrapper), '-I', 'tools/java-format.init.gradle', 'exportJavaFormatInputs'], cwd=ROOT, check=True)
    groups = json.loads((CACHE / 'inputs.json').read_text())
    subprocess.run(['javac', '-d', str(CACHE), str(ROOT / 'tools/java/JavaImports.java')], check=True)
    snapshots = []
    files = sorted({p for group in groups for p in group['files']} | {str(ROOT / 'tools/java/JavaImports.java')})
    try:
        for index, group in enumerate(groups):
            classpath = CACHE / f'classpath-{index}.txt'
            sources = CACHE / f'sources-{index}.txt'
            classpath.write_text(group['classpath'])
            sources.write_text('\n'.join(group['files']))
            command = ['java', '-cp', str(CACHE), 'JavaImports', 'literals', str(classpath), str(sources)]
            before = subprocess.check_output(command)
            snapshots.append((command, before))
            if args.imports:
                print('处理符号：' + group['id'], flush=True)
                subprocess.run(command[:4] + ['rewrite' if args.apply else 'check'] + command[5:], check=True)
        run_formatter(files, apply=args.apply)
    finally:
        # 按 javac 解码后的值核对字面量，包括 text block 的实际值。
        for command, before in snapshots:
            after = subprocess.check_output(command)
            if sorted(before.splitlines()) != sorted(after.splitlines()):
                raise ValueError('Java 字面量发生变化，请检查 diff；停止继续提交')
    print(f'完成：{len(files)} 个 Java 文件；字面量值核对通过')


if __name__ == '__main__':
    main()
