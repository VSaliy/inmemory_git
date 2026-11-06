package io.github.inmemorygit;

import static io.github.inmemorygit.InMemoryGit.ChangeType.DELETED;
import static io.github.inmemorygit.InMemoryGit.ChangeType.MODE_CHANGED;
import static io.github.inmemorygit.InMemoryGit.ChangeType.MODIFIED;
import static io.github.inmemorygit.InMemoryGit.FileMode.EXECUTABLE_FILE;
import static io.github.inmemorygit.InMemoryGit.FileMode.REGULAR_FILE;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.InflaterInputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Behavioral tests shared by the Maven and Gradle builds. */
public final class InMemoryGitTest {
    private static final InMemoryGit.Signature AUTHOR = new InMemoryGit.Signature(
            "Linus Example", "linus@example.test", "Thu Apr 7 15:13:13 2005");
    private static final InMemoryGit.Signature COMMITTER = new InMemoryGit.Signature(
            "Java Example", "java@example.test", "Thu Apr 7 15:14:00 2005");

    @Test
    @DisplayName("compressed object identity and historical fixture")
    void compressedIdentity() throws Exception {
        var store = new InMemoryObjectStore();
        ObjectId id = store.put(ObjectType.BLOB, utf8("hello\n"));
        byte[] compressed = store.compressedBytes(id);
        byte[] inflated;
        try (var input = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            inflated = input.readAllBytes();
        }
        bytes(utf8("blob 6\0hello\n"), inflated);
        equal(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(compressed)), id.hex());
        check(!id.hex().equals("ce013625030ba8dba906f756967f9e9ca394464a"),
                "The first implementation hashes compressed bytes, unlike modern Git");
        // Independently generated with Node's zlib level 9 and crypto SHA-1.
        equal("d1411423ff8cd2481a52ee2a96a999bf676d6242", id.hex());
        equal("78da4bcac94f523063c848cdc9c9e702001dc50414", HexFormat.of().formatHex(compressed));
    }

    @Test
    @DisplayName("binary, empty, Unicode, and large object round trips")
    void payloadRoundTrips() throws Exception {
        var store = new InMemoryObjectStore();
        byte[] large = new byte[150_000];
        new java.util.Random(2005).nextBytes(large);
        for (byte[] payload : List.of(new byte[0], new byte[] {0, 1, -1, 0}, utf8("café 🦊"), large)) {
            ObjectId id = store.put(ObjectType.BLOB, payload);
            GitObject object = store.get(id);
            equal(ObjectType.BLOB, object.type());
            equal(payload.length, object.size());
            bytes(payload, object.content());
            try (var input = new InflaterInputStream(new ByteArrayInputStream(store.compressedBytes(id)))) {
                byte[] header = utf8("blob " + payload.length + "\0");
                byte[] actual = input.readAllBytes();
                bytes(header, Arrays.copyOf(actual, header.length));
                bytes(payload, Arrays.copyOfRange(actual, header.length, actual.length));
            }
        }
    }

    @Test
    @DisplayName("type separation and object deduplication")
    void typeSeparation() {
        var store = new InMemoryObjectStore();
        ObjectId blob = store.put(ObjectType.BLOB, utf8("same"));
        equal(blob, store.put(ObjectType.BLOB, utf8("same")));
        equal(1, store.objectCount());
        ObjectId tree = store.put(ObjectType.TREE, utf8("same"));
        ObjectId commit = store.put(ObjectType.COMMIT, utf8("same"));
        check(!blob.equals(tree) && !blob.equals(commit) && !tree.equals(commit), "Type is part of identity");
        equal(3, store.objectCount());
        check(store.contains(blob), "Stored object is present");
    }

    @Test
    @DisplayName("immutable object identifiers and object data")
    void objectBoundaries() {
        var store = new InMemoryObjectStore();
        byte[] input = utf8("original");
        ObjectId id = store.put(ObjectType.BLOB, input);
        input[0] = 'X';
        byte[] output = store.get(id).content();
        output[0] = 'Y';
        byte[] compressed = store.compressedBytes(id);
        compressed[0] ^= 1;
        bytes(utf8("original"), store.get(id).content());
        byte[] rawId = id.bytes();
        rawId[0] ^= 1;
        equal(id, ObjectId.fromBytes(id.bytes()));
        equal(id, new ObjectId(id.hex().toUpperCase(java.util.Locale.ROOT)));
        var standalone = new GitObject(ObjectType.BLOB, input);
        input[0] = 'Z';
        bytes(utf8("Xriginal"), standalone.content());
    }

    @Test
    @DisplayName("snapshots outlive working directory and index edits")
    void snapshotLifetime() {
        var git = new InMemoryGit();
        byte[] content = utf8("version one\n");
        git.putFile("file.txt", content);
        content[0] = 'X';
        git.updateCache("file.txt");
        ObjectId first = git.writeTree();
        ObjectId originalBlob = git.readTree(first).get(0).objectId();
        git.putText("file.txt", "version two\n");
        equal(first, git.writeTree());
        git.updateCache("file.txt");
        ObjectId second = git.writeTree();
        check(!first.equals(second), "Updating the cache changes a snapshot");
        git.removeFile("file.txt");
        git.updateCache("file.txt");
        equal(0, git.readTree(git.writeTree()).size());
        bytes(utf8("version one\n"), git.catFile(originalBlob).content());
        equal(originalBlob, git.readTree(first).get(0).objectId());
        equal(1, git.readTree(second).size());
    }

    @Test
    @DisplayName("flat tree binary encoding and insertion independence")
    void flatTreeEncoding() {
        var git = new InMemoryGit();
        git.putText("z.txt", "z");
        git.putFile("src/main.txt", utf8("main"), EXECUTABLE_FILE);
        git.putText("a.txt", "a");
        git.updateCache("z.txt", "src/main.txt", "a.txt");
        ObjectId tree = git.writeTree();
        var entries = git.readTree(tree);
        equal(List.of("a.txt", "src/main.txt", "z.txt"), entries.stream().map(InMemoryGit.TreeEntry::path).toList());
        var expected = new ByteArrayOutputStream();
        for (var entry : entries) {
            expected.writeBytes(utf8((entry.mode() == EXECUTABLE_FILE ? "100755 " : "100644 ") + entry.path() + "\0"));
            expected.writeBytes(entry.objectId().bytes());
        }
        GitObject object = git.catFile(tree);
        equal(ObjectType.TREE, object.type());
        bytes(expected.toByteArray(), object.content());
        var reversed = new InMemoryGit();
        reversed.putText("a.txt", "a");
        reversed.putFile("src/main.txt", utf8("main"), EXECUTABLE_FILE);
        reversed.putText("z.txt", "z");
        reversed.updateCache("a.txt", "src/main.txt", "z.txt");
        equal(tree, reversed.writeTree());
        expect(UnsupportedOperationException.class, entries::clear);
    }

    @Test
    @DisplayName("tree paths use unsigned UTF-8 byte order")
    void utf8PathOrdering() {
        var git = new InMemoryGit();
        for (String name : List.of("𐀀.txt", "\uE000.txt", "z.txt", "é.txt")) {
            git.putText(name, "same");
            git.updateCache(name);
        }
        equal(List.of("z.txt", "é.txt", "\uE000.txt", "𐀀.txt"),
                git.readTree(git.writeTree()).stream().map(InMemoryGit.TreeEntry::path).toList());
        equal(1L, git.indexEntries().stream().map(InMemoryGit.IndexEntry::objectId).distinct().count());
    }

    @Test
    @DisplayName("partial cache updates, replacement, and removal")
    void cacheUpdates() {
        var git = new InMemoryGit();
        git.putText("a", "one");
        git.putText("b", "two");
        git.updateCache("a", "b");
        ObjectId b = git.indexEntries().get(1).objectId();
        git.putText("a", "new one");
        git.putText("b", "unstaged two");
        git.updateCache("a");
        equal(b, git.indexEntries().get(1).objectId());
        equal(2, git.indexEntries().size());
        git.removeFile("a");
        git.updateCache("a", "never-existed");
        equal(List.of("b"), git.indexEntries().stream().map(InMemoryGit.IndexEntry::path).toList());
        expect(UnsupportedOperationException.class, () -> git.indexEntries().clear());

        git.putText("new", "not yet stored");
        List<InMemoryGit.IndexEntry> beforeRejectedUpdate = git.indexEntries();
        int countBeforeRejectedUpdate = git.objectCount();
        expect(IllegalArgumentException.class, () -> git.updateCache("new", ".hidden"));
        equal(beforeRejectedUpdate, git.indexEntries());
        equal(countBeforeRejectedUpdate, git.objectCount());

        var replacement = new InMemoryGit();
        replacement.putText("node", "old file");
        replacement.updateCache("node");
        replacement.removeFile("node");
        replacement.putText("node/child", "new child");
        List<InMemoryGit.IndexEntry> beforeConflict = replacement.indexEntries();
        expect(IllegalArgumentException.class, () -> replacement.updateCache("node/child"));
        equal(beforeConflict, replacement.indexEntries());
        replacement.updateCache("node", "node/child");
        equal(List.of("node/child"), replacement.indexEntries().stream().map(InMemoryGit.IndexEntry::path).toList());
        replacement.removeFile("node/child");
        replacement.putText("node", "new file");
        replacement.updateCache("node/child", "node");
        equal(List.of("node"), replacement.indexEntries().stream().map(InMemoryGit.IndexEntry::path).toList());
    }

    @Test
    @DisplayName("read-tree inspects without changing repository state")
    void readTreeIsInspection() {
        var git = new InMemoryGit();
        git.putText("a", "old");
        git.updateCache("a");
        ObjectId tree = git.writeTree();
        git.putText("a", "new");
        git.putText("untracked", "untouched");
        List<InMemoryGit.IndexEntry> before = git.indexEntries();
        git.readTree(tree);
        equal(before, git.indexEntries());
        bytes(utf8("new"), git.workingFile("a").orElseThrow().content());
        check(git.workingFile("untracked").isPresent(), "Inspection retains untracked files");
    }

    @Test
    @DisplayName("commit graph links complete immutable snapshots")
    void commitsLinkSnapshots() {
        var git = new InMemoryGit();
        git.putText("a", "one");
        git.updateCache("a");
        ObjectId firstTree = git.writeTree();
        ObjectId first = git.commitTree(firstTree, List.of(), AUTHOR, COMMITTER, "initial\n");
        git.putText("a", "two");
        git.updateCache("a");
        ObjectId secondTree = git.writeTree();
        ObjectId second = git.commitTree(secondTree, List.of(first), AUTHOR, COMMITTER, "next\n");
        equal(List.of(), git.readCommit(first).parents());
        equal(firstTree, git.readCommit(first).tree());
        equal(List.of(first), git.readCommit(second).parents());
        equal(secondTree, git.readCommit(second).tree());
        bytes(utf8("one"), git.catFile(git.readTree(firstTree).get(0).objectId()).content());
        equal(first, git.commitTree(firstTree, List.of(), AUTHOR, COMMITTER, "initial\n"));
        expect(UnsupportedOperationException.class, () -> git.readCommit(second).parents().clear());
    }

    @Test
    @DisplayName("commit text, ordered parents, and exact messages")
    void commitEncoding() {
        var git = new InMemoryGit();
        ObjectId tree = git.writeTree();
        ObjectId a = git.commitTree(tree, List.of(), AUTHOR, COMMITTER, "a");
        ObjectId b = git.commitTree(tree, List.of(), AUTHOR, COMMITTER, "b");
        String message = "merge café\n\nbody without trailing newline";
        ObjectId merge = git.commitTree(tree, List.of(a, b), AUTHOR, COMMITTER, message);
        String expected = "tree " + tree.hex() + "\nparent " + a.hex() + "\nparent " + b.hex()
                + "\nauthor " + AUTHOR.name() + " <" + AUTHOR.email() + "> " + AUTHOR.date()
                + "\ncommitter " + COMMITTER.name() + " <" + COMMITTER.email() + "> " + COMMITTER.date()
                + "\n\n" + message;
        equal(ObjectType.COMMIT, git.catFile(merge).type());
        bytes(utf8(expected), git.catFile(merge).content());
        var decoded = git.readCommit(merge);
        equal(List.of(a, b), decoded.parents());
        equal(AUTHOR, decoded.author());
        equal(COMMITTER, decoded.committer());
        equal(message, decoded.message());
        check(!merge.equals(git.commitTree(tree, List.of(b, a), AUTHOR, COMMITTER, message)),
                "Parent order is part of the commit identity");
        check(!a.equals(git.commitTree(tree, List.of(), AUTHOR, COMMITTER, "a\n")),
                "Messages retain their exact newline bytes");
    }

    @Test
    @DisplayName("working differences include content, modes, and deletions")
    void workingDiffs() {
        var git = new InMemoryGit();
        for (String name : List.of("content", "deleted", "mode", "unchanged")) {
            git.putText(name, "same");
            git.updateCache(name);
        }
        equal(List.of(), git.showDiff());
        git.putFile("content", utf8("edited"), EXECUTABLE_FILE);
        git.removeFile("deleted");
        git.putFile("mode", utf8("same"), EXECUTABLE_FILE);
        git.putText("untracked", "ignored by show-diff");
        var diff = git.showDiff();
        equal(List.of("content", "deleted", "mode"), diff.stream().map(InMemoryGit.DiffEntry::path).toList());
        equal(List.of(MODIFIED, DELETED, MODE_CHANGED), diff.stream().map(InMemoryGit.DiffEntry::kind).toList());
        bytes(utf8("same"), diff.get(0).indexed().content());
        bytes(utf8("edited"), diff.get(0).working().content());
        equal(null, diff.get(1).working());
        equal(REGULAR_FILE, diff.get(2).indexed().mode());
        equal(EXECUTABLE_FILE, diff.get(2).working().mode());
        git.updateCache("content", "deleted", "mode");
        equal(List.of(), git.showDiff());
    }

    @Test
    @DisplayName("unpack-tree recreates staged bytes and modes")
    void restoringTrees() {
        var git = new InMemoryGit();
        byte[] binary = new byte[] {0, 10, -1, 42};
        git.putFile("bin/run", binary, EXECUTABLE_FILE);
        git.putText("notes", "saved");
        git.updateCache("bin/run", "notes");
        ObjectId saved = git.writeTree();
        git.putText("notes", "dirty");
        git.removeFile("bin/run");
        git.putText("extra", "extra");
        git.updateCache("notes", "bin/run", "extra");
        git.unpackTree(saved);
        equal(saved, git.writeTree());
        bytes(binary, git.workingFile("bin/run").orElseThrow().content());
        equal(EXECUTABLE_FILE, git.workingFile("bin/run").orElseThrow().mode());
        bytes(utf8("saved"), git.workingFile("notes").orElseThrow().content());
        check(git.workingFile("extra").isEmpty(), "Restoration replaces the working directory");
        equal(List.of(), git.showDiff());
        byte[] returned = git.workingFile("notes").orElseThrow().content();
        returned[0] = 'X';
        bytes(utf8("saved"), git.workingFile("notes").orElseThrow().content());
        Map<String, InMemoryGit.WorkingFile> snapshot = git.workingDirectory();
        expect(UnsupportedOperationException.class, snapshot::clear);
        git.putText("later", "edit");
        check(!snapshot.containsKey("later"), "Working directory views are snapshots");
        var empty = new InMemoryGit();
        ObjectId emptyTree = empty.writeTree();
        empty.putText("remove-me", "x");
        empty.unpackTree(emptyTree);
        equal(0, empty.workingDirectory().size());
        git.restoreTree(saved);
        equal(saved, git.writeTree());
    }

    @Test
    @DisplayName("validation rejects malformed paths, IDs, and object types")
    void validation() {
        expect(IllegalArgumentException.class, () -> new ObjectId("a"));
        expect(IllegalArgumentException.class, () -> new ObjectId("g".repeat(40)));
        expect(IllegalArgumentException.class, () -> ObjectId.fromBytes(new byte[19]));
        var git = new InMemoryGit();
        for (String path : List.of("", "/a", "a/", "a//b", ".hidden", "a/.hidden", "a/../b",
                "./a", "a\\b", "a\0b", "\uD800")) {
            expect(IllegalArgumentException.class, () -> git.putText(path, "x"));
        }
        git.putText("directory/file", "x");
        expect(IllegalArgumentException.class, () -> git.putText("directory", "x"));
        git.putText("file", "x");
        expect(IllegalArgumentException.class, () -> git.putText("file/child", "x"));
        git.updateCache("file");
        ObjectId blob = git.indexEntries().get(0).objectId();
        ObjectId missing = new ObjectId("0".repeat(40));
        expect(IllegalArgumentException.class, () -> git.catFile(missing));
        expect(IllegalArgumentException.class, () -> git.readTree(blob));
        expect(IllegalArgumentException.class, () -> git.readCommit(blob));
        expect(IllegalArgumentException.class, () -> git.unpackTree(blob));
        expect(IllegalArgumentException.class, () -> git.commitTree(blob, List.of(), AUTHOR, COMMITTER, "bad"));
        ObjectId tree = git.writeTree();
        expect(IllegalArgumentException.class, () -> git.commitTree(tree, List.of(blob), AUTHOR, COMMITTER, "bad"));
        expect(IllegalArgumentException.class, () -> git.commitTree(tree, List.of(missing), AUTHOR, COMMITTER, "bad"));
        ObjectId parent = git.commitTree(tree, List.of(), AUTHOR, COMMITTER, "valid parent");
        expect(IllegalArgumentException.class, () -> git.commitTree(tree,
                java.util.Collections.nCopies(17, parent), AUTHOR, COMMITTER, "too many parents"));
        expect(IllegalArgumentException.class, () -> new InMemoryGit.Signature("name\ninjection", "e", "d"));
        expect(IllegalArgumentException.class, () -> new InMemoryGit.Signature("name", "bad>email", "d"));
        expect(IllegalArgumentException.class, () -> new InMemoryGit.Signature("name", "email", "bad\rdate"));
    }

    @Test
    @DisplayName("repository instances keep independent in-memory state")
    void independentRepositories() {
        var first = new InMemoryGit();
        var second = new InMemoryGit();
        first.putText("a", "one");
        first.updateCache("a");
        ObjectId firstTree = first.writeTree();
        equal(0, second.objectCount());
        equal(0, second.workingDirectory().size());
        equal(0, second.indexEntries().size());
        expect(IllegalArgumentException.class, () -> second.readTree(firstTree));
        second.putText("a", "one");
        second.updateCache("a");
        equal(firstTree, second.writeTree());
        first.removeFile("a");
        check(second.workingFile("a").isPresent(), "Repository state is not static or shared");
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void bytes(byte[] expected, byte[] actual) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError("Expected bytes " + HexFormat.of().formatHex(expected)
                    + " but received " + HexFormat.of().formatHex(actual));
        }
    }

    private static void equal(Object expected, Object actual) {
        check(java.util.Objects.equals(expected, actual), "Expected " + expected + " but received " + actual);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private static void expect(Class<? extends Throwable> type, CheckedRunnable action) {
        try {
            action.run();
        } catch (Throwable thrown) {
            if (type.isInstance(thrown)) return;
            throw new AssertionError("Expected " + type.getSimpleName() + " but received " + thrown, thrown);
        }
        throw new AssertionError("Expected " + type.getSimpleName() + " to be thrown");
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
