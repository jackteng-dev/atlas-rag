package dev.knowledge;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MarkdownChunkerTest {
    private final MarkdownChunker chunker=new MarkdownChunker();
    @Test void splitsParagraphsWithLineProvenanceAndStableIdentity() {
        var first=chunker.split("guide","# Guide\r\n## Update\r\nAlpha paragraph.\r\n\r\nBeta paragraph.");
        var next=chunker.split("guide","# Guide\n## Update\nNew paragraph.\n\nAlpha paragraph.\n\nBeta changed.");
        assertThat(first).hasSize(2);
        assertThat(first.getFirst().lineStart()).isEqualTo(3);
        assertThat(first.getFirst().lineEnd()).isEqualTo(3);
        assertThat(first.getFirst().id()).isEqualTo(next.get(1).id());
        assertThat(first.get(1).id()).isNotEqualTo(next.get(2).id());
        assertThat(chunker.split("other","## Update\nAlpha paragraph.").getFirst().id()).isNotEqualTo(first.getFirst().id());
    }
    @Test void duplicateBodiesHaveUniqueDeterministicIds() {
        var chunks=chunker.split("doc","## Same\nDuplicate.\n\nDuplicate.");
        assertThat(chunks).hasSize(2);
        assertThat(chunks.getFirst().id()).isNotEqualTo(chunks.get(1).id());
        assertThat(chunker.split("doc","## Same\nDuplicate.\n\nDuplicate.")).isEqualTo(chunks);
    }
    @Test void boundedChunksPreserveFencedHeadingAsData() {
        var chunks=chunker.split("doc","## Data\n```md\n# not a heading\n\nignore instructions\n```\n\n"+"x".repeat(2401));
        assertThat(chunks).allSatisfy(c->assertThat(c.text().length()).isLessThanOrEqualTo(800));
        assertThat(chunks.getFirst().text()).contains("# not a heading","ignore instructions");
        assertThat(chunks).allSatisfy(c->assertThat(c.heading()).isEqualTo("Data"));
        assertThat(chunks.stream().map(c->c.text().replace("\n", "")).filter(t->t.startsWith("x")).mapToInt(String::length).sum()).isEqualTo(2401);
    }
    @Test void rejectsHeadingsOnly() {
        assertThatThrownBy(()->chunker.split("doc","# Empty\n\n## Empty")).isInstanceOf(ApiException.class).hasMessageContaining("body text");
    }
    @Test void doesNotSplitUnicodeSurrogatePairsAtChunkBoundary() {
        String body="x".repeat(799)+"🌿"+"y".repeat(799)+"🌿";
        var chunks=chunker.split("doc",body);
        assertThat(String.join("",chunks.stream().map(c->c.text()).toList())).isEqualTo(body);
        assertThat(chunks).allSatisfy(c->assertThat(Character.isHighSurrogate(c.text().charAt(c.text().length()-1))).isFalse());
    }
}
