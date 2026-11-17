package dev.raggateway.rag.query;

import com.zaxxer.hikari.HikariDataSource;
import dev.raggateway.rag.Embedder;
import dev.raggateway.rag.RetrievalMode;
import dev.raggateway.rag.RetrievalQuery;
import dev.raggateway.rag.RetrievedChunk;
import dev.raggateway.rag.Retriever;
import dev.raggateway.support.AbstractIntegrationTest;
import dev.raggateway.support.RagFixtures;
import dev.raggateway.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@link Retriever} bean in the full application (fake embedder under the test profile, the app's own pool of 3
 * connections): it is the hybrid retriever, and concurrent hybrid retrievals, each using up to two connections at
 * once, all complete with the same result as a serial one. No leg holds a connection while it waits for another, so
 * they queue on the pool instead of deadlocking.
 */
class HybridRetrieverIT extends AbstractIntegrationTest {

    static final String TENANT = "t-retriever-it";

    @Autowired
    Retriever retriever;

    @Autowired
    Embedder embedder;

    @Autowired
    DataSource dataSource;

    @Test
    void concurrentHybridRetrievalsCompleteOnTheTestPoolOfThree() throws Exception {
        assertThat(retriever).isInstanceOf(HybridRetriever.class);
        assertThat(((HikariDataSource) dataSource).getMaximumPoolSize()).isEqualTo(3);
        TestData.insertTenant(jdbc, TENANT, 1000, BigDecimal.TEN, Set.of());
        RagFixtures.document(jdbc, TENANT, "k8s", "concepts.jobs", "Jobs", Set.of("eng"), embedder,
                "A Job creates Pods and retries them until enough succeed.",
                "Set backoffLimit to cap the retries of a failing Job.");
        RagFixtures.document(jdbc, TENANT, "k8s", "concepts.deployment", "Deployments", Set.of("eng"), embedder,
                "Run kubectl apply --dry-run=client to preview a change.");
        var query = new RetrievalQuery(TENANT, "k8s", "how are Job retries capped", Set.of("eng"), 5,
                RetrievalMode.HYBRID);
        List<RetrievedChunk> serial = retriever.retrieve(query).chunks();
        assertThat(serial).isNotEmpty().anySatisfy(c -> {
            assertThat(c.keywordRank()).isPositive();
            assertThat(c.vectorRank()).isPositive();
        });

        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<RetrievedChunk>>> futures = new ArrayList<>();
            for (int i = 0; i < 12; i++) futures.add(callers.submit(() -> retriever.retrieve(query).chunks()));
            for (var f : futures) assertThat(f.get(20, TimeUnit.SECONDS)).isEqualTo(serial);
        }
    }
}
