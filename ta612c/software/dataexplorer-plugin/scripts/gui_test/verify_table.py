"""Compare saved native table evidence with decoded TA612C OSD records."""

from decimal import Decimal
import json
from pathlib import Path
import sys


def verify(decoded, evidence):
    if 'records' in evidence:
        tables = evidence['records']
    else:
        table = evidence.get('table', evidence.get('reopened_table'))
        assert table is not None, 'Expected table, reopened_table or records.'
        tables = [{'record_index': 1, 'table': table}]
    results = []
    seen = set()
    for item in tables:
        index = item['record_index']
        assert index not in seen, 'Duplicate record index.'
        seen.add(index)
        assert 1 <= index <= len(decoded['records'])
        record = decoded['records'][index - 1]
        table = item['table']
        present = list(record['points_milli_celsius'])
        headers = [h.split('[')[0].strip() for h in table['headers'] if h.strip()]
        assert headers == ['Time'] + present, (index, headers, present)
        assert len(table['rows']) == record['sample_count']
        for row_index, row in enumerate(table['rows']):
            milliseconds = int(sum(
                Decimal(part) * 60 ** power
                for power, part in enumerate(reversed(row[0].split(':')))
            ) * 1000)
            assert milliseconds == record['timestamps_tenths_ms'][row_index] // 10
            for name in present:
                actual = int(Decimal(row[headers.index(name)]) * 1000)
                expected = record['points_milli_celsius'][name][row_index]
                assert actual == expected, (index, row_index, name, actual, expected)
        results.append({'record_index': index, 'rows': record['sample_count'],
                        'temperature_values': record['sample_count'] * len(present)})
    return {'all_supplied_tables_match': True, 'records': results,
            'all_decoded_records_covered': seen == set(range(1, len(decoded['records']) + 1)),
            'time_comparison': 'UI milliseconds; stored timestamps retain 0.1 ms.'}


if __name__ == '__main__':
    if len(sys.argv) != 4:
        raise SystemExit('Usage: verify_table.py decoded.json table-evidence.json result.json')
    decoded_path, table_path, output_path = map(Path, sys.argv[1:])
    result = verify(json.loads(decoded_path.read_text()), json.loads(table_path.read_text()))
    assert not output_path.exists(), 'Choose a new output path; preserve existing evidence.'
    output_path.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
