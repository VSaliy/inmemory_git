package io.github.inmemorygit;

import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/** A 20-byte SHA-1 object name, represented as 40 lowercase hexadecimal digits. */
public record ObjectId(String hex) {
    public ObjectId {
        Objects.requireNonNull(hex, "hex");
        if (hex.length() != 40 || !hex.chars().allMatch(ObjectId::isHexDigit)) {
            throw new IllegalArgumentException("Object ID must contain exactly 40 hexadecimal digits");
        }
        hex = hex.toLowerCase(Locale.ROOT);
    }

    /** Constructs an object name from exactly 20 raw bytes. */
    public static ObjectId fromBytes(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length != 20) {
            throw new IllegalArgumentException("Object ID must contain exactly 20 bytes");
        }
        return new ObjectId(HexFormat.of().formatHex(bytes));
    }

    /** Returns a new array containing the 20 raw ID bytes. */
    public byte[] bytes() {
        return HexFormat.of().parseHex(hex);
    }

    @Override
    public String toString() {
        return hex;
    }

    private static boolean isHexDigit(int character) {
        return character >= '0' && character <= '9'
                || character >= 'a' && character <= 'f'
                || character >= 'A' && character <= 'F';
    }
}
