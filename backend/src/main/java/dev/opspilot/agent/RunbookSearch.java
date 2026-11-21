package dev.opspilot.agent;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Retrieval over runbooks using PostgreSQL full-text search.
 * The query words are OR-ed ("add | servers | capacity") and ranked by ts_rank,
 * with title matches weighted higher (setweight 'A' in the migration).
 */
@Component
public class RunbookSearch {
    public record Hit(String slug, String title, String snippet, double rank) { }

    private final JdbcClient jdbc;

    public RunbookSearch(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<Hit> search(String query, int limit) {
        String tsQuery = toOrQuery(query);
        if (tsQuery.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT slug, title,
                       ts_headline('english', content, q, 'MaxWords=40, MinWords=20, StartSel="", StopSel=""') AS snippet,
                       ts_rank(search, q) AS rank
                FROM runbook, to_tsquery('english', :q) q
                WHERE search @@ q
                ORDER BY rank DESC, slug
                LIMIT :limit
                """)
                .param("q", tsQuery)
                .param("limit", limit)
                .query((rs, i) -> new Hit(rs.getString("slug"), rs.getString("title"),
                        rs.getString("snippet"), rs.getDouble("rank")))
                .list();
    }

    /** Sanitize to plain words (no tsquery operators from user/LLM input) and OR them together. */
    static String toOrQuery(String query) {
        if (query == null) return "";
        return Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(w -> w.length() >= 2)
                .distinct()
                .limit(12)
                .collect(Collectors.joining(" | "));
    }
}
