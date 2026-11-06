package io.github.inmemorygit;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * A content-addressed object database held entirely in memory.
 *
 * <p>Following the April 7, 2005 implementation, an ID is the SHA-1 of the
 * zlib-compressed {@code type + " " + byteLength + NUL + payload}. This differs
 * from modern Git, which hashes the uncompressed representation. Compression
 * uses zlib's best-compression level. Instances are not thread-safe.
 */
public final class InMemoryObjectStore {
    private final Map<ObjectId, byte[]> objects = new HashMap<>();

    /** Stores an immutable object and returns its content-derived name. */
    public ObjectId put(ObjectType type, byte[] content) {
        Objects.requireNonNull(type, "type");
        byte[] payload = Objects.requireNonNull(content, "content").clone();
        byte[] header = (type.tag() + " " + payload.length + '\0')
                .getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        encoded.writeBytes(header);
        encoded.writeBytes(payload);
        byte[] compressed = compress(encoded.toByteArray());
        ObjectId id = hash(compressed);
        byte[] previous = objects.putIfAbsent(id, compressed);
        if (previous != null && !Arrays.equals(previous, compressed)) {
            throw new IllegalStateException("SHA-1 collision for object " + id);
        }
        return id;
    }

    /**
     * Reads an object, checking its compressed-byte identity and decoded header.
     *
     * @throws IllegalArgumentException if the ID is absent
     * @throws IllegalStateException if stored bytes fail integrity checks
     */
    public GitObject get(ObjectId id) {
        byte[] compressed = requireObject(id);
        DecodedObject decoded = decodeStoredObject(id, compressed);
        return new GitObject(decoded.type(), decoded.payload());
    }

    /** Returns a copy of the exact compressed bytes addressed by this ID. */
    public byte[] compressedBytes(ObjectId id) {
        return requireObject(id).clone();
    }

    public boolean contains(ObjectId id) {
        return objects.containsKey(Objects.requireNonNull(id, "id"));
    }

    /** Checks whether the stored compressed bytes still match the addressed SHA-1 name. */
    public boolean checkObjectSignature(ObjectId id) {
        return hash(requireObject(id)).equals(id);
    }

    public int objectCount() {
        return objects.size();
    }

    private static DecodedObject decodeStoredObject(ObjectId id, byte[] compressed) {
        if (!hash(compressed).equals(id)) {
            throw new IllegalStateException("Object identity mismatch: " + id);
        }
        byte[] encoded = inflate(compressed);
        int nul = -1;
        for (int i = 0; i < encoded.length; i++) {
            if (encoded[i] == 0) {
                nul = i;
                break;
            }
        }
        if (nul < 0) {
            throw new IllegalStateException("Missing object header terminator");
        }
        String header = new String(encoded, 0, nul, StandardCharsets.US_ASCII);
        int space = header.indexOf(' ');
        if (space <= 0 || space == header.length() - 1) {
            throw new IllegalStateException("Invalid object header");
        }
        ObjectType type = ObjectType.fromTag(header.substring(0, space));
        String sizeText = header.substring(space + 1);
        if (!sizeText.chars().allMatch(character -> character >= '0' && character <= '9')
                || sizeText.length() > 1 && sizeText.charAt(0) == '0') {
            throw new IllegalStateException("Invalid object length");
        }
        int size;
        try {
            size = Integer.parseInt(sizeText);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Invalid object length", exception);
        }
        if (size != encoded.length - nul - 1) {
            throw new IllegalStateException("Object length does not match header");
        }
        return new DecodedObject(type, Arrays.copyOfRange(encoded, nul + 1, encoded.length));
    }

    private byte[] requireObject(ObjectId id) {
        Objects.requireNonNull(id, "id");
        byte[] compressed = objects.get(id);
        if (compressed == null) {
            throw new IllegalArgumentException("Unknown object: " + id);
        }
        return compressed;
    }

    private static ObjectId hash(byte[] bytes) {
        try {
            return ObjectId.fromBytes(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("Java requires the SHA-1 algorithm", exception);
        }
    }

    private static byte[] compress(byte[] encoded) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(encoded);
            deflater.finish();
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                if (count == 0) {
                    throw new IllegalStateException("Object compression made no progress");
                }
                result.write(buffer, 0, count);
            }
            return result.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflate(byte[] compressed) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count > 0) {
                    result.write(buffer, 0, count);
                } else if (inflater.needsDictionary() || inflater.needsInput()) {
                    throw new IllegalStateException("Incomplete object compression stream");
                } else if (!inflater.finished()) {
                    throw new IllegalStateException("Object inflation made no progress");
                }

                private record DecodedObject(ObjectType type, byte[] payload) { }
            }
            if (inflater.getRemaining() != 0) {
                throw new IllegalStateException("Trailing bytes after object compression stream");
            }
            return result.toByteArray();
        } catch (DataFormatException exception) {
            throw new IllegalStateException("Invalid object compression stream", exception);
        } finally {
            inflater.end();
        }
    }
}
