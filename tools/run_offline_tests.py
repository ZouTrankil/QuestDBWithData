#!/usr/bin/env python3
"""在各平台运行当前全部模块的离线测试，报告保存在 .gradle。"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tests', action='append', default=[], help='只运行匹配的测试，可重复传入')
    parser.add_argument('--evidence-directory', default='.gradle/offline-checks', help='报告目录，必须在本项目 .gradle 下')
    parser.add_argument('--dry-run', action='store_true', help='查看命令，不运行')
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    # 主策略和实时 L2 都在聚合测试里，不再维护容易过期的模块任务清单。
    command = [str(root / ('gradlew.bat' if os.name == 'nt' else 'gradlew')),
               ':test', '--offline', '--console=plain']
    for pattern in args.tests:
        command += ['--tests', pattern]
    if args.dry_run:
        print(json.dumps(command, ensure_ascii=False))
        return 0
    reports = (root / args.evidence_directory).resolve()
    if not reports.is_relative_to((root / '.gradle').resolve()) or reports == (root / '.gradle').resolve():
        parser.error('报告目录必须在本项目 .gradle 下')
    run = reports / datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    run.mkdir(parents=True)
    # LiveMain 等纯本地用例保留，真实供应商和数据库验收单独运行。
    local_live = {'AliyunL2LiveMainTest', 'L2LiveConfigurationTest', 'L2LiveProcessorTest'}
    external = []
    modules = re.findall(r"^include\s+'([^']+)'", (root / 'settings.gradle').read_text(), re.M)
    for module in modules:
        for source in (root / module.replace(':', '/') / 'src/test/java').rglob('*Live*.java'):
            if source.stem not in local_live:
                package = re.search(r'^package\s+([\w.]+);', source.read_text(), re.M)
                if package:
                    external.append(package[1] + '.' + source.stem)
    external += ['*QuestDbProtocolTest', '*EquityStyleMonthlyInitialReadOnlyRecoveryTest',
                 '*EtfMarketOverviewCacheHitReadOnlyRecoveryTest', '*TemporalQuestDbReadTest',
                 '*IndexCatalogObservedEarlyRecoveryTest', '*IndexCatalogObservedRecoveryTest',
                 '*StockDetailObservedRunRecoveryTest',
                 'com.zoutrankil.data.service.TushareSliceAcceptanceTest.realCodeSlicesUseSharedSourceAndConsumePagesSerially']
    policy = run / 'offline.init.gradle'
    policy.write_text('''allprojects {
    tasks.withType(Test).configureEach {
        useJUnitPlatform { excludeTags 'retained-parity' }
        def excluded = new groovy.json.JsonSlurper().parse(new File(System.getProperty('offline.excludedFile')))
        excluded.each { filter.excludeTestsMatching(it) }
        setEnvironment(environment.findAll { key, value ->
            !(key.toString() ==~ /(?i).*(LIVE|RECOVERY|JDB_QUESTDB_TEST_URL|TUSHARE_TOKEN).*/)
                && !(key.toString() ==~ /(?i)D\\d+_.*/)
        })
    }
}
''')
    # Windows 的批处理命令行有长度上限；排除清单通过文件传给 Gradle。
    command += ['-I', str(policy), '-Doffline.excludedFile=' + str(run / 'excluded.json')]
    # 每次写独立报告，避免把上一次的 XML 算进本次结果。
    report_policy = run / 'reports.init.gradle'
    report_policy.write_text('''allprojects {
    tasks.withType(Test).configureEach {
        def folder = new File(System.getProperty('offline.reports'), path.substring(1).replace(':', '/'))
        reports.junitXml.outputLocation.set(new File(folder, 'xml'))
        reports.html.outputLocation.set(new File(folder, 'html'))
        binaryResultsDirectory.set(new File(folder, 'binary'))
    }
}
''')
    command += ['-I', str(report_policy), '-Doffline.reports=' + str(run), '--rerun-tasks']
    (run / 'command.json').write_text(json.dumps(command, ensure_ascii=False, indent=2))
    (run / 'excluded.json').write_text(json.dumps(external, ensure_ascii=False, indent=2))
    print('离线检查报告：' + str(run), flush=True)
    with (run / 'gradle.log').open('w') as log:
        result = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT)
    totals = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
    failures = []
    for xml in run.rglob('TEST-*.xml'):
        suite = ET.parse(xml).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, 0))
        for case in suite.findall('testcase'):
            error = case.find('failure')
            if error is None:
                error = case.find('error')
            if error is not None:
                failures.append({'class': case.get('classname'), 'test': case.get('name'),
                                 'message': error.get('message'), 'report': str(xml)})
    summary = dict(totals, exitCode=result.returncode, failuresByCase=failures)
    (run / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
    (run.parent / 'latest.txt').write_text(str(run))
    print(json.dumps(totals, ensure_ascii=False))
    print('\n'.join((run / 'gradle.log').read_text().splitlines()[-20:]))
    return result.returncode or int(not totals['tests'] or bool(totals['failures'] or totals['errors']))


if __name__ == '__main__':
    sys.exit(main())
