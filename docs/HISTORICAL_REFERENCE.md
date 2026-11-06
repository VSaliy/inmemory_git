# The 7 April 2005 Git reference

This project follows the content-addressed snapshot design in Linus Torvalds's
first Git revision. It is an in-memory Java adaptation of that revision, not an
implementation of the present-day Git command line or repository format.

## Verified revision

The reference is commit
[`e83c5163316f89bfbde7d9ab23ca2e25604af290`](https://github.com/git/git/commit/e83c5163316f89bfbde7d9ab23ca2e25604af290),
authored and committed by Linus Torvalds on 7 April 2005 at 15:13:13 -0700.
Its message is `Initial revision of "git", the information manager from hell`.
The kernel.org mirror's [commit metadata](https://kernel.googlesource.com/pub/scm/git/git/+/e83c5163316f89bfbde7d9ab23ca2e25604af290?format=JSON)
explicitly records an empty parent list and tree
`2b5bfdf7798569e0b59b16eb9602d5fa572d6038`. These identify the archived source
revision; they are not promised output IDs of the Java demonstration.

All eleven files at that exact revision were inspected. The
[Makefile](https://github.com/git/git/blob/e83c5163316f89bfbde7d9ab23ca2e25604af290/Makefile)
builds seven programs: `init-db`, `update-cache`, `write-tree`, `read-tree`,
`commit-tree`, `cat-file`, and `show-diff`. The other files are `README`,
`cache.h`, and the shared implementation `read-cache.c`.

## Objects and identity

The [original README](https://github.com/git/git/blob/e83c5163316f89bfbde7d9ab23ca2e25604af290/README)
separates immutable, named objects from a mutable directory cache. Blobs contain
bytes; trees associate paths and modes with blob IDs; commits associate a tree
with parent commits, identities, dates, and a message. Reusing an object ID
reuses its contents, so a snapshot does not require another copy of each
unchanged file. A commit describes a complete result tree; it does not store a
patch or explicit rename records.

The inflated object representation is:

```text
ASCII(type) + SPACE + ASCII(decimal payload length in bytes) + NUL + payload
```

There is a significant historical detail: **this revision hashes the compressed
object bytes**. `write_sha1_file` in
[`read-cache.c`](https://github.com/git/git/blob/e83c5163316f89bfbde7d9ab23ca2e25604af290/read-cache.c)
uses zlib with `Z_BEST_COMPRESSION`, then computes SHA-1 over that zlib stream.
`index_fd` in
[`update-cache.c`](https://github.com/git/git/blob/e83c5163316f89bfbde7d9ab23ca2e25604af290/update-cache.c)
does the same for blobs. The result is 20 bytes, normally displayed as 40
hexadecimal characters. Hashing an uncompressed `blob <size>\0<payload>` would
implement a different identity rule from this source revision.

The on-disk store uses `.dircache/objects/xx/yyyy...`, splitting the hexadecimal
ID after two characters. The `SHA1_FILE_DIRECTORY` environment variable can
select another object directory. `init-db` creates `.dircache`, the default
object directory, and its 256 two-hex-digit subdirectories; object writes use
exclusive file creation and treat an existing ID as already stored.
[`init-db.c`](https://github.com/git/git/blob/e83c5163316f89bfbde7d9ab23ca2e25604af290/init-db.c)
and [`read-cache.c`](https://github.com/git/git/blob/e83c5163316f89bfbde7d9ab23ca2e25604af290/read-cache.c)
contain these operations.

## Directory cache and tree snapshots

The original index is `.dircache/index`. It is an ordered array of unique path
entries. Each entry contains file timestamps, device/inode, mode, user/group,
size, a blob ID, and a pathname. Its version-1 header contains the `DIRC`
signature, entry count, and a SHA-1 checksum covering the header fields before
the checksum plus the entries. Entries are padded to an eight-byte boundary.
The layout uses native CPU representation deliberately; it is not a portable
serialization. See
[`cache.h`](https://github.com/git/git/blob/e83c5163316f89bfbde7d9ab23ca2e25604af290/cache.h)
and [`read-cache.c`](https://github.com/git/git/blob/e83c5163316f89bfbde7d9ab23ca2e25604af290/read-cache.c).

`update-cache path...` reads files, stores their blobs, and inserts or replaces
sorted cache entries. A missing file removes its existing entry. It updates
the index through `.dircache/index.lock` followed by rename. Paths containing
a component starting with `.` are rejected, including all dotfiles; leading,
repeated, and trailing slashes are rejected too. The cache can differ from the
working directory: later file edits do not change an already cached blob ID.
[`update-cache.c`](https://github.com/git/git/blob/e83c5163316f89bfbde7d9ab23ca2e25604af290/update-cache.c)
implements these behaviors.

`write-tree` reads the cache, checks that each referenced object is readable,
and serializes the ordered entries. Each tree payload entry is:

```text
ASCII(octal file mode) + SPACE + pathname bytes + NUL + 20 raw blob-ID bytes
```

**The implementation is flat.** A path such as `src/Main.java` is one entry
whose name includes `/`; this program does not build recursive directory
trees. It writes the full `st_mode`, and it refuses an empty index. The
snapshot depends on the cached paths, modes, and blob IDs, not on the current
file contents or the cache's timestamp/inode metadata.
[`write-tree.c`](https://github.com/git/git/blob/e83c5163316f89bfbde7d9ab23ca2e25604af290/write-tree.c)
is the precise reference.

Despite its name, the initial `read-tree` only decodes and prints a tree's
entries, including octal modes, paths, and hexadecimal blob IDs. It does not
replace the cache or recreate files. This distinction comes directly from
[`read-tree.c`](https://github.com/git/git/blob/e83c5163316f89bfbde7d9ab23ca2e25604af290/read-tree.c),
even though the README discusses cache regeneration as a general use of trees.

## Commits and utility operations

`commit-tree <tree-id> [-p <parent-id>]*` reads a message from standard input and
constructs this payload:

```text
tree <40-hex tree ID>
parent <40-hex parent ID>       # repeated in caller-specified order, if any
author <name> <email-in-angle-brackets> <date text>
committer <name> <email-in-angle-brackets> <date text>

<message bytes>
```

Here the angle-bracketed placeholders describe fields; the stored identity line
looks like `author A Person <a@example.test> Thu Apr  7 15:13:13 2005`.
Zero parents produces a root, one records ordinary ancestry, and multiple
parents can record ancestry from several commits. Parent order affects the
object ID. Listing multiple parents does not perform a content merge. The
source allocates space for sixteen parent IDs, without enforcing that bound
in the argument loop.

The original derives its default identity from the operating-system account,
hostname, and local `ctime` date. Its `COMMITTER_NAME`, `COMMITTER_EMAIL`, and
`COMMITTER_DATE` variables actually override the **author** fields; the
committer fields still come from the real account/time. It removes newlines
and angle brackets from these inputs. It parses hexadecimal arguments but
does not check that the named tree and parents exist or have the expected
object types. It writes the commit object and prints its ID; there is no
automatic branch or current-commit update. These details are in
[`commit-tree.c`](https://github.com/git/git/blob/e83c5163316f89bfbde7d9ab23ca2e25604af290/commit-tree.c).

The initial `cat-file` inflates an object, writes the payload into a temporary
file named `temp_git_file_XXXXXX`, and prints that file's name and the object
type. It does not print the payload directly to standard output.
[`cat-file.c`](https://github.com/git/git/blob/e83c5163316f89bfbde7d9ab23ca2e25604af290/cat-file.c)
contains the complete utility.

`show-diff` iterates tracked cache entries, compares their saved stat metadata
with the live files, reports missing files, and invokes external `diff -u`
with cached blob contents when metadata differs. It does not enumerate
untracked files. See
[`show-diff.c`](https://github.com/git/git/blob/e83c5163316f89bfbde7d9ab23ca2e25604af290/show-diff.c).

This source revision has no implementation of named branches, `HEAD`, checkout,
log traversal, network transport, packfiles, or a merge algorithm. Its core is
the plumbing needed to name file contents, freeze a cached snapshot, and give
that snapshot explicit ancestry.

## Deliberate choices in this Java adaptation

The Java design retains compressed-byte SHA-1 identities, blobs, flat ordered
trees, snapshot commits, explicit ordered parents, and an index separate from
working contents. It changes the surrounding machinery as follows:

| Original mechanism | Java mechanism and reason |
| --- | --- |
| Files under `.dircache`, memory maps, lockfiles, and temporary output files | Object-store map, sorted index map, and working-file map. All repository state stays in memory; APIs return data. |
| Native binary cache plus OS stat metadata | Path, mode, and blob ID entries. Memory has no filesystem inode or owner cache to maintain. |
| Arbitrary pathname bytes and raw `st_mode` values | Validated UTF-8 paths sorted by unsigned encoded bytes, with regular `100644` and executable `100755` file modes. Ambiguous paths and file/directory conflicts are rejected. |
| `read-tree` prints entries | `readTree` returns entries without mutating repository state. `unpackTree` explicitly replaces the whole index and working directory, including removal of untracked files (`restoreTree` remains a compatibility alias). |
| OS identity, environment overrides, and implicit current time | Explicit author and committer signatures with caller-supplied textual dates, including original-style dates. This makes commits repeatable and independent of the host account/timezone. |
| Host bytes for paths and commit text | Paths, identities, dates, and commit messages use validated UTF-8 strings. Blob contents remain arbitrary binary bytes. Message text is preserved exactly, including a missing final newline. |
| Empty index refused; zero-length files encounter the original memory-map limitation | Empty trees and empty blobs are supported. They are useful valid snapshots without reproducing historical implementation limitations. |
| Minimal reference/type checks and an unchecked sixteen-parent array | The repository API validates input, object format, tree/blob/parent references, and the sixteen-parent limit. The low-level object store handles typed binary payloads; repository-level parsing checks their tree/commit structure. |
| Stat heuristics and an external line-diff subprocess | `showDiff` returns structured content/mode changes for tracked paths by comparing in-memory bytes and modes. |

No branch/`HEAD`, network, or merge layer has been added. Commit IDs are the
caller's history handles. Stored byte arrays are protected from caller
mutation so that returning or editing data cannot silently rewrite an older
snapshot.

This is a historical format adaptation, not a claim of interchangeability
with modern Git or of identical compression output across every runtime. Since
the compressed representation participates in identity, different zlib
implementations or versions can produce different IDs for the same inflated
bytes. SHA-1 is retained to explain the original design; it is a legacy choice
and should not be treated as a contemporary security guarantee.
