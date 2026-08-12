package com.devloom.ai;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LlmCallRepository extends JpaRepository<LlmCallEntity, Long> {

    List<LlmCallEntity> findByAtGreaterThanEqualOrderByAtDesc(Instant since, Limit limit);

    /**
     * Per-model totals computed in the database. Aggregating in SQL rather than loading the history
     * keeps the panel's cost flat as the history grows.
     */
    @Query("""
            select c.provider, c.model, count(c), sum(case when c.ok then 0 else 1 end),
                   avg(c.latencyMs), sum(c.inputTokens), sum(c.outputTokens)
            from LlmCallEntity c
            where c.at >= :since
            group by c.provider, c.model
            order by count(c) desc""")
    List<Object[]> aggregateSince(@Param("since") Instant since);

    @Modifying
    @Query("delete from LlmCallEntity c where c.at < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
