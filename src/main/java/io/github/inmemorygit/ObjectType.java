package io.github.inmemorygit;

/** Object types present in Git's original commit. */
public enum ObjectType {
    BLOB("blob"),
    TREE("tree"),
    COMMIT("commit");

    private final String tag;

    ObjectType(String tag) {
        this.tag = tag;
    }

    /** The ASCII type tag stored before an object's byte length. */
    public String tag() {
        return tag;
    }

    static ObjectType fromTag(String tag) {
        for (ObjectType type : values()) {
            if (type.tag.equals(tag)) {
                return type;
            }
        }
        throw new IllegalStateException("Invalid object type: " + tag);
    }
}
