# MediSync SDKs

Official client libraries for the MediSync API: speech to text (batch and live), clinical notes and letters, ICD-10-GM coding, decisions, account reads and MediSync Connect. One package per language, one client for every public API.

| Language | Package | Folder |
|---|---|---|
| TypeScript / JavaScript (Node, Bun, Deno) | `medisync` on npm, `@medisync/medisync` on JSR | [typescript/](typescript/) |
| Python | `medisync` on PyPI | [python/](python/) |
| Java / Kotlin | `me.medisync:medisync` on Maven Central | [java/](java/) |
| C# / .NET | `MediSync` on NuGet | [dotnet/](dotnet/) |

API reference: https://docs.medisync.me

Everything the API generates is a draft. A clinician reviews and releases it.

## Layout

| Folder | What it holds |
|---|---|
| `specs/` | The API specs as docs.medisync.me publishes them: `openapi.json` (platform API), `connect-openapi.json` (MediSync Connect), `asyncapi.yaml` (live speech). `SOURCE.json` names the commit and the sha256 of each file. |
| `generator/` | Our code generator (TypeScript, run with bun). It reads the operationId, tags and `x-medisync-*` fields and writes the models and resource classes of each language. |
| `conformance/` | The shared test cases, the mock server built from the specs, and `operations.json`, the method name of every operation in every language. |
| `typescript/`, `python/`, `java/`, `dotnet/` | The packages. Generated code is in `typescript/src/generated`, `python/src/medisync/_generated`, `java/src/main/java/me/medisync/models` and `resources`, and `dotnet/src/MediSync/Generated`; the rest is written by hand. |
| `release/` | The leak check and the install tests that run before every publish. |

## Work on the SDKs

You need bun 1.4, Node 20 or later, and uv. For Java: a JDK 11 or later (the Maven wrapper fetches Maven). For .NET: the .NET 8 SDK.

```sh
bun install
bun run specs:sync      # copy the specs from a committed ref of the docs repository
bun run generate        # write the generated code of every language
bun run test            # generator and mock tests

cd typescript && npm ci && npm run lint && npm test && npm run conformance
cd python && uv sync && uv run ruff check src tests && uv run mypy && uv run pyright && uv run pytest
cd java && ./mvnw verify
cd dotnet && dotnet test && dotnet pack src/MediSync -c Release -o ../dist/dotnet
```

`bun run generate:check` fails when generated files are out of date. CI runs it.

## Releases

All four packages share one version and are released together. `bun run version set 0.2.0` writes the version into every package; CI fails when the packages disagree. After the pull request is merged into `dev`, an admin pushes the tag `v0.2.0` on the merge commit. The `Release` workflow then runs every test, builds each package once, runs the leak check on the built files, installs each file into a clean project and runs it against the API mock (`release/install-test`), and publishes those files to npm, JSR, PyPI, Maven Central and NuGet after one approval. It ends with a GitHub release. A version such as `0.2.0-beta.1` is a prerelease everywhere and goes to the npm dist-tag `beta`.

To rehearse a release without publishing, start the `Release` workflow by hand on `dev`.
