package com.atlasmind.atlaswatch.models;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@Embeddable
public class MovieKeywordId implements Serializable {

    @Column(name = "movie_id")
    private Long movieId;

    @Column(name = "keyword_id")
    private Long keywordId;
}
