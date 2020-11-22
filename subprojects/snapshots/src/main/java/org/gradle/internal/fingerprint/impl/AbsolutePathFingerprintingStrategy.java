/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.fingerprint.impl;

import com.google.common.collect.ImmutableMap;

import org.gradle.internal.file.FileType;
import org.gradle.internal.fingerprint.DirectorySensitivity;
import org.gradle.internal.fingerprint.FileSystemLocationFingerprint;
import org.gradle.internal.fingerprint.FingerprintHashingStrategy;
import org.gradle.internal.fingerprint.FingerprintingStrategy;
import org.gradle.internal.snapshot.CompleteFileSystemLocationSnapshot;
import org.gradle.internal.snapshot.FileSystemSnapshot;
import org.gradle.internal.snapshot.RootTrackingFileSystemSnapshotHierarchyVisitor;
import org.gradle.internal.snapshot.SnapshotVisitResult;

import java.util.HashSet;
import java.util.Map;

/**
 * Fingerprint files without path or content normalization.
 */
public class AbsolutePathFingerprintingStrategy extends AbstractFingerprintingStrategy {
    public static final FingerprintingStrategy DEFAULT = new AbsolutePathFingerprintingStrategy(DirectorySensitivity.DEFAULT);
    public static final FingerprintingStrategy IGNORE_DIRECTORIES = new AbsolutePathFingerprintingStrategy(DirectorySensitivity.IGNORE_DIRECTORIES);
    public static final String IDENTIFIER = "ABSOLUTE_PATH";

    private final DirectorySensitivity directorySensitivity;

    private AbsolutePathFingerprintingStrategy(DirectorySensitivity directorySensitivity) {
        super(IDENTIFIER);
        this.directorySensitivity = directorySensitivity;
    }

    @Override
    public String normalizePath(CompleteFileSystemLocationSnapshot snapshot) {
        return snapshot.getAbsolutePath();
    }

    @Override
    public Map<String, FileSystemLocationFingerprint> collectFingerprints(FileSystemSnapshot roots) {
        ImmutableMap.Builder<String, FileSystemLocationFingerprint> builder = ImmutableMap.builder();
        HashSet<String> processedEntries = new HashSet<>();
        roots.accept(new RootTrackingFileSystemSnapshotHierarchyVisitor() {
            @Override
            public SnapshotVisitResult visitEntry(CompleteFileSystemLocationSnapshot snapshot, boolean isRoot) {
                String absolutePath = snapshot.getAbsolutePath();
                if (processedEntries.add(absolutePath) && shouldFingerprint(snapshot)) {
                    builder.put(absolutePath, new DefaultFileSystemLocationFingerprint(snapshot.getAbsolutePath(), snapshot));
                }
                return SnapshotVisitResult.CONTINUE;
            }
        });
        return builder.build();
    }

    private boolean shouldFingerprint(CompleteFileSystemLocationSnapshot snapshot) {
        return !(snapshot.getType() == FileType.Directory && directorySensitivity == DirectorySensitivity.IGNORE_DIRECTORIES);
    }

    @Override
    public FingerprintHashingStrategy getHashingStrategy() {
        return FingerprintHashingStrategy.SORT;
    }

    @Override
    public DirectorySensitivity getDirectorySensitivity() {
        return directorySensitivity;
    }
}
