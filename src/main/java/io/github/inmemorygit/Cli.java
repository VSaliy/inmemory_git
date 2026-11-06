package io.github.inmemorygit;

import java.util.List;

/**
 * Small plumbing-oriented entrypoint for scripted runs.
 */
public final class Cli {
    private Cli() { }

    public static void main(String[] args) {
        String command = args.length == 0 ? "demo" : args[0];
        switch (command) {
            case "demo" -> Demo.main(new String[0]);
            case "fsck" -> runFsckFixture();
            default -> throw new IllegalArgumentException("Unsupported command: " + command);
        }
    }

    private static void runFsckFixture() {
        InMemoryGit git = new InMemoryGit();
        var who = new InMemoryGit.Signature("Fsck Fixture", "fsck@example.test",
                "Thu Apr 7 15:13:13 2005");
        git.putText("hello.txt", "hello\n");
        git.updateCache("hello.txt");
        ObjectId tree = git.writeTree();
        git.commitTree(tree, List.of(), who, who, "fixture\n");
        List<InMemoryGit.FsckIssue> issues = git.fsckRepository();
        if (!issues.isEmpty()) {
            StringBuilder message = new StringBuilder("fsck failed:");
            for (InMemoryGit.FsckIssue issue : issues) {
                message.append(System.lineSeparator()).append(issue.id()).append(" ").append(issue.message());
            }
            throw new IllegalStateException(message.toString());
        }
        System.out.println("fsck: ok (" + git.objectCount() + " objects)");
    }
}
