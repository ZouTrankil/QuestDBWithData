import unittest
from migrate_questdb_record_archives import fingerprint, normalized


class RecordMigrationSafetyTest(unittest.TestCase):
    def test_int64_and_duplicate_history_cannot_silently_change(self):
        schema = {"id": "LONG", "value": "DOUBLE"}
        row = {"id": 9007199254740993, "value": 0.12345678901234567}
        before = fingerprint([row, row], schema)
        rounded = {**row, "id": 9007199254740992}
        self.assertNotEqual(before, fingerprint([row, rounded], schema))
        self.assertNotEqual(before, fingerprint([row], schema))
        self.assertNotEqual(before, fingerprint([row, {**row, "value": 0.1234567890123456}], schema))

    def test_time_precision_and_date_only_loss_are_checked(self):
        self.assertEqual(normalized("2026-10-16T08:00:00.123456+08:00", "TIMESTAMP"),
                         "2026-10-16T00:00:00.123456")
        with self.assertRaisesRegex(ValueError, "discard intraday"):
            normalized("2026-10-16T00:00:00.000001Z", "TIMESTAMP", True)

    def test_null_and_missing_fields_are_not_equivalent(self):
        self.assertNotEqual(fingerprint([{"id": None}], {"id": "LONG"}),
                            fingerprint([{"id": 0}], {"id": "LONG"}))
        with self.assertRaises(ValueError):
            fingerprint([{}], {"id": "LONG"})


if __name__ == "__main__":
    unittest.main()
