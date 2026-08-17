package com.atlasmind.atlaswatch.service;

import java.util.List;

record MovieSignalFeatures(
        List<String> genres,
        List<String> keywords
) {
    static final MovieSignalFeatures EMPTY = new MovieSignalFeatures(List.of(), List.of());
}
