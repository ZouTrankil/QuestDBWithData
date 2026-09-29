"""Run local Gradle tests with the reference project's configured PGWire credential.

Credentials stay in the child environment. Java's configured target host/database and
QWP credential are unchanged. Run with the reference project's uv environment.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--workspace', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--python-project', type=Path, default=Path('D:/work/fund_2/back-monitor'))
    parser.add_argument('--java-home', type=Path, default=Path.home() / '.jdks/jdk-24.0.2')
    parser.add_argument('--build-name', default='local-validation')
    parser.add_argument('--test', action='append', default=[])
    parser.add_argument('--enable', action='append', default=[], choices=[
        'QUESTDB_BOUNDED_READ', 'QUESTDB_READ_LIVE', 'QUESTDB_TEMPORAL_READ',
        'QUESTDB_DEFINITION_READ', 'TUSHARE_PAGE_LIVE', 'TUSHARE_HTTP_LIVE', 'QUESTDB_LIVE_SMOKE',
        'QUESTDB_WRITE_LIVE'])
    args = parser.parse_args()
    if not re.fullmatch(r'[a-zA-Z0-9_-]+', args.build_name):
        parser.error('build-name must be a simple directory name')
    if any(not re.fullmatch(r'[a-zA-Z0-9_.*]+', pattern) for pattern in args.test):
        parser.error('test selectors must contain only class/method names and wildcards')
    workspace = args.workspace.resolve()
    reference = args.python_project.resolve()
    if not (reference / 'config/database.py').is_file():
        parser.error('Reference project config/database.py is required')
    if not (args.java_home / 'bin/java.exe').is_file():
        parser.error('JDK 24 installation is required; pass --java-home')
    sys.path.insert(0, str(reference))
    from config.database import QUESTDB_CONFIG  # Only configuration; no application/client imports.

    child_env = os.environ.copy()
    child_env['JAVA_HOME'] = str(args.java_home.resolve())
    child_env['SPRING_DATASOURCE_USERNAME'] = QUESTDB_CONFIG['user']
    child_env['SPRING_DATASOURCE_PASSWORD'] = QUESTDB_CONFIG['password']
    for name in ('QUESTDB_BOUNDED_READ', 'QUESTDB_READ_LIVE', 'QUESTDB_TEMPORAL_READ',
                 'QUESTDB_DEFINITION_READ', 'TUSHARE_PAGE_LIVE', 'TUSHARE_HTTP_LIVE', 'QUESTDB_LIVE_SMOKE',
                 'QUESTDB_WRITE_LIVE'):
        child_env.pop(name, None)
    for name in args.enable:
        child_env[name] = '1'
    init = workspace / 'var' / f'{args.build_name}.gradle'
    init.parent.mkdir(parents=True, exist_ok=True)
    init.write_text("allprojects { layout.buildDirectory.set(layout.projectDirectory.dir('var/"
                    + args.build_name + "-build')) }\n", encoding='utf-8')
    command = [str(workspace / 'gradlew.bat'), '--init-script', str(init), '--project-cache-dir',
               str(workspace / 'var' / f'{args.build_name}-cache'), 'test', '--rerun-tasks', '--console=plain']
    for pattern in args.test:
        command.extend(['--tests', pattern])
    print('Using reference-project PGWire credential in child environment; Java target and QWP config unchanged.', flush=True)
    return subprocess.run(command, cwd=workspace, env=child_env, check=False).returncode


if __name__ == '__main__':
    raise SystemExit(main())
