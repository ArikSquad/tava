package eu.mikart.tava.transfer;

import eu.mikart.tava.TavaException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Stores transfer checkpoints on a filesystem supporting exclusive locks and atomic replacement.
 *
 * @since 1.3.0
 */
public final class FileTransferCheckpointStore implements TransferCheckpointStore {
    private static final int VERSION = 1;
    private final Path directory;

    /**
     * Creates a checkpoint store rooted at the given directory.
     *
     * @param directory directory for lock and checkpoint files
     * @throws NullPointerException if {@code directory} is {@code null}
     * @since 1.3.0
     */
    public FileTransferCheckpointStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath();
    }

    /**
     * {@inheritDoc}
     *
     * @since 1.3.0
     */
    @Override
    public Lease acquire(String transferId) {
        if (transferId == null || transferId.isBlank()) throw new IllegalArgumentException("transferId is required");
        try {
            Files.createDirectories(directory);
            String name = hash(transferId);
            FileChannel channel = FileChannel.open(
                    directory.resolve(name + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException held) {
                lock = null;
            } catch (IOException failure) {
                channel.close();
                throw failure;
            }
            if (lock == null) {
                channel.close();
                throw new IllegalStateException("Transfer is already running: " + transferId);
            }
            return new FileLease(transferId, directory.resolve(name + ".checkpoint"), channel, lock);
        } catch (IOException failure) {
            throw new TavaException.Data("Acquire transfer checkpoint lease", failure);
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private final class FileLease implements Lease {
        private final String transferId;
        private final Path checkpoint;
        private final FileChannel channel;
        private final FileLock lock;
        private boolean closed;

        FileLease(String transferId, Path checkpoint, FileChannel channel, FileLock lock) {
            this.transferId = transferId;
            this.checkpoint = checkpoint;
            this.channel = channel;
            this.lock = lock;
        }

        private void check() {
            if (closed) throw new IllegalStateException("Checkpoint lease is closed");
        }

        @Override
        public Optional<TransferCheckpoint> load() {
            check();
            if (!Files.exists(checkpoint)) return Optional.empty();
            try (DataInputStream input =
                    new DataInputStream(new BufferedInputStream(Files.newInputStream(checkpoint)))) {
                if (input.readInt() != VERSION) throw new IOException("Unsupported checkpoint version");
                String id = input.readUTF();
                if (!transferId.equals(id)) throw new IOException("Checkpoint transfer ID mismatch");
                String source = input.readUTF();
                String target = input.readUTF();
                String fingerprint = input.readUTF();
                int batchSize = input.readInt();
                int index = input.readInt();
                String cursor = null;
                if (input.readBoolean()) {
                    int length = input.readInt();
                    if (length < 0 || length > 65536) throw new IOException("Invalid checkpoint cursor length");
                    byte[] bytes = new byte[length];
                    input.readFully(bytes);
                    cursor = new String(bytes, StandardCharsets.UTF_8);
                }
                boolean complete = input.readBoolean();
                int size = input.readInt();
                if (size < 0 || size > 100000) throw new IOException("Invalid checkpoint count size");
                Map<String, Long> counts = new LinkedHashMap<>();
                for (int i = 0; i < size; i++)
                    if (counts.put(input.readUTF(), input.readLong()) != null)
                        throw new IOException("Duplicate checkpoint entity");
                if (input.read() != -1) throw new IOException("Trailing checkpoint data");
                return Optional.of(new TransferCheckpoint(
                        id, source, target, fingerprint, batchSize, index, cursor, counts, complete));
            } catch (IOException | IllegalArgumentException failure) {
                throw new TavaException.Data("Read transfer checkpoint", failure);
            }
        }

        @Override
        public void save(TransferCheckpoint value) {
            check();
            if (!transferId.equals(value.transferId()))
                throw new IllegalArgumentException("Checkpoint transfer ID mismatch");
            Path temporary = null;
            try {
                temporary = Files.createTempFile(directory, ".tava-checkpoint-", ".tmp");
                try (FileOutputStream file = new FileOutputStream(temporary.toFile());
                        DataOutputStream output = new DataOutputStream(new BufferedOutputStream(file))) {
                    output.writeInt(VERSION);
                    output.writeUTF(value.transferId());
                    output.writeUTF(value.sourceAdapter());
                    output.writeUTF(value.targetAdapter());
                    output.writeUTF(value.schemaFingerprint());
                    output.writeInt(value.batchSize());
                    output.writeInt(value.entityIndex());
                    output.writeBoolean(value.cursor() != null);
                    if (value.cursor() != null) {
                        byte[] cursor = value.cursor().getBytes(StandardCharsets.UTF_8);
                        if (cursor.length > 65536) throw new IOException("Checkpoint cursor is too large");
                        output.writeInt(cursor.length);
                        output.write(cursor);
                    }
                    output.writeBoolean(value.complete());
                    output.writeInt(value.counts().size());
                    for (var entry : value.counts().entrySet()) {
                        output.writeUTF(entry.getKey());
                        output.writeLong(entry.getValue());
                    }
                    output.flush();
                    file.getFD().sync();
                }
                Files.move(temporary, checkpoint, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException failure) {
                throw new TavaException.Data("Save transfer checkpoint", failure);
            } finally {
                if (temporary != null)
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException ignored) {
                    }
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            try {
                lock.release();
            } catch (IOException failure) {
                try {
                    channel.close();
                } catch (IOException close) {
                    failure.addSuppressed(close);
                }
                throw new TavaException.Data("Release transfer checkpoint lease", failure);
            }
            try {
                channel.close();
            } catch (IOException failure) {
                throw new TavaException.Data("Close transfer checkpoint lease", failure);
            }
        }
    }
}
