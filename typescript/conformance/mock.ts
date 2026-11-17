// Starts the mock server of ../../conformance/mock with bun, or uses MEDISYNC_MOCK_URL.
import { spawn, type ChildProcess } from 'node:child_process';
import { fileURLToPath } from 'node:url';

export interface Mock {
  url: string;
  reset(script: { responses?: unknown[]; ws?: unknown }): Promise<void>;
  requests(): Promise<any[]>;
  ws(): Promise<any[]>;
  stop(): Promise<void>;
}

const SERVER = fileURLToPath(new URL('../../conformance/mock/server.ts', import.meta.url));

export async function startMock(): Promise<Mock> {
  let child: ChildProcess | null = null;
  let url = process.env.MEDISYNC_MOCK_URL ?? '';
  if (!url) {
    child = spawn(process.env.BUN ?? 'bun', [SERVER, '--port', '0'], { stdio: ['ignore', 'pipe', 'inherit'] });
    url = await new Promise<string>((resolve, reject) => {
      let out = '';
      child?.stdout?.on('data', (chunk: Buffer) => {
        out += chunk.toString();
        const m = /MOCK_URL=(\S+)/.exec(out);
        if (m) resolve(m[1] as string);
      });
      child?.on('exit', (code) => reject(new Error(`The mock server ended with ${code}. Is bun installed?`)));
      child?.on('error', reject);
    });
  }
  const json = async (path: string, init?: RequestInit) => (await fetch(`${url}${path}`, init)).json();
  return {
    url,
    async reset(script) {
      await json('/__mock/reset', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(script) });
    },
    requests: () => json('/__mock/requests') as Promise<any[]>,
    ws: () => json('/__mock/ws') as Promise<any[]>,
    async stop() {
      if (!child) return;
      child.kill();
      await new Promise((r) => child?.once('exit', r));
    },
  };
}
