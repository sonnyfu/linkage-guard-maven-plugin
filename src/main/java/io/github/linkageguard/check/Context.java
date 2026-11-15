package io.github.linkageguard.check;

import io.github.linkageguard.classpath.ClassIndex;
import io.github.linkageguard.model.Findings;

record Context(ClassIndex idx, Resolver resolver, Hierarchy hier, Findings findings) {}
