package com.example.donutflipscanner.api;

import com.example.donutflipscanner.ModConstants;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Separate credential file excluded from config exports and restricted to owner access where supported. */
public final class FileApiCredentialsStore implements ApiCredentialsProvider {
    public static final Path DEFAULT_RELATIVE_PATH = Path.of(
            "config", ModConstants.CONFIG_DIRECTORY_NAME, "api-key.txt"
    );
    private static final int MAXIMUM_KEY_CHARACTERS = 512;
    private static final int MAXIMUM_KEY_BYTES = 2_048;
    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE
    );

    private final Path path;

    public FileApiCredentialsStore(Path path) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    @Override
    public synchronized Optional<char[]> copyApiKey() {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        byte[] bytes = null;
        char[] backing = null;
        try {
            if (Files.isSymbolicLink(path) || Files.size(path) > MAXIMUM_KEY_BYTES) {
                throw new IOException("credential file is unsafe or too large");
            }
            bytes = Files.readAllBytes(path);
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            backing = new char[decoded.remaining()];
            decoded.get(backing);
            int first = 0;
            int last = backing.length;
            while (first < last && Character.isWhitespace(backing[first])) {
                first++;
            }
            while (last > first && Character.isWhitespace(backing[last - 1])) {
                last--;
            }
            char[] trimmed = Arrays.copyOfRange(backing, first, last);
            try {
                validate(trimmed);
                return Optional.of(Arrays.copyOf(trimmed, trimmed.length));
            } finally {
                Arrays.fill(trimmed, '\0');
            }
        } catch (IOException | IllegalArgumentException error) {
            throw new ApiAuthenticationException("The local API credential could not be read safely");
        } finally {
            if (bytes != null) {
                Arrays.fill(bytes, (byte) 0);
            }
            if (backing != null) {
                Arrays.fill(backing, '\0');
            }
        }
    }

    public synchronized void setApiKey(char[] apiKey) {
        Objects.requireNonNull(apiKey, "apiKey");
        char[] copy = Arrays.copyOf(apiKey, apiKey.length);
        byte[] encoded = null;
        try {
            validate(copy);
            ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(copy));
            encoded = new byte[buffer.remaining()];
            buffer.get(encoded);
            Path parent = requireParent();
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, "credential-", ".tmp");
            boolean moved = false;
            try {
                Files.write(temporary, encoded);
                restrictPermissions(temporary);
                try {
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                }
                restrictPermissions(path);
                moved = true;
            } finally {
                if (!moved) {
                    Files.deleteIfExists(temporary);
                }
            }
        } catch (IOException error) {
            throw new ApiAuthenticationException("The local API credential could not be saved safely");
        } finally {
            Arrays.fill(copy, '\0');
            if (encoded != null) {
                Arrays.fill(encoded, (byte) 0);
            }
        }
    }

    public synchronized void clear() {
        try {
            if (Files.isSymbolicLink(path)) {
                throw new IOException("refusing to follow a credential symbolic link");
            }
            Files.deleteIfExists(path);
        } catch (IOException error) {
            throw new ApiAuthenticationException("The local API credential could not be removed safely");
        }
    }

    private static void validate(char[] value) {
        if (value.length < 1 || value.length > MAXIMUM_KEY_CHARACTERS) {
            throw new IllegalArgumentException("credential length is invalid");
        }
        boolean nonWhitespace = false;
        for (char character : value) {
            if (Character.isISOControl(character)) {
                throw new IllegalArgumentException("credential contains control characters");
            }
            nonWhitespace |= !Character.isWhitespace(character);
        }
        if (!nonWhitespace) {
            throw new IllegalArgumentException("credential is blank");
        }
    }

    private static void restrictPermissions(Path target) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(target, PosixFileAttributeView.class);
        if (posix != null) {
            Files.setPosixFilePermissions(target, OWNER_ONLY);
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(target, AclFileAttributeView.class);
        if (acl != null) {
            AclEntry ownerOnly = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(Files.getOwner(target))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
            acl.setAcl(java.util.List.of(ownerOnly));
        }
    }

    private Path requireParent() {
        Path parent = path.getParent();
        if (parent == null) {
            throw new ApiAuthenticationException("The local API credential path is invalid");
        }
        return parent;
    }
}
