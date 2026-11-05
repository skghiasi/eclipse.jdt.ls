# Batch file facts for the Eclipse JDT Language Server

A fork of the [Eclipse JDT Language Server](https://github.com/eclipse-jdtls/eclipse.jdt.ls). The branch `batch-facts/1.61`
is the official `v1.61.0` tag plus commits: `git log v1.61.0..batch-facts/1.61` is the complete list of what differs from
the release, and `git diff v1.61.0 batch-facts/1.61 -- org.eclipse.jdt.ls.core` is the change to the server itself.

| Change | Why |
|---|---|
| `CallHierarchyHandler`: a caller without source no longer fails the request | One library caller made `callHierarchy/incomingCalls` fail for every caller of the method (`AssertionFailedException: null argument:start`). Upstream issue [#3298](https://github.com/eclipse-jdtls/eclipse.jdt.ls/issues/3298). |

## The extension

`batch-facts/extension` is an OSGi bundle (`jdtls.batchfacts`) that adds one command to the server,
`java.batch.fileFacts`. For a file it answers from one compiler pass what a client would otherwise ask the server
thousands of times: where each name is defined, which methods override which, and the calls that are not a plain
identifier (object creations, `super(..)`, method references, annotation uses). Names the compiler cannot resolve
are reported as such, so the client can ask the server for those the usual way. The archive carries the bundle as
`plugins/jdtls.batchfacts_0.1.0.jar` and starts it with the other bundles, so a client only has to see the
command in `executeCommandProvider.commands`.

## The archive

`batch-facts/build.sh [output dir]` downloads the **official** 1.61.0 archive, refuses to go on unless it matches the
checksum Eclipse publishes, compiles the files in `batch-facts/patched-sources.txt` from this tree against the archive's
own jars, puts the classes into the core jar, drops that jar's signature files, adds the bundle, and writes an archive
with the official layout, and its SHA-256. The same inputs give the same bytes. Set `ARCHIVE_REVISION` to number the
archive (`jdt-language-server-1.61.0-batch.<revision>.tar.gz`).

Each release carries the archive under its numbered name and again as `jdt-language-server-batch.tar.gz` with a
`jdt-language-server-batch.tar.gz.sha256` beside it, so a client can always fetch the newest build from
`releases/latest/download/jdt-language-server-batch.tar.gz` and learn from the checksum whether it changed.

It is not an official Eclipse build, and says so inside (`PATCHES.txt`). The licence is the project's: EPL-2.0, see
`LICENSE`.

## A new jdtls release

1. `git rebase --onto <new tag> v1.61.0 batch-facts/1.61` (or a new branch from the new tag, cherry-picking the commits).
2. In `batch-facts/build.sh`, change the four values at the top (URL, checksum, tag, version).
3. Tag `v<new version>-batch.1` and push the tag; the workflow builds and publishes the archive.
