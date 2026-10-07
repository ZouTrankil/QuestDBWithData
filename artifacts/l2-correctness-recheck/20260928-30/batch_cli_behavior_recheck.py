"""Native batch CLI behavior checks on copied tiny fixtures; no database access."""
from __future__ import annotations
import csv
from datetime import datetime, timezone
from decimal import Decimal
import hashlib
import json
import math
from pathlib import Path
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
BASE = Path(__file__).resolve().parent
FIXTURES = ROOT / "src/test/resources/l2-daily-pipeline/20260924"
JAVA = Path(r"C:\Users\zouqiang\.jdks\jdk-24.0.2\bin\java.exe")
DEPENDENCIES = (ROOT / "artifacts/java-migration/D088/commands/compile-classpath.txt").read_text(encoding="utf-8-sig").strip()
CLASSPATH = str(BASE / "gradle-build/classes/java/main") + ";" + DEPENDENCIES
SCHEMA = json.loads((ROOT / "var/l2-performance-validation/performance-schema.json").read_text(encoding="utf-8-sig"))
RUN = BASE / "batch-cli-recheck" / ("run-" + datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S-%f"))
RUN.mkdir(parents=True)
CASES = []
BYTECODE_PROBE = {}


def load(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(value, description):
    if not value:
        raise AssertionError(description)


def copy_symbol(source, destination):
    shutil.copytree(source, destination)


def prepare(name):
    source = RUN / (name + "-source")
    day = source / "20260924"
    day.mkdir(parents=True)
    for symbol in ("159915.SZ", "510300.SZ"):
        copy_symbol(FIXTURES / symbol, day / symbol)
    return source, RUN / (name + "-output")


def validate_published(output, date="20260924"):
    folder = output / date
    manifest = load(folder / "manifest.json")
    require(manifest["status"] == "COMPLETE" and manifest["published"] is True, "date must be completely published")
    file = folder / "l2_daily_features.jsonl"
    require(sha(file) == manifest["aggregateSha256"], "aggregate hash must match manifest")
    rows = [json.loads(line) for line in file.read_text(encoding="utf-8").splitlines()]
    require(len(rows) == manifest["counts"]["success"], "all successful tasks must reach aggregate")
    symbols = [row["symbol"] for row in rows]
    require(symbols == sorted(set(symbols)), "canonical output must be unique and sorted")
    for row in rows:
        require(row["ts"] == date, "correct business date")
        require(len(row) == 110 and set(row) == set(SCHEMA["fields"]), "110 frozen field names")
        for key, kind in SCHEMA["fields"].items():
            value = row[key]
            if value is None:
                require(key not in ("ts", "symbol"), "identity must not be null")
            elif kind == "str":
                require(type(value) is str, key + " string type")
            elif kind == "bool":
                require(type(value) is bool, key + " boolean type")
            elif kind == "int":
                require(type(value) is int, key + " integer type")
            elif kind == "number":
                require(type(value) in (int, float) and math.isfinite(value), key + " finite numeric type")
    return manifest


def execute(name, source, output, expected_exit=0, dates="20260924", extra=None, resume=True, class_prefix=None):
    classpath = str(class_prefix) + ";" + CLASSPATH if class_prefix else CLASSPATH
    command = [str(JAVA), "--enable-native-access=ALL-UNNAMED", "-Xmx1g", "-cp", classpath,
               "com.zoutrankil.batch.l2.L2DailyFeatureBatchCli", "--source-root", str(source),
               "--dates", dates, "--output-root", str(output), "--workers", "2", "--resume", str(resume).lower()]
    if extra:
        command.extend(extra)
    started = time.perf_counter()
    process = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=90)
    log = RUN / (name + ".log")
    log.write_bytes(process.stdout)
    manifests = {date: load(output / date / "manifest.json") for date in dates.split(",")}
    case = {"name": name, "exitCode": process.returncode, "expectedExitCode": expected_exit,
            "elapsedSeconds": time.perf_counter() - started, "log": str(log),
            "source": str(source), "output": str(output), "dates": {
                date: {key: manifest.get(key) for key in ("status", "published", "counts", "aggregateSha256", "computeFingerprint")}
                for date, manifest in manifests.items()}}
    CASES.append(case)
    require(process.returncode == expected_exit, name + ": expected exit " + str(expected_exit))
    return manifests


def alter_quantity(csv_path):
    with csv_path.open(encoding="gb18030", newline="") as source:
        rows = list(csv.reader(source))
    headers = rows[0]
    price_index, volume_index = headers.index("成交价格"), headers.index("成交数量")
    for row in rows[1:]:
        if row and Decimal(row[price_index]) > 0:
            row[volume_index] = str(Decimal(row[volume_index]) + 1)
            break
    with csv_path.open("w", encoding="gb18030", newline="") as output:
        csv.writer(output, lineterminator="\n").writerows(rows)


def run_checks():
    source, output = prepare("resume")
    fresh = execute("01-fresh", source, output, resume=False)["20260924"]
    validate_published(output)
    require(fresh["counts"] == {"success": 2, "empty": 0, "failed": 0, "resumed": 0}, "fresh exactly two success tasks")
    original_hash = fresh["aggregateSha256"]
    reused = execute("02-resume", source, output)["20260924"]
    validate_published(output)
    require(reused["counts"]["resumed"] == 2 and reused["aggregateSha256"] == original_hash, "unchanged input reuses two and preserves bytes")

    result_path = output / "20260924/features/159915.SZ.json"
    result_path.write_bytes(result_path.read_bytes() + b" ")
    guarded = execute("03-tampered-result", source, output)["20260924"]
    validate_published(output)
    require(guarded["counts"]["resumed"] == 1 and guarded["aggregateSha256"] == original_hash, "result tampering forces only affected symbol recomputation")

    state_path = output / "20260924/state/510300.SH.json"
    state = load(state_path)
    state["computeFingerprint"] = "intentionally-stale-fingerprint"
    state_path.write_text(json.dumps(state), encoding="utf-8")
    guarded = execute("04-stale-computation-fingerprint", source, output)["20260924"]
    validate_published(output)
    require(guarded["counts"]["resumed"] == 1 and guarded["aggregateSha256"] == original_hash, "stale calculation fingerprint forces one recomputation")

    csv_path = source / "20260924/159915.SZ/逐笔成交.csv"
    alter_quantity(csv_path)
    guarded = execute("05-changed-valid-input", source, output)["20260924"]
    validate_published(output)
    require(guarded["counts"]["resumed"] == 1 and guarded["aggregateSha256"] != original_hash, "changed input recomputes affected symbol and updates actual output")
    shutil.copyfile(FIXTURES / "159915.SZ/逐笔成交.csv", csv_path)
    guarded = execute("06-restored-input", source, output)["20260924"]
    validate_published(output)
    require(guarded["counts"]["resumed"] == 1 and guarded["aggregateSha256"] == original_hash, "restoring input reproduces original aggregate")

    alias_source, alias_output = prepare("aliases")
    copy_symbol(FIXTURES / "510300.SZ", alias_source / "20260924/510300_SH")
    (alias_source / "20260924/000001.SZ").mkdir()
    same = execute("07-identical-canonical-alias-and-empty", alias_source, alias_output)["20260924"]
    validate_published(alias_output)
    require(same["counts"] == {"success": 2, "empty": 1, "failed": 0, "resumed": 0}, "same aliases deduplicate and empty source counted")
    require(same["aggregateSha256"] == original_hash, "duplicate alias cannot duplicate daily row")
    alter_quantity(alias_source / "20260924/510300_SH/逐笔成交.csv")
    conflicted = execute("08-conflicting-canonical-alias", alias_source, alias_output, expected_exit=1)["20260924"]
    require(conflicted["status"] == "FAILED" and conflicted["published"] is False, "alias conflict must block fresh publication")
    require(conflicted["counts"] == {"success": 1, "empty": 1, "failed": 1, "resumed": 2}, "alias conflict does not stop other symbols")
    require(sha(alias_output / "20260924/l2_daily_features.jsonl") == original_hash, "failed run preserves previous whole-day aggregate")
    errors = [json.loads(row) for row in (alias_output / "20260924/errors.jsonl").read_text().splitlines()]
    require(len(errors) == 1 and errors[0]["symbol"] == "510300.SH", "canonical alias failure is identified explicitly")

    bad_source, bad_output = prepare("bad-symbol")
    bad = bad_source / "20260924/300001.SZ"
    bad.mkdir()
    (bad / "逐笔成交.csv").write_text("invalid_header\ninvalid_data\n", encoding="gb18030")
    failed = execute("09-bad-symbol-does-not-stop-neighbors", bad_source, bad_output, expected_exit=1)["20260924"]
    require(failed["counts"] == {"success": 2, "empty": 0, "failed": 1, "resumed": 0}, "both good symbols complete around failed code")
    require(failed["status"] == "FAILED" and not failed["published"], "bad symbol blocks aggregate publication")
    require(not (bad_output / "20260924/l2_daily_features.jsonl").exists(), "fresh incomplete day must not publish half aggregate")
    require((bad_output / "20260924/features/159915.SZ.json").is_file() and (bad_output / "20260924/features/510300.SH.json").is_file(), "successful checkpoint results survive single-symbol error")
    retry = execute("10-resume-incomplete-day", bad_source, bad_output, expected_exit=1)["20260924"]
    require(retry["counts"] == {"success": 2, "empty": 0, "failed": 1, "resumed": 2}, "incomplete day retries failed symbol while reusing successes")
    for file in (FIXTURES / "159915.SZ").iterdir():
        shutil.copyfile(file, bad / file.name)
    repaired = execute("11-repaired-symbol-completes-day", bad_source, bad_output)["20260924"]
    validate_published(bad_output)
    require(repaired["counts"] == {"success": 3, "empty": 0, "failed": 0, "resumed": 2}, "repair publishes all three and retains prior successful checkpoints")

    multiday_source, multiday_output = prepare("multiple-dates")
    bad = multiday_source / "20260924/300001.SZ"
    bad.mkdir()
    (bad / "逐笔成交.csv").write_text("invalid_header\n", encoding="gb18030")
    following = multiday_source / "20260925/159915.SZ"
    copy_symbol(FIXTURES / "159915.SZ", following)
    for file in following.iterdir():
        file.write_bytes(file.read_bytes().replace(b"20260924", b"20260925"))
    days = execute("12-failed-date-does-not-stop-next-date", multiday_source, multiday_output,
                   expected_exit=1, dates="20260924,20260925")
    require(days["20260924"]["status"] == "FAILED" and days["20260925"]["status"] == "COMPLETE", "failed date must not prevent later date processing")
    validate_published(multiday_output, "20260925")

    whitelist = RUN / "universe.txt"
    whitelist.write_text("# explicit sample universe\n159915.SZ\n600000.SH\n", encoding="utf-8")
    selected_output = RUN / "selected-output"
    selected = execute("13-whitelist-intersection", source, selected_output,
                       extra=["--symbols-file", str(whitelist)])["20260924"]
    validate_published(selected_output)
    require(selected["counts"]["success"] == 1 and selected["selection"]["excludedByUniverse"] == 1,
            "explicit universe restricts output to selected code")
    require(selected["selection"]["requestedNotPresent"] == ["600000.SH"], "absent requested symbol is recorded")

    # Change only the class-file debug attributes of a compiler-generated enum-switch helper.
    # The production pipeline class and all source files remain unchanged. A raw file checksum
    # must detect the loaded helper even though Class.getDeclaredClasses omits it.
    compile_directory = RUN / "alternate-helper-compilation"
    helper_directory = RUN / "alternate-helper-only-classpath"
    compile_directory.mkdir()
    javac = JAVA.with_name("javac.exe")
    compiled = subprocess.run([str(javac), "-g:none", "-encoding", "UTF-8", "-cp", CLASSPATH,
                               "-d", str(compile_directory),
                               str(ROOT / "src/main/java/com/zoutrankil/batch/l2/L2DailyFeaturePipeline.java")],
                              cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=90)
    (RUN / "helper-probe-compile.log").write_bytes(compiled.stdout)
    require(compiled.returncode == 0, "helper-only bytecode probe compilation")
    relative_helper = Path("com/zoutrankil/batch/l2/L2DailyFeaturePipeline$1.class")
    old_helper = BASE / "gradle-build/classes/java/main" / relative_helper
    new_helper = compile_directory / relative_helper
    require(sha(old_helper) != sha(new_helper), "alternate helper bytes must differ")
    alternate_helper = helper_directory / relative_helper
    alternate_helper.parent.mkdir(parents=True)
    shutil.copyfile(new_helper, alternate_helper)
    BYTECODE_PROBE.update({"changedLoadedClassOnly": "com.zoutrankil.batch.l2.L2DailyFeaturePipeline$1",
                          "originalSha256": sha(old_helper), "alternateSha256": sha(new_helper),
                          "method": "Compile copied pipeline with javac -g:none; prepend only its $1 helper class",
                          "originalClass": str(old_helper), "alternateClass": str(alternate_helper)})
    helper_changed = execute("14-loaded-synthetic-helper-bytecode-change", source, output,
                             class_prefix=helper_directory)["20260924"]
    validate_published(output)
    require(helper_changed["counts"]["resumed"] == 0, "actual helper bytecode change invalidates both checkpoints")
    require(helper_changed["aggregateSha256"] == original_hash, "nonsemantic helper debug change preserves feature output bytes")
    normal_again = execute("15-original-loaded-helper-restored", source, output)["20260924"]
    validate_published(output)
    require(normal_again["counts"]["resumed"] == 0 and normal_again["aggregateSha256"] == original_hash,
            "restoring original computation bytes invalidates alternate checkpoints and preserves feature values")


def main():
    errors = []
    try:
        run_checks()
    except Exception as error:
        errors.append(type(error).__name__ + ": " + str(error))
    summary = {"recordedAt": datetime.now(timezone.utc).isoformat(), "status": "passed" if not errors else "failed",
               "errors": errors, "completedBehaviorCases": len(CASES), "classDirectory": str(BASE / "gradle-build/classes/java/main"),
               "java": str(JAVA), "fixtureSource": str(FIXTURES), "validationRun": str(RUN), "cases": CASES,
               "syntheticHelperFingerprintProbe": BYTECODE_PROBE,
               "databaseWrites": "none: native batch CLI computes local copied fixtures and files only"}
    target = BASE / "batch-cli-behavior-summary.json"
    target.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: summary[key] for key in ("status", "errors", "completedBehaviorCases", "validationRun")}, ensure_ascii=False))
    return 0 if not errors else 2


if __name__ == "__main__":
    sys.exit(main())
