package dev.raggateway.rag;

import dev.raggateway.rag.ingest.ChunkStore;
import dev.raggateway.rag.ingest.IndexingWorker;
import dev.raggateway.rag.ingest.FakeEmbedder;
import dev.raggateway.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG beans in the full app under the test profile: the fake embedder (D-18) and no scheduled poller (D-47: only
 * {@code IngestionE2EIT} turns it on). Shares the cached context of the other {@link AbstractIntegrationTest}s.
 */
class RagConfigIT extends AbstractIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void testProfileUsesTheFakeEmbedderAndNoScheduledWorker() {
        assertThat(context.getBean(Embedder.class)).isInstanceOf(FakeEmbedder.class);
        assertThat(context.getBean(Chunker.class)).isNotNull();
        assertThat(context.getBean(ChunkStore.class)).isNotNull();
        // The worker bean exists (Dev A's stores are on main), so tests can call pollOnce(); it is not scheduled.
        assertThat(context.getBean(IndexingWorker.class)).isNotNull();
        assertThat(context.getBean(RagProperties.class).worker().enabled()).isFalse();
        assertThat(context.getBeansOfType(ScheduledTaskHolder.class).values())
                .allSatisfy(holder -> assertThat(holder.getScheduledTasks()).isEmpty());
    }
}
