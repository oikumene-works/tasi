import hashlib, json, re, struct, sys, zipfile
from pathlib import Path

def decode(path):
    with zipfile.ZipFile(path) as archive:
        assert len(archive.namelist()) == 1
        raw = archive.read(archive.namelist()[0])
    offset, headers = 0, []
    while True:
        size = struct.unpack_from('>H', raw, offset)[0]
        offset += 2
        text = raw[offset:offset + size].decode('utf-8')
        offset += size
        headers.append(text)
        if text.startswith('NumberRecordSets'):
            count = int(text.split(':', 1)[1])
            break
    records = []
    for index in range(count):
        size = struct.unpack_from('>I', raw, offset)[0]
        offset += 4
        metadata = raw[offset:offset + size].decode('utf-8')
        offset += size
        fields = metadata.split('||::||')
        def field(name):
            return next(f.split(':', 1)[1].strip() for f in fields if f.startswith(name + ' :'))
        properties = field('RecordSetProperties')
        assert re.search(r'timeStep_ms=([^|]+)', properties).group(1) == '-1.0'
        probes = []
        for props in metadata.split('RecordProperties : ')[1:]:
            probes.append({
                'name': re.search(r'_name=(T[1-4])', props).group(1),
                'present': re.search(r'ta612c_rec_present_BOOLEAN=(true|false)', props).group(1) == 'true',
                'displayable': re.search(r'_isDisplayable=(true|false)', props).group(1) == 'true',
            })
        records.append({
            'name': field('RecordSetName'),
            'comment': field('RecordSetComment'),
            'start_epoch_ms': int(re.search(r'startTimeStamp=([^|]+)', properties).group(1)),
            'sample_count': int(field('RecordDataSize')),
            'payload_pointer': int(field('RecordSetDataPointer')),
            'probes': probes,
        })
    assert records[0]['payload_pointer'] == offset
    for index, record in enumerate(records):
        start = record['payload_pointer']
        end = records[index + 1]['payload_pointer'] if index + 1 < len(records) else len(raw)
        count = record['sample_count']
        present = [p['name'] for p in record['probes'] if p['present']]
        assert end - start == 4 * count * (1 + len(present))
        record['timestamps_tenths_ms'] = list(struct.unpack_from('>' + str(count) + 'i', raw, start))
        values = struct.unpack_from('>' + str(count * len(present)) + 'i', raw, start + 4 * count)
        record['points_milli_celsius'] = {name: list(values[i::len(present)]) for i, name in enumerate(present)}
        assert all(a < b for a, b in zip(record['timestamps_tenths_ms'], record['timestamps_tenths_ms'][1:]))
        assert all(not p['displayable'] for p in record['probes'] if not p['present'])
    return {'file_name': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'headers': headers, 'records': records}

if __name__ == '__main__':
    source, destination = map(Path, sys.argv[1:3])
    result = decode(source)
    destination.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({'file': source.name, 'sha256': result['sha256'], 'records': [
        {'name': r['name'], 'samples': r['sample_count'], 'probes': list(r['points_milli_celsius']), 'epoch': r['start_epoch_ms']}
        for r in result['records']
    ]}, indent=2))
