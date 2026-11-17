// SDK names of the platform operations, and the helper methods every language adds.
//
// Connect operations need no table: `transfers.create` is `client.connect.transfers.create`.
// The platform spec uses operationIds like `createNote`, so this table gives each one its place
// on the client. The generator fails when the spec has an operation that is not listed here.

export const PLATFORM_NAMES: Record<string, string> = {
  createTranscription: 'audio.transcriptions.create',
  getTranscription: 'audio.transcriptions.get',
  deleteTranscription: 'audio.transcriptions.delete',
  createStreamToken: 'audio.stream.tokens.create',
  openStream: 'audio.stream.open',
  createNote: 'notes.create',
  createLetter: 'letters.create',
  codeIcd10gm: 'coding.icd10gm',
  searchCodes: 'coding.search',
  getCatalogue: 'coding.catalogue',
  getCode: 'coding.codes.get',
  refineCode: 'coding.codes.refine',
  createDecision: 'decisions.create',
  createDecisionBatch: 'decisions.createBatch',
  listModels: 'models.list',
  getUsage: 'usage.get',
  getCosts: 'costs.get',
  getOpenApi: 'specs.openapi',
  getAsyncApi: 'specs.asyncapi',
};

export interface Helper {
  /** Dotted camelCase name on the client. */
  name: string;
  doc: string;
}

/** Methods that are no API operation. Each language implements them by hand. */
export const HELPERS: Helper[] = [
  {
    name: 'audio.transcriptions.wait',
    doc: 'Helper, no API operation: reads the transcription until its status is completed, failed or expired.',
  },
  {
    name: 'connect.transfers.wait',
    doc: 'Helper, no API operation: reads the transfer until it is final or in_doubt, or until the `until` check holds.',
  },
  {
    name: 'connect.events.poll',
    doc: 'Helper, no API operation: polls events.list from a cursor on and yields each page with events. Save next_cursor once a page is handled.',
  },
  {
    name: 'connect.capabilities.forConnection',
    doc: 'Helper, no API operation: a cached view of capabilities.get for one connection, with supports(), callable(), limits() and require().',
  },
];
