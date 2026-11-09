package dev.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static dev.knowledge.Models.*;

class DocumentStoreTest {
    @TempDir Path directory;
    private DocumentStore store;
    @BeforeEach void open() throws Exception { store=new DocumentStore(directory.resolve("store.json"),new ObjectMapper(),new MarkdownChunker(),false); }
    @AfterEach void close() throws Exception { store.close(); }
    @Test void persistsUpdateAndTombstoneAcrossRestart() throws Exception {
        var original=store.create(new ImportRequest("guide","Guide","## Update\nlegacytoken content"));
        var updated=store.update("guide",new UpdateRequest("Guide","## Update\nreplacementtoken content",1));
        assertThat(updated.version()).isEqualTo(2);
        var retriever=new KeywordRetriever();
        assertThat(retriever.retrieve(store.snapshot(),"legacytoken",null,5).hits()).isEmpty();
        assertThat(retriever.retrieve(store.snapshot(),"replacementtoken",null,5).hits()).hasSize(1);
        assertThatThrownBy(()->store.update("guide",new UpdateRequest("Guide","stale overwrite",1))).isInstanceOf(ApiException.class);
        store.close(); store=new DocumentStore(directory.resolve("store.json"),new ObjectMapper(),new MarkdownChunker(),true);
        assertThat(store.get("guide").version()).isEqualTo(2);
        assertThat(store.snapshot().documents()).hasSize(1); // existing store is never reseeded
        store.delete("guide",2);
        assertThat(retriever.retrieve(store.snapshot(),"replacementtoken",null,5).hits()).isEmpty();
        store.close(); store=new DocumentStore(directory.resolve("store.json"),new ObjectMapper(),new MarkdownChunker(),true);
        assertThat(store.snapshot().documents()).isEmpty();
        var recreated=store.create(new ImportRequest("guide","Guide",original.markdown()));
        assertThat(recreated.version()).isEqualTo(3);
        assertThat(recreated.chunks().getFirst().id()).isEqualTo(original.chunks().getFirst().id());
    }
    @Test void noOpAndFailedPersistenceDoNotAdvanceIndex() throws Exception {
        var doc=store.create(new ImportRequest("guide","Guide","persistent body"));
        long revision=store.snapshot().revision();
        assertThat(store.update("guide",new UpdateRequest("Guide","persistent body",1))).isEqualTo(doc);
        assertThat(store.snapshot().revision()).isEqualTo(revision);
        Files.delete(directory.resolve("store.json")); Files.createDirectory(directory.resolve("store.json"));
        Files.writeString(directory.resolve("store.json/obstacle"),"force atomic replacement failure");
        assertThatThrownBy(()->store.update("guide",new UpdateRequest("Guide","new body",1))).isInstanceOf(ApiException.class);
        assertThat(store.get("guide")).isEqualTo(doc);
        assertThat(store.snapshot().revision()).isEqualTo(revision);
    }
    @Test void optimisticConcurrentWritersOnlyAllowOneWinner() throws Exception {
        store.create(new ImportRequest("guide","Guide","initial body"));
        var latch=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> task=()->{ latch.await(); try { store.update("guide",new UpdateRequest("Guide","body "+Thread.currentThread().threadId(),1)); return true; } catch(ApiException e) { assertThat(e.code).isEqualTo("VERSION_CONFLICT"); return false; } };
            var first=executor.submit(task); var second=executor.submit(task); latch.countDown();
            assertThat(first.get(3,TimeUnit.SECONDS)^second.get(3,TimeUnit.SECONDS)).isTrue();
            assertThat(store.get("guide").version()).isEqualTo(2);
        }
    }
    @Test void secondWriterAndInvalidBodyAreRejected() {
        assertThatThrownBy(()->new DocumentStore(directory.resolve("store.json"),new ObjectMapper(),new MarkdownChunker(),false)).isInstanceOf(Exception.class);
        assertThatThrownBy(()->store.create(new ImportRequest("empty","Empty","# heading"))).isInstanceOf(ApiException.class);
        assertThat(store.snapshot().documents()).isEmpty();
    }
    @Test void corruptStoreIsPreservedRatherThanSilentlyReseeded() throws Exception {
        store.close();
        Files.writeString(directory.resolve("store.json"),"{broken-json}");
        assertThatThrownBy(()->new DocumentStore(directory.resolve("store.json"),new ObjectMapper(),new MarkdownChunker(),true)).isInstanceOf(Exception.class);
        assertThat(Files.readString(directory.resolve("store.json"))).isEqualTo("{broken-json}");
    }
}
