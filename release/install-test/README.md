# Install tests

The release installs each built package into a clean project, the way a user gets it from the registry, and runs it against the mock of the MediSync API (`conformance/mock`). A package is published only after its install test passed. Each test checks:

- the installed version equals the release version, and the `User-Agent` header carries it;
- a platform call and a MediSync Connect call, with both the sync and the async client where the language has both;
- an error answer: a 404 becomes `NotFoundError` / `NotFoundException` with the code and the request ID.

| Package | Installed from | Runs on |
|---|---|---|
| TypeScript | the npm tarball | Node 20 and 22 (ESM, CommonJS, `tsc --strict`), Bun, Deno |
| Python | the wheel and the sdist | Python 3.9 and 3.13 |
| Java | the jar and POM in a local Maven repository | Java 11 and 21 |
| .NET | the `.nupkg` in a local feed | a net8.0 app, and a netstandard2.0 library |

Run one by hand after a build, with the version of the files:

```sh
release/install-test/typescript/run.sh out/medisync-0.1.0.tgz 0.1.0
release/install-test/python/run.sh dist 0.1.0
release/install-test/java/run.sh java/target 0.1.0
release/install-test/dotnet/run.sh dist/dotnet 0.1.0
```
