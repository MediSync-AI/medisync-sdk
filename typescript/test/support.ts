import { MediSync, type ClientOptions } from '../src/index.ts';

export const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', ...headers } });

/** A client whose fetch answers from a list, and the requests it got. */
export function mockApi(answers: Array<Response | ((req: Request) => Response | Promise<Response>)>, options: ClientOptions = {}) {
  const calls: Request[] = [];
  const sleeps: number[] = [];
  const fetch = async (req: Request) => {
    calls.push(req.clone());
    const next = answers.shift();
    if (!next) throw new Error('no more answers');
    return typeof next === 'function' ? next(req) : next;
  };
  const client = new MediSync({
    apiKey: 'msk_test_abc',
    baseUrl: 'https://api.example.test',
    fetch,
    sleep: async (ms) => {
      sleeps.push(ms);
    },
    ...options,
  });
  return { client, calls, sleeps };
}

export const transfer = (status: string, extra: Record<string, unknown> = {}) => ({
  id: 'trf_7Yc3Nd9LxB',
  plan_id: 'pln_2Vh8Ms6TqW',
  connection_id: 'con_4Rt8Vb2MnQ',
  site_id: 'ste_7Hq2Lk9PwX',
  verb: 'record.write',
  status,
  release: {},
  operations: [],
  history: [],
  expires_at: '2026-11-05T09:00:00Z',
  created_at: '2026-11-04T09:00:00Z',
  updated_at: '2026-11-04T09:00:00Z',
  ...extra,
});
