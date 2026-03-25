# Search Performance: ILIKE vs Full-Text Search

## Problem
Searching movies by title using `ILIKE '%query%'` forces a sequential scan —
PostgreSQL reads every row in the table regardless of table size.
This is O(n) and doesn't scale.

## Solution
PostgreSQL full-text search with a GIN-indexed tsvector generated column.

## Schema Change
```sql
ALTER TABLE movie ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (
        to_tsvector('english', coalesce(movie_title, '') || ' ' || coalesce(overview, ''))
    ) STORED;

CREATE INDEX idx_movie_search ON movie USING GIN (search_vector);
```

## Before: ILIKE (Seq Scan)
```sql
EXPLAIN ANALYZE SELECT * FROM movie WHERE movie_title ILIKE '%dark%';
```
```
                                             QUERY PLAN
----------------------------------------------------------------------------------------------------
 Seq Scan on movie  (cost=0.00..25.93 rows=18 width=391) (actual time=0.065..0.373 rows=20 loops=1)
   Filter: ((movie_title)::text ~~* '%dark%'::text)
   Rows Removed by Filter: 374
 Planning Time: 0.334 ms
 Execution Time: 0.383 ms
(5 rows)

```

## After: Full-Text Search (Index Scan)
```sql
EXPLAIN ANALYZE SELECT *, ts_rank(search_vector, to_tsquery('english', 'dark')) AS rank
FROM movie WHERE search_vector @@ to_tsquery('english', 'dark')
ORDER BY rank DESC;
```
```
                                                           QUERY PLAN
---------------------------------------------------------------------------------------------------------------------------------
 Sort  (cost=19.81..19.81 rows=2 width=427) (actual time=1.000..1.002 rows=25 loops=1)
   Sort Key: (ts_rank(search_vector, '''dark'''::tsquery)) DESC
   Sort Method: quicksort  Memory: 50kB
   ->  Bitmap Heap Scan on movie  (cost=12.83..19.80 rows=2 width=427) (actual time=0.787..0.923 rows=25 loops=1)
         Recheck Cond: (search_vector @@ '''dark'''::tsquery)
         Heap Blocks: exact=8
         ->  Bitmap Index Scan on idx_movie_search  (cost=0.00..12.83 rows=2 width=0) (actual time=0.317..0.317 rows=25 loops=1)
               Index Cond: (search_vector @@ '''dark'''::tsquery)
 Planning Time: 2.921 ms
 Execution Time: 1.473 ms
(10 rows)

```

## Results
| Approach | Scan Type          | Execution Time | Rows Scanned | Rows Matched |
|----------|--------------------|---------------|--------------|--------------|
| ILIKE    | Seq Scan           | 0.383 ms      | 394 (all)    | 20           |
| FTS+GIN  | Bitmap Index Scan  | 1.473 ms      | 25 (indexed) | 25           |

## Analysis
At 394 rows, ILIKE is faster (0.38ms vs 1.47ms). This is expected —
sequential scanning a small table is cheaper than the overhead of index
lookup + heap fetch + ts_rank scoring + sorting.

The critical difference is in the **Rows Scanned** column:
- ILIKE scanned **all 394 rows** and filtered out 374. As the table grows
  to 100K+ rows, this scan grows linearly.
- FTS scanned **only 25 rows** via the GIN index. As the table grows,
  this stays proportional to the number of matches, not the table size.

The GIN index pays for itself at scale. At 10,000+ rows, the sequential
scan becomes the bottleneck while the indexed search stays near-constant.
This is the same reason production databases use B-tree indexes for
equality lookups even though a sequential scan is faster on tiny tables.

## Why FTS Over ILIKE Regardless of Current Scale
Even ignoring performance, FTS provides features ILIKE cannot:
- **Stemming**: searching "running" also matches "run", "runs", "ran"
- **Stop word removal**: ignores "the", "a", "is" for better relevance
- **Ranking**: results sorted by relevance, not just filtered by match
- **Language awareness**: understands English word forms