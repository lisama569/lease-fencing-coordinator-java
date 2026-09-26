package com.github.lisama569.leasefencingcoordinatorjava.lease.store;

import com.github.lisama569.leasefencingcoordinatorjava.lease.Lease;
import com.github.lisama569.leasefencingcoordinatorjava.lease.LeaseException;
import com.github.lisama569.leasefencingcoordinatorjava.lease.LeaseStore;
import com.github.lisama569.leasefencingcoordinatorjava.lease.RejectReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * File-backed {@link LeaseStore}: one properties file per named lock.
 *
 * <p>Every save goes to a temporary file followed by an atomic move, and
 * records are never deleted on expiry. Consequently, after a process
 * restart the previously granted token and the absolute expiry timestamp
 * are still present, so token re-issue and double-holding are impossible.</p>
 */
@Component
public class FileLeaseStore implements LeaseStore {

    private static final Logger log = LoggerFactory.getLogger(FileLeaseStore.class);

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private static final String K_LOCK_NAME = "lockName";
    private static final String K_HOLDER = "holderId";
    private static final String K_TOKEN = "fencingToken";
    private static final String K_ACQUIRED = "acquiredAtMs";
    private static final String K_EXPIRES = "expiresAtMs";

    private final Path storeDir;

    public FileLeaseStore(
            @Value("${lease.store.dir:${java.io.tmpdir}/lease-fencing-store}") String storeDir) {
        this.storeDir = Path.of(storeDir);
        try {
            Files.createDirectories(this.storeDir);
        } catch (IOException e) {
            throw new LeaseException(RejectReason.PERSISTENCE_FAILURE,
                    "cannot create lease store directory: " + this.storeDir);
        }
        log.info("FileLeaseStore initialized at {}", this.storeDir.toAbsolutePath());
    }

    @Override
    public Optional<Lease> load(String lockName) {
        Path file = fileFor(lockName);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Properties props = new Properties();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            props.load(in);
        } catch (IOException | IllegalArgumentException e) {
            throw new LeaseException(RejectReason.PERSISTENCE_FAILURE,
                    "cannot read lease record for lock '" + lockName + "': " + e.getMessage());
        }
        try {
            Lease lease = new Lease(
                    props.getProperty(K_LOCK_NAME),
                    props.getProperty(K_HOLDER),
                    Long.parseLong(props.getProperty(K_TOKEN)),
                    Long.parseLong(props.getProperty(K_ACQUIRED)),
                    Long.parseLong(props.getProperty(K_EXPIRES)));
            return Optional.of(lease);
        } catch (RuntimeException e) {
            throw new LeaseException(RejectReason.PERSISTENCE_FAILURE,
                    "corrupt lease record for lock '" + lockName + "': " + e.getMessage());
        }
    }

    @Override
    public void save(Lease lease) {
        if (lease == null || lease.lockName() == null) {
            throw new LeaseException(RejectReason.INVALID_PARAMETER, "lease and lockName are required");
        }
        Properties props = new Properties();
        props.setProperty(K_LOCK_NAME, lease.lockName());
        props.setProperty(K_HOLDER, lease.holderId());
        props.setProperty(K_TOKEN, Long.toString(lease.fencingToken()));
        props.setProperty(K_ACQUIRED, Long.toString(lease.acquiredAtMs()));
        props.setProperty(K_EXPIRES, Long.toString(lease.expiresAtMs()));

        Path target = fileFor(lease.lockName());
        Path tmp;
        try {
            tmp = Files.createTempFile(storeDir, "lease-", ".tmp");
        } catch (IOException e) {
            throw new LeaseException(RejectReason.PERSISTENCE_FAILURE,
                    "cannot create temp file in " + storeDir + ": " + e.getMessage());
        }
        try (OutputStream out = Files.newOutputStream(tmp)) {
            props.store(out, "lease record for " + lease.lockName());
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new LeaseException(RejectReason.PERSISTENCE_FAILURE,
                    "cannot write lease record for lock '" + lease.lockName() + "': " + e.getMessage());
        }
        try {
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new LeaseException(RejectReason.PERSISTENCE_FAILURE,
                    "cannot persist lease record for lock '" + lease.lockName() + "': " + e.getMessage());
        }
        log.debug("persisted lease lock={} holder={} token={} expiresAt={}",
                lease.lockName(), lease.holderId(), lease.fencingToken(), lease.expiresAtMs());
    }

    private Path fileFor(String lockName) {
        if (lockName == null || !VALID_NAME.matcher(lockName).matches()) {
            throw new LeaseException(RejectReason.INVALID_PARAMETER,
                    "lockName must match " + VALID_NAME.pattern() + " but was: " + lockName);
        }
        return storeDir.resolve("lock-" + lockName + ".properties");
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort cleanup of the temp file
        }
    }
}
