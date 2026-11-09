package dev.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static dev.knowledge.Models.*;

@Service
public class DocumentStore implements AutoCloseable {
    private final Path file;
    private final ObjectMapper mapper;
    private final MarkdownChunker chunker;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private volatile StoreState state;
    @Autowired
    public DocumentStore(@Value("${knowledge.store}") String file, ObjectMapper mapper, MarkdownChunker chunker) throws IOException {
        this(Path.of(file), mapper, chunker, true);
    }
    public DocumentStore(Path file, ObjectMapper mapper, MarkdownChunker chunker, boolean seed) throws IOException {
        this.file=file.toAbsolutePath(); this.mapper=mapper; this.chunker=chunker;
        Files.createDirectories(this.file.getParent());
        lockChannel=FileChannel.open(this.file.resolveSibling(this.file.getFileName()+".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try { lock=lockChannel.tryLock(); }
        catch (RuntimeException | IOException e) { lockChannel.close(); throw e; }
        if (lock==null) { lockChannel.close(); throw new IOException("Store already in use by another process"); }
        try {
            if (Files.exists(this.file)) {
                StoreState loaded=mapper.readValue(this.file.toFile(),StoreState.class);
                if (loaded.schemaVersion()!=1) throw new IOException("Unsupported store schema; preserve file and migrate explicitly");
                for (Document doc : loaded.documents()) {
                    if (!doc.contentHash().equals(MarkdownChunker.hash(doc.markdown()))
                            || !doc.chunks().equals(chunker.split(doc.id(), doc.markdown())))
                        throw new IOException("Stored chunks differ from configured chunking or content. Use a separate AKA_STORE; existing file is preserved.");
                }
                state=new StoreState(1,loaded.revision(),Map.copyOf(loaded.versions()),List.copyOf(loaded.documents()));
            } else {
                state=new StoreState(1,0,Map.of(),List.of());
                if (seed) {
                    for (String id: List.of("architecture","data-policy","operations","security-fixture")) {
                        String content=new String(new ClassPathResource("corpus/"+id+".md").getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        String title=content.lines().findFirst().orElse(id).replaceFirst("^#\\s*", "");
                        create(new ImportRequest(id,title,content));
                    }
                } else persist(state);
            }
        } catch (Exception e) { close(); throw e; }
    }
    public StoreState snapshot() { return state; }
    public Document get(String id) { return get(state,id); }
    public static Document get(StoreState snapshot, String id) {
        return snapshot.documents().stream().filter(d->d.id().equals(id)).findFirst()
                .orElseThrow(()->new ApiException(404,"DOCUMENT_NOT_FOUND","Document does not exist."));
    }
    public synchronized Document create(ImportRequest request) {
        if (state.documents().stream().anyMatch(d->d.id().equals(request.id())))
            throw new ApiException(409,"ID_EXISTS","Document ID already exists. Use update with expectedVersion.");
        return save(request.id(),request.title(),request.markdown(),request.metadata()==null?Metadata.publicDocument():request.metadata());
    }
    public synchronized Document update(String id, UpdateRequest request) {
        Document previous=get(id);
        checkVersion(previous,request.expectedVersion());
        Metadata metadata=request.metadata()==null?previous.metadata():request.metadata();
        if (previous.markdown().equals(request.markdown()) && previous.title().equals(request.title()) && previous.metadata().equals(metadata)) return previous;
        return save(id,request.title(),request.markdown(),metadata);
    }
    public synchronized void delete(String id, int expectedVersion) {
        checkVersion(get(id),expectedVersion);
        var documents=state.documents().stream().filter(d->!d.id().equals(id)).toList();
        commit(new StoreState(1,state.revision()+1,state.versions(),documents));
    }
    private void checkVersion(Document doc, int expected) {
        if (expected!=doc.version()) throw new ApiException(409,"VERSION_CONFLICT","Document changed. Reload before editing or deleting.");
    }
    private Document save(String id, String title, String markdown, Metadata metadata) {
        int version=state.versions().getOrDefault(id,0)+1;
        Document doc=new Document(id,title.strip(),version,MarkdownChunker.hash(markdown),Instant.now().toString(),markdown,chunker.split(id,markdown),metadata);
        var documents=new ArrayList<>(state.documents().stream().filter(d->!d.id().equals(id)).toList());
        documents.add(doc); documents.sort(Comparator.comparing(Document::id));
        var versions=new HashMap<>(state.versions()); versions.put(id,version);
        commit(new StoreState(1,state.revision()+1,Map.copyOf(versions),List.copyOf(documents)));
        return doc;
    }
    private void commit(StoreState next) {
        try { persist(next); state=next; }
        catch (IOException e) { throw new ApiException(503,"STORAGE_FAILURE","Could not persist document; active index is unchanged."); }
    }
    private void persist(StoreState next) throws IOException {
        Path temporary=Files.createTempFile(file.getParent(),"store-",".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(),next);
            try (var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    @Override @PreDestroy public void close() throws IOException {
        if (lock!=null && lock.isValid()) lock.release();
        if (lockChannel!=null && lockChannel.isOpen()) lockChannel.close();
    }
}
