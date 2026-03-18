package com.atlasmind.ai_travel_recommendation.models;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Table(
        uniqueConstraints = @UniqueConstraint(
                name = "uk_watchlist_user_movie",
                columnNames = {"user_id", "movie_id"}
        )
)
public class WatchList {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "watchList_seq")
    @SequenceGenerator(name = "watchList_seq", sequenceName = "db_watchList", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movie_id", nullable = false)
    private Movie movie;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WatchListStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime addedAt;
}
