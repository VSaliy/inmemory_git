# In-memory Git, based on the first Git commit

A small, dependency-free Java 17 implementation of the content-addressed
snapshot design in Linus Torvalds's
[7 April 2005 Git revision](https://github.com/git/git/commit/e83c5163316f89bfbde7d9ab23ca2e25604af290).
The working directory, index, and object database are Java collections. Creating
and using a repository never reads or writes files, runs Git, or starts a process.
Maven and Gradle builds use the same Java sources and JUnit test suite.

## Build and run

Install a JDK 17 or newer and choose either build tool. The included wrappers
download their pinned Maven or Gradle version on first use; a separate build-tool
installation is unnecessary. The first build also downloads plugins and the
test-only JUnit dependencies. The library has no runtime dependencies.

From this project directory, in PowerShell:

```powershell
# Maven
./mvnw.cmd clean verify
./mvnw.cmd compile exec:java

# Gradle
./gradlew.bat clean build
./gradlew.bat run
```

On Linux or macOS:

```bash
# Maven
./mvnw clean verify
./mvnw compile exec:java

# Gradle
./gradlew clean build
./gradlew run
```

Both builds compile UTF-8 sources with `--release 17 -Xlint:all -Werror` and run
all 15 JUnit Jupiter scenario tests. Maven writes output to `target/` and test
reports to `target/surefire-reports/`. Gradle writes output to `build/`, with an
HTML test report at `build/reports/tests/test/index.html` and XML results in
`build/test-results/test/`.

The packaged JAR also runs the demo:

```bash
# After the Maven build
java -jar target/inmemory-git-1.0-SNAPSHOT.jar

# After the Gradle build
java -jar build/libs/inmemory-git-1.0-SNAPSHOT.jar
```

The existing convenience commands, `./scripts/run.ps1 test` or
`sh scripts/run.sh test`, now use the Maven wrapper. They also accept `demo` and
`all` (the default), with `all` running tests followed by the demo.

## What the original implemented

The first revision built seven small utilities around two ideas: immutable
objects named by their contents, and a mutable sorted directory cache that
selects the next snapshot. A blob holds file bytes. A tree records cached paths,
file modes, and blob IDs. A commit records a complete tree, ordered parent commit
IDs, author/committer identities, dates, and a message.

Two historical details matter here: **IDs hash the zlib-compressed object bytes**,
and **trees are flat lists of full paths**. The inflated representation is
`type SPACE decimal-byte-length NUL payload`. Tree entries contain an octal mode,
path, NUL, and twenty raw blob-ID bytes. This follows the initial implementation;
it is not a modern Git repository format.

`init-db` prepared storage; `update-cache` staged or removed files; `write-tree`
froze the cache; `commit-tree` recorded a tree and explicit parents; `read-tree`
only printed tree entries; `cat-file` extracted a payload to a temporary file;
`show-diff` compared tracked files with the cache using stat data and external
`diff`. There were no implemented branches, `HEAD`, checkout, network transport,
or merge algorithm. The source-verified explanation, original index layout,
source links, and deliberate Java differences are in
[docs/HISTORICAL_REFERENCE.md](docs/HISTORICAL_REFERENCE.md).

## Use the Java API

```java
import io.github.inmemorygit.InMemoryGit;
import java.util.List;

var git = new InMemoryGit();
var who = new InMemoryGit.Signature(
    "A Person", "a@example.com", "Thu Apr  7 15:13:13 2005");

git.putText("src/hello.txt", "hello\n"); // working directory only
git.updateCache("src/hello.txt");        // immutable blob + index entry
var tree = git.writeTree();               // immutable flat snapshot
var first = git.commitTree(tree, List.of(), who, who, "Initial snapshot\n");

git.putText("src/hello.txt", "updated\n");
git.showDiff();                           // reports unstaged tracked changes
assert git.writeTree().equals(tree);      // index still contains the old blob
git.updateCache("src/hello.txt");
var second = git.commitTree(git.writeTree(), List.of(first), who, who, "Update\n");
git.readCommit(second).parents();          // [first]
git.readTree(tree);                       // inspect without changing state
git.restoreTree(tree);                    // explicitly replace working files + index
```

To stage a deletion, call `removeFile(path)` and then `updateCache(path)`.
`putFile(path, bytes, FileMode.EXECUTABLE_FILE)` supports binary executable files.
`catFile(id)` returns a type and defensive copy of its binary payload.
`commitTree` never stages files or advances an implicit current commit. Keep the
returned IDs and supply the desired parents for each subsequent commit. Multiple
parents record ancestry only; callers must supply the resulting snapshot.

## Structure and limits

- `InMemoryGit.java`: working directory, sorted index, flat trees, commits,
  tracked-file comparisons, and explicit snapshot restoration.
- `InMemoryObjectStore.java`: compressed, content-addressed immutable objects,
  deduplication, and read integrity checks.
- `ObjectId.java`, `ObjectType.java`, `GitObject.java`: immutable object values.
- `Demo.java`: two snapshots, parent linkage, object sharing, and restoration.
- `src/test/java/io/github/inmemorygit/InMemoryGitTest.java`: JUnit scenario tests.

This adaptation supports empty files/trees and validates object types and
references. It accepts UTF-8 paths with unsigned byte ordering, regular `100644`
and executable `100755` modes, and at most sixteen ordered parents. As in the
original path filter, dot-prefixed path components are rejected. It also rejects
ambiguous paths and file/directory conflicts. Dates and messages are explicit;
no host identity or clock is read. Messages are preserved exactly, including
whether they end in a newline.

`readTree` only inspects. The additional `restoreTree` operation replaces the
**whole** working directory and index, including removal of untracked files;
stored objects remain available. `showDiff` returns structured binary content
and mode differences for tracked paths, without producing a text patch.

Repositories are single-threaded, process-local, bounded by available heap, and
discarded when the instance is lost. There is no persistence, object pruning,
symlink support, branch management, transport, or merge implementation. SHA-1
and compressed-byte identity are retained for historical fidelity; output IDs
can vary with compression implementations. This is an educational library, with
no promise of modern Git interoperability.
