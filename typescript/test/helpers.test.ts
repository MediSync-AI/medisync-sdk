// Helpers that are no API operation: release, waits, the event poll and the capability view.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { buildRelease, CapabilityError, NotSupportedError, toFile, TranscriptionWaitTimeoutError, TransferWaitTimeoutError, VerbNotGrantedError } from '../src/index.ts';
import { json, mockApi, transfer } from './support.ts';

describe('buildRelease', () => {
  const plan = { document_hash: 'a'.repeat(64), plan_hash: 'b'.repeat(64) };
  test('takes the hashes from the plan and writes seconds without milliseconds', () => {
    const release = buildRelease(plan, {
      memberId: 'mem_9Ks3Jd7TyA',
      displayName: 'Dr. Jonas Keller',
      role: 'doctor',
      practitionerLink: 'ARZT-03',
      patientConfirmed: { id: '10482', family: 'Beispiel', given: 'Erika', birth_date: '1961-03-12' },
      releasedAt: new Date('2026-11-04T10:15:30.456Z'),
    });
    assert.deepEqual(release, {
      released_by: { member_id: 'mem_9Ks3Jd7TyA', display_name: 'Dr. Jonas Keller' },
      role: 'doctor',
      practitioner_link: 'ARZT-03',
      patient_confirmed: { id: '10482', family: 'Beispiel', given: 'Erika', birth_date: '1961-03-12' },
      document_hash: plan.document_hash,
      plan_hash: plan.plan_hash,
      released_at: '2026-11-04T10:15:30Z',
    });
  });
  test('refuses a time it cannot read', () => {
    assert.throws(() => buildRelease(plan, { memberId: 'm', role: 'doctor', practitionerLink: 'x', patientConfirmed: { id: '1' }, releasedAt: 'gestern' }), TypeError);
  });
});

describe('connect.transfers.wait', () => {
  test('reads until a final status and reports each change once', async () => {
    const { client, calls } = mockApi([json(200, transfer('queued')), json(200, transfer('queued')), json(200, transfer('delivered')), json(200, transfer('completed'))]);
    const seen: string[] = [];
    const done = await client.connect.transfers.wait('trf_7Yc3Nd9LxB', { intervalMs: 1, onUpdate: (t) => seen.push(t.status) });
    assert.equal(done.status, 'completed');
    assert.deepEqual(seen, ['queued', 'delivered', 'completed']);
    assert.equal(calls.length, 4);
  });

  test('stops at in_doubt, which needs a person', async () => {
    const { client } = mockApi([json(200, transfer('in_doubt'))]);
    assert.equal((await client.connect.transfers.wait('trf_7Yc3Nd9LxB')).status, 'in_doubt');
  });

  test('keeps reading through a status it does not know, then times out', async () => {
    const { client } = mockApi(Array.from({ length: 50 }, () => json(200, transfer('waiting_for_vendor'))));
    const err = await client.connect.transfers.wait('trf_7Yc3Nd9LxB', { intervalMs: 1, timeoutMs: 20 }).catch((e: unknown) => e);
    assert.ok(err instanceof TransferWaitTimeoutError);
    assert.equal(err.transfer.status, 'waiting_for_vendor');
  });
});

describe('audio.transcriptions.wait', () => {
  const t = (status: string) => ({ id: 'tr_1', object: 'transcription', status, created_at: '2026-11-16T09:00:00Z', result: null });
  test('reads until completed; a failed one is answered, not thrown', async () => {
    const { client } = mockApi([json(200, t('queued')), json(200, t('processing')), json(200, t('completed')), json(200, t('failed'))]);
    assert.equal((await client.audio.transcriptions.wait('tr_1', { intervalMs: 1 })).status, 'completed');
    assert.equal((await client.audio.transcriptions.wait('tr_1', { intervalMs: 1 })).status, 'failed');
  });
  test('times out with the last state', async () => {
    const { client } = mockApi(Array.from({ length: 50 }, () => json(200, t('processing'))));
    const err = await client.audio.transcriptions.wait('tr_1', { intervalMs: 1, timeoutMs: 15 }).catch((e: unknown) => e);
    assert.ok(err instanceof TranscriptionWaitTimeoutError);
  });
});

describe('connect.events.poll', () => {
  test('yields pages with events, keeps the cursor across empty pages, and ends on abort', async () => {
    const stop = new AbortController();
    const event = (id: string) => ({ id, type: 'entry.filed', site_id: 'ste_7Hq2Lk9PwX', connection_id: 'con_4Rt8Vb2MnQ', created_at: '2026-11-04T09:24:13Z', data: {} });
    const { client, calls } = mockApi([
      json(200, { data: [event('evt_1'), event('evt_2')], next_cursor: 'c2' }),
      json(200, { data: [], next_cursor: 'c2' }),
      json(200, { data: [{ ...event('evt_3'), type: 'entry.archived' }], next_cursor: 'c3' }),
      () => {
        stop.abort();
        return json(200, { data: [], next_cursor: 'c3' });
      },
    ]);
    const pages: string[][] = [];
    for await (const page of client.connect.events.poll({ site_id: 'ste_7Hq2Lk9PwX', cursor: 'c1' }, { intervalMs: 1, signal: stop.signal })) {
      pages.push(page.data.map((e) => `${e.id}:${e.type}`));
    }
    assert.deepEqual(pages, [['evt_1:entry.filed', 'evt_2:entry.filed'], ['evt_3:entry.archived']]);
    assert.deepEqual(calls.map((c) => new URL(c.url).searchParams.get('cursor')), ['c1', 'c2', 'c2', 'c3']);
  });
});

describe('connect.capabilities.forConnection', () => {
  test('loads once, counts unknown statuses as not callable, and explains a refusal', async () => {
    const set = {
      connection_id: 'con_4Rt8Vb2MnQ',
      system: 'duria',
      adapter: 'duria@0.2.0',
      as_of: '2026-11-04T09:00:00Z',
      source: 'live',
      verbs: {
        'record.write': { status: 'supported' },
        'diagnosis.write.text': { status: 'partial', limits: ['text_only'] },
        'diagnosis.write': { status: 'unsupported', reason: 'system_cannot', alternative: 'diagnosis.write.text' },
        'patient.search': { status: 'deprecated' },
        'medication.order': { status: 'supported', granted: false },
      },
    };
    const { client, calls } = mockApi([json(200, set)]);
    const caps = client.connect.capabilities.forConnection('con_4Rt8Vb2MnQ');
    assert.equal(await caps.supports('record.write'), true);
    assert.equal(await caps.supports('patient.search'), false);
    assert.deepEqual(await caps.callable(), ['diagnosis.write.text', 'record.write']);
    assert.deepEqual(await caps.limits('diagnosis.write.text'), ['text_only']);
    const unsupported = await caps.require('diagnosis.write').catch((e: unknown) => e);
    assert.ok(unsupported instanceof NotSupportedError);
    assert.equal(unsupported.alternative, 'diagnosis.write.text');
    await assert.rejects(caps.require('medication.order'), VerbNotGrantedError);
    const unknown = await caps.require('patient.search').catch((e: unknown) => e);
    assert.ok(unknown instanceof CapabilityError);
    assert.equal(unknown.code, 'capability_status_unknown');
    assert.equal(calls.length, 1);
  });
});

describe('uploads', () => {
  test('a file keeps its name; bytes without a name take the field name', async () => {
    const forms: FormData[] = [];
    const answer = async (req: Request) => {
      forms.push(await req.formData());
      return json(202, { id: 'tr_1', object: 'transcription', status: 'queued', created_at: '2026-11-16T09:00:00Z' });
    };
    const { client } = mockApi([answer, answer]);
    await client.audio.transcriptions.create({ file: toFile(new Uint8Array(10), 'visit.wav', 'audio/wav'), speaker_roles: true });
    await client.audio.transcriptions.create({ file: new Uint8Array(4) });
    const first = forms[0]?.get('file') as File;
    assert.equal(first.name, 'visit.wav');
    assert.match(first.type, /wav/);
    assert.equal(forms[0]?.get('speaker_roles'), 'true');
    assert.equal((forms[1]?.get('file') as File).name, 'file');
  });
});
