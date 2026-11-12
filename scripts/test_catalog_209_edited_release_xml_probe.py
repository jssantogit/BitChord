#!/usr/bin/env python3
"""Deterministic source-only regressions for a historically edited Release."""
import unittest
from catalog_209_edited_release_xml_probe import (
    RELEASE_MBID, compare, json_rows, xml_rows,
)

T1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
T2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
R1 = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
R2 = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"

def json_edition():
    return {
        "id": RELEASE_MBID, "title": "Damn Skippy",
        "media": [{"position": 1, "track-count": 2, "track-offset": 0,
                   "tracks": [
                       {"id": T1, "position": 0, "recording": {"id": R1}},
                       {"id": T2, "position": 1, "recording": {"id": R2}},
                   ]}],
    }

def xml_edition():
    return (
        '<metadata xmlns="http://musicbrainz.org/ns/mmd-2.0#">'
        f'<release id="{RELEASE_MBID}"><medium-list count="1">'
        '<medium><position>1</position><track-list count="2">'
        f'<track id="{T1}"><position>0</position><recording id="{R1}"/></track>'
        f'<track id="{T2}"><position>1</position><recording id="{R2}"/></track>'
        '</track-list></medium></medium-list></release></metadata>'
    )

class EditedReleaseSourceProofTest(unittest.TestCase):
    def test_exact_source_json_xml_parity_allows_valid_pregap(self):
        report = compare(json_edition(), xml_edition())
        self.assertEqual("PASS", report["status"])
        self.assertEqual(2, report["track_count"])
        self.assertTrue(report["xml_json_track_identity_and_order_equal"])
        self.assertFalse(report["android_ui_verified"])
        self.assertFalse(report["historical_pre_edit_state_reconstructed"])

    def test_reassigned_recording_cannot_be_proven_by_old_xml(self):
        source = json_edition()
        source["media"][0]["tracks"][0]["recording"]["id"] = R2
        outcome = compare(source, xml_edition())
        self.assertEqual("FAIL", outcome["status"])
        self.assertFalse(outcome["xml_json_track_identity_and_order_equal"])

    def test_track_positions_must_be_explicit_unique_and_complete(self):
        mutations = [
            lambda doc: doc["media"][0].pop("track-count"),
            lambda doc: doc["media"][0].update({"track-count": 3}),
            lambda doc: doc["media"][0].update({"track-offset": 1}),
            lambda doc: doc["media"][0]["tracks"][0].pop("position"),
            lambda doc: doc["media"][0]["tracks"][0].update({"position": 1}),
            lambda doc: doc["media"][0].update({"position": True}),
        ]
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                case = json_edition()
                mutate(case)
                self.assertEqual("FAIL", compare(case, xml_edition())["status"])

    def test_structurally_wrong_xml_is_not_independent_proof(self):
        examples = [
            xml_edition().replace("http://musicbrainz.org/ns/mmd-2.0#", "urn:wrong"),
            xml_edition().replace('<track-list count="2">', '<track-list count="1">'),
            xml_edition().replace(f'id="{R1}"', f'id="{R2}"'),
            xml_edition().replace('<position>0</position>', '<position>1</position>'),
            '<!DOCTYPE metadata [<!ENTITY x SYSTEM "file:///etc/passwd">]>' + xml_edition(),
        ]
        for source in examples:
            with self.subTest(source=source[:70]):
                self.assertEqual("FAIL", compare(json_edition(), source)["status"])

    def test_exact_mbids_and_positions_are_preserved_not_matched_by_title(self):
        doc = json_edition()
        doc["title"] = "Another Name"
        self.assertEqual(json_rows(json_edition()), json_rows(doc))
        self.assertEqual(json_rows(doc), xml_rows(xml_edition()))

if __name__ == "__main__":
    unittest.main()
