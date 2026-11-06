package io.github.inmemorygit;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** A deterministic, disk-free repository workflow. */
public final class Demo {
    private Demo() { }

    public static void main(String[] args) {
        InMemoryGit git = new InMemoryGit();
        var identity = new InMemoryGit.Signature("Example Author", "author@example.com",
                "Thu Apr 7 15:13:13 2005");

        git.putText("hello.txt", "Hello from the first snapshot!\n");
        git.putText("src/note.txt", "Full paths live in a flat tree.\n");
        git.updateCache("hello.txt", "src/note.txt");
        ObjectId firstTree = git.writeTree();
        ObjectId firstCommit = git.commitTree(firstTree, List.of(), identity, identity,
                "Initial snapshot\n");

        git.putText("hello.txt", "Hello from the second snapshot!\n");
        System.out.println("Unstaged changes: " + git.showDiff().size());
        System.out.println("Index still names first tree: " + firstTree.equals(git.writeTree()));
        git.updateCache("hello.txt");
        ObjectId secondTree = git.writeTree();
        ObjectId secondCommit = git.commitTree(secondTree, List.of(firstCommit), identity, identity,
                "Update the greeting\n");

        System.out.println("First commit:  " + firstCommit.hex());
        System.out.println("Second commit: " + secondCommit.hex());
        System.out.println("Parent:        " + git.readCommit(secondCommit).parents().get(0).hex());
        System.out.println("Second snapshot:");
        for (var entry : git.readTree(secondTree)) {
            System.out.println("  " + entry.mode().octalMode() + " " + entry.path() + " " + entry.objectId().hex());
        }
        System.out.println("Stored objects: " + git.objectCount() + " (the unchanged note blob is shared)");

        git.restoreTree(firstTree);
        System.out.print("Restored greeting: " + new String(git.workingFile("hello.txt").orElseThrow().content(),
                StandardCharsets.UTF_8));
        System.out.println("All repository state lived in Java memory.");
    }
}
