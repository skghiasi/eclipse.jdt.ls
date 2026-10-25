# codoro's jdtls

A fork of the [Eclipse JDT Language Server](https://github.com/eclipse-jdtls/eclipse.jdt.ls) carrying the changes
[codoro](https://github.com/skghiasi/codoro) needs. The branch `codoro/1.61` is the official `v1.61.0` tag plus
commits: `git log v1.61.0..codoro/1.61` is the complete list of what differs from the release, and
`git diff v1.61.0 codoro/1.61 -- org.eclipse.jdt.ls.core` is the code.

| Change | Why |
|---|---|
| `CallHierarchyHandler`: a caller without source no longer fails the request | One library caller made `callHierarchy/incomingCalls` fail for every caller of the method (`AssertionFailedException: null argument:start`). Upstream issue [#3298](https://github.com/eclipse-jdtls/eclipse.jdt.ls/issues/3298). |

## The archive

`codoro/build.sh` downloads the **official** 1.61.0 archive, refuses to go on unless it matches the checksum Eclipse
publishes, compiles the files in `codoro/patched-sources.txt` from this tree against the archive's own jars, puts the
classes into the core jar, drops that jar's signature files, and writes an archive with the official layout. The same
inputs give the same bytes. Releases of this fork carry the archive and its SHA-256; codoro pins both.

It is not an official Eclipse build, and says so inside (`CODORO-PATCHES.txt`). The licence is the project's:
EPL-2.0, see `LICENSE`.

## A new jdtls release

1. `git rebase --onto <new tag> v1.61.0 codoro/1.61` (or a new branch from the new tag, cherry-picking the commits).
2. In `codoro/build.sh`, change the four values at the top (URL, checksum, tag, version).
3. Tag `v<new version>-codoro.1` and push the tag; the workflow builds and publishes the archive.
4. Put the new URL and SHA-256 in codoro's `src/lsp/servers/jdtls.rs` and `src/artifacts.rs`.
