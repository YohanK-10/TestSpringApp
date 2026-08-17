package com.atlasmind.atlaswatch.repository;

import com.atlasmind.atlaswatch.models.MovieKeyword;
import com.atlasmind.atlaswatch.models.MovieKeywordId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface MovieKeywordRepository extends JpaRepository<MovieKeyword, MovieKeywordId> {

    List<MovieKeyword> findByMovieId(Long movieId);

    @Query("SELECT mk FROM MovieKeyword mk JOIN FETCH mk.keyword JOIN FETCH mk.movie WHERE mk.movie.id IN :movieIds")
    List<MovieKeyword> findByMovieIdInWithKeyword(@Param("movieIds") Collection<Long> movieIds);

    @Modifying
    @Transactional
    void deleteByMovieId(Long movieId);
}
