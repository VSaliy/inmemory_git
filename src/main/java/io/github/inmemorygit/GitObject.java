package io.github.inmemorygit;

import java.util.Arrays;
import java.util.Objects;

/** An immutable, decoded object: its type and binary payload, without the header. */
public final class GitObject {
    private final ObjectType type;
    private final byte[] content;

    public GitObject(ObjectType type, byte[] content) {
        this.type = Objects.requireNonNull(type, "type");
        this.content = Objects.requireNonNull(content, "content").clone();
    }

    public ObjectType type() {
        return type;
    }

    /** Returns a copy; callers cannot change the stored object. */
    public byte[] content() {
        return content.clone();
    }

    public int size() {
        return content.length;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof GitObject object
                && type == object.type && Arrays.equals(content, object.content);
    }

    @Override
    public int hashCode() {
        return 31 * type.hashCode() + Arrays.hashCode(content);
    }

    @Override
    public String toString() {
        return type.tag() + " " + content.length + " bytes";
    }
}
