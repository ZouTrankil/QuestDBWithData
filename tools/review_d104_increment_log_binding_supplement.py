"""Repeat frozen SELECT-only audit with a new native proof after log-name mismatch."""
import hashlib
import json
from pathlib import Path
import sys

sys.dont_write_bytecode = True
import review_d104_completed_increment_sources_readonly as review


def main():
    directory = review.D
    actual = directory / 'source-fixture-increment-actual-20261007.log'
    expected = directory / 'source-fixture-increment-20261007.log'
    failed = directory / 'coordinator-complete-increment-source-readonly-review-actual-20261007.log'
    assert 'FileNotFoundError' in failed.read_text(encoding='utf-8-sig')
    assert 'source-fixture-increment-20261007.log' in failed.read_text(encoding='utf-8-sig')
    original_native = directory / 'increment-source-producer-native-stop-20261007.json'
    original = json.loads(original_native.read_text(encoding='utf-8'))
    assert original['original_identity_present'] is False
    assert not expected.exists()
    with expected.open('xb') as stream:
        stream.write(actual.read_bytes())
    assert hashlib.sha256(expected.read_bytes()).digest() == hashlib.sha256(actual.read_bytes()).digest()
    frozen_save = review.save

    def supplementary_save(name, value):
        if name == 'increment-source-producer-native-stop-20261007.json':
            name = 'increment-source-producer-native-stop-supplement-20261007.json'
        return frozen_save(name, value)

    # All native, source, target, formal and ledger checks run again unchanged.
    # The original native proof and failed log remain immutable.
    review.save = supplementary_save
    review.main()


if __name__ == '__main__':
    main()
