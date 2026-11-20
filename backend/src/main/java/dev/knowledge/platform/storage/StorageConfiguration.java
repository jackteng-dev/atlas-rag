package dev.knowledge.platform.storage;

import dev.knowledge.ApiException;
import io.minio.*;
import java.io.*;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration @Profile("platform")
public class StorageConfiguration {
    @Bean ObjectStore objectStore(@Value("${atlas.storage.type:s3}") String type,
                                  @Value("${atlas.storage.s3.endpoint}") String endpoint, @Value("${atlas.storage.s3.bucket}") String bucket,
                                  @Value("${atlas.storage.s3.access-key:}") String accessKey, @Value("${atlas.storage.s3.secret-key:}") String secretKey,
                                  @Value("${atlas.storage.filesystem.root}") String root) throws IOException {
        return switch (type) {
            case "s3" -> {
                if (accessKey.isBlank() || secretKey.isBlank()) throw new IllegalStateException("ATLAS_S3_ACCESS_KEY and ATLAS_S3_SECRET_KEY are required for S3 storage");
                yield new S3ObjectStore(MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build(), bucket);
            }
            case "filesystem" -> new FileObjectStore(Path.of(root));
            default -> throw new IllegalStateException("atlas.storage.type must be s3 or filesystem");
        };
    }

    static final class S3ObjectStore implements ObjectStore {
        private final MinioClient client; private final String bucket; private volatile boolean ready;
        S3ObjectStore(MinioClient client, String bucket) { this.client = client; this.bucket = bucket; }
        private void ensureBucket() throws Exception {
            if (ready) return;
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            ready = true;
        }
        @Override public void put(String key, byte[] bytes, String contentType) {
            try { ensureBucket(); client.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(new ByteArrayInputStream(bytes), bytes.length, -1).contentType(contentType).build()); }
            catch (Exception e) { throw new ApiException(503, "STORAGE_UNAVAILABLE", "Object storage rejected the upload."); }
        }
        @Override public InputStream get(String key) {
            try { return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build()); }
            catch (Exception e) { throw new ApiException(503, "STORAGE_UNAVAILABLE", "The original file cannot be read from object storage."); }
        }
        @Override public void delete(String key) {
            try { client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build()); }
            catch (Exception e) { throw new ApiException(503, "STORAGE_UNAVAILABLE", "Object storage delete failed."); }
        }
        @Override public String health() {
            try { ensureBucket(); return "UP"; } catch (Exception e) { return "DOWN"; }
        }
    }

    static final class FileObjectStore implements ObjectStore {
        private final Path root;
        FileObjectStore(Path root) throws IOException { this.root = root.toAbsolutePath().normalize(); Files.createDirectories(this.root); }
        private Path resolve(String key) {
            if (!key.matches("org/[0-9a-f-]{36}/doc/[0-9a-f-]{36}/v[0-9]+/[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid object key");
            var path = root.resolve(key).normalize();
            if (!path.startsWith(root)) throw new IllegalArgumentException("Object key escapes the storage root");
            return path;
        }
        @Override public void put(String key, byte[] bytes, String contentType) {
            try {
                var path = resolve(key); Files.createDirectories(path.getParent());
                var tmp = Files.createTempFile(path.getParent(), "upload", ".part");
                Files.write(tmp, bytes);
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) { throw new ApiException(503, "STORAGE_UNAVAILABLE", "File storage rejected the upload."); }
        }
        @Override public InputStream get(String key) {
            try { return Files.newInputStream(resolve(key)); } catch (IOException e) { throw new ApiException(404, "FILE_NOT_FOUND", "The original file is not available."); }
        }
        @Override public void delete(String key) { try { Files.deleteIfExists(resolve(key)); } catch (IOException ignored) { } }
        @Override public String health() { return Files.isWritable(root) ? "UP" : "DOWN"; }
    }
}
