package io.github.linkageguard.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Findings {
    private final Map<String, Finding> map = new LinkedHashMap<>();

    public void add(Finding f) {
        map.putIfAbsent(f.key(), f); // keep first occurrence only
    }

    public List<Finding> all() {
        return new ArrayList<>(map.values());
    }
}
