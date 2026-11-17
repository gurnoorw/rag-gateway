package dev.raggateway.rag;

import dev.raggateway.config.GatewayProperties;
import dev.raggateway.config.GatewayProperties.ProviderProperties;
import dev.raggateway.core.TokenEstimator;
import dev.raggateway.provider.ollama.OllamaClientConfig;
import dev.raggateway.rag.ingest.ChunkStore;
import dev.raggateway.rag.ingest.DocumentStore;
import dev.raggateway.rag.ingest.FakeEmbedder;
import dev.raggateway.rag.ingest.HeadingParagraphSentenceChunker;
import dev.raggateway.rag.ingest.IndexingWorker;
import dev.raggateway.rag.ingest.IngestMetrics;
import dev.raggateway.rag.ingest.JdbcChunkStore;
import dev.raggateway.rag.ingest.OllamaEmbedder;
import dev.raggateway.rag.ingest.OutboxStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * RAG ingestion beans (t09, DECISIONS.md D-18, D-47).
 * <ul>
 *   <li>{@link Embedder}: {@code gateway.rag.embedder} = {@code ollama} (default; {@code /api/embed} at
 *       {@code gateway.providers.ollama.base-url}, its own HTTP/1.1 client) or {@code fake} (default under the
 *       test profile, D-18).</li>
 *   <li>{@link Chunker}: headings, paragraphs, sentences, sizes from {@code gateway.rag.chunk}.</li>
 *   <li>{@link ChunkStore} on JDBC; {@link IndexingWorker} on the outbox and document stores.</li>
 *   <li>The scheduled poller: one per instance, {@code fixedDelay} = {@code gateway.rag.worker.poll-delay}, only
 *       when {@code gateway.rag.worker.enabled} is true (off under the test profile: every cached test context
 *       would otherwise poll the shared outbox and take other tests' jobs). The worker bean exists either way, so
 *       tests drive it with {@link IndexingWorker#pollOnce()}.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    @Bean
    Embedder embedder(RagProperties rag, GatewayProperties gateway) {
        return switch (rag.embedder()) {
            case FAKE -> {
                log.info("embedder: fake (feature hashing; numbers produced with it are not model numbers)");
                yield new FakeEmbedder(rag.embedDimensions());
            }
            case OLLAMA -> {
                ProviderProperties ollama = gateway.providers().get("ollama");
                if (ollama == null || ollama.baseUrl() == null || ollama.baseUrl().isBlank()) {
                    throw new IllegalStateException("gateway.rag.embedder=ollama needs gateway.providers.ollama.base-url");
                }
                yield new OllamaEmbedder(OllamaClientConfig.client(ollama), ollama.baseUrl(), rag.embedModel(),
                        rag.embedDimensions(), rag.embedTimeout());
            }
        };
    }

    @Bean
    Chunker chunker(TokenEstimator estimator, RagProperties rag) {
        RagProperties.Chunking c = rag.chunk();
        return new HeadingParagraphSentenceChunker(estimator, c.targetTokens(), c.overlapTokens(), c.maxTokens());
    }

    @Bean
    ChunkStore chunkStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        return new JdbcChunkStore(jdbc, transactions);
    }

    /** Needs the write side's stores; without them the app fails at startup rather than never indexing. */
    @Bean
    IndexingWorker indexingWorker(OutboxStore outbox, DocumentStore documents, ChunkStore chunks, Chunker chunker,
                                  Embedder embedder, RagProperties rag, IngestMetrics metrics) {
        return new IndexingWorker(outbox, documents, chunks, chunker, embedder, rag.worker().claimBatch(),
                rag.worker().lease(), rag.embedBatchSize(), rag.worker().retryPolicy(), metrics);
    }

    /** The poller. A fixed delay (not a rate) so a slow batch never overlaps the next poll. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(prefix = "gateway.rag.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class WorkerScheduling implements SchedulingConfigurer {

        private final IndexingWorker worker;
        private final RagProperties rag;

        WorkerScheduling(IndexingWorker worker, RagProperties rag) {
            this.worker = worker;
            this.rag = rag;
        }

        @Override
        public void configureTasks(ScheduledTaskRegistrar registrar) {
            registrar.addFixedDelayTask(worker::scheduledPoll, rag.worker().pollDelay());
            log.info("indexing worker scheduled every {} after the previous poll", rag.worker().pollDelay());
        }
    }
}
