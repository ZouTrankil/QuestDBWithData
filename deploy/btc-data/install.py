# /// script
# requires-python = ">=3.11"
# dependencies = ["PyYAML>=6"]
# ///
"""安装当前用户的 macOS BTC 日更任务；每日 UTC 01:00 对应本机时刻。"""
import argparse
from datetime import datetime, timezone
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[2]
LABEL='com.zoutrankil.btc-data'
MARKER='QuestDBWithData BTC 日更；由 deploy/btc-data/install.py 管理'


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--remove',action='store_true')
    args=parser.parse_args()
    if sys.platform!='darwin':raise SystemExit('当前安装器只支持 macOS；其他环境调用 tools/btc_daily.py run')
    path=Path.home()/'Library/LaunchAgents'/f'{LABEL}.plist'
    domain=f'gui/{os.getuid()}'
    if path.exists() and plistlib.loads(path.read_bytes()).get('Comment')!=MARKER:
        raise SystemExit('同名任务不属于本安装器，未覆盖')
    if args.remove:
        subprocess.run(['launchctl','bootout',domain+'/'+LABEL],capture_output=True)
        path.unlink(missing_ok=True)
        print('已移除 BTC 日更任务；数据、锁和账本保留')
        return
    uv=shutil.which('uv'); java=shutil.which('java')
    if not uv or not java:raise SystemExit('缺 uv 或 Java 24')
    version=subprocess.check_output([java,'-XshowSettings:properties','-version'],stderr=subprocess.STDOUT,text=True)
    java_home=next((line.split('=',1)[1].strip() for line in version.splitlines() if line.strip().startswith('java.home =')),None)
    if not java_home or not (Path(java_home)/'bin/java').is_file():raise SystemExit('无法确定实际 Java home')
    if 'version "24' not in version:raise SystemExit('请先选择 JDK 24')
    binary=ROOT/'build/modules/markets/btc/data-app/_output/install/btc-data/bin/btc-data'
    if not binary.is_file():raise SystemExit('先运行 ./gradlew :markets:btc:data-app:installDist')
    # 排程只读根配置；launchd 按本机时区运行，截止日与尝试计数始终按 UTC。
    sys.path.insert(0,str(ROOT/'tools'))
    from btc_daily import schedule_settings
    policy=schedule_settings()
    times=[datetime.now(timezone.utc).replace(hour=hour,minute=0,second=0,microsecond=0).astimezone() for hour in policy['utcHours']]
    local=times[0]
    logs=ROOT/'build/btc-scheduler';logs.mkdir(parents=True,exist_ok=True)
    doc={'Label':LABEL,'Comment':MARKER,'ProgramArguments':[uv,'run','--script',str(ROOT/'tools/btc_daily.py'),'scheduled'],
         'WorkingDirectory':str(ROOT),'StartCalendarInterval':[{'Hour':time.hour,'Minute':time.minute} for time in times],
         'ProcessType':'Background','EnvironmentVariables':{'PATH':os.environ.get('PATH','/usr/bin:/bin'),'JAVA_HOME':java_home},
         'StandardOutPath':str(logs/'stdout.log'),'StandardErrorPath':str(logs/'stderr.log')}
    path.parent.mkdir(parents=True,exist_ok=True)
    if path.exists():
        state=subprocess.run(['launchctl','print',domain+'/'+LABEL],capture_output=True,text=True)
        if any(line.strip().startswith('pid =') for line in state.stdout.splitlines()):
            raise SystemExit('原日更进程仍在运行，待当前运行结束后再安装；未终止原 writer')
        subprocess.run(['launchctl','bootout',domain+'/'+LABEL],capture_output=True)
    pending=path.with_suffix('.pending');pending.write_bytes(plistlib.dumps(doc));pending.replace(path)
    subprocess.run(['launchctl','bootstrap',domain,str(path)],check=True)
    subprocess.run(['launchctl','enable',domain+'/'+LABEL],check=True)
    print(f'已安装 {path}；首次本机 {local:%H:%M}，失败每 {policy["retryHours"]} 小时续跑，UTC 当日最多 {policy["maxAttempts"]} 次；时区改变后重新安装')


if __name__=='__main__':main()
