/*
 * Copyright 2020 the original author or authors.
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

package org.gradle.internal.watch.registry.impl;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import net.rubygrapefruit.platform.file.FileWatcher;
import org.gradle.internal.snapshot.CompleteFileSystemLocationSnapshot;
import org.gradle.internal.watch.registry.FileWatcherUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Updater for hierarchical file watchers.
 *
 * We want to keep track of root project directories for hierarchical watchers,
 * because we prefer watching the root project directory instead of directories inside.
 * Watching the root project directories is better since they are less likely to be deleted and
 * nearly no changes to the watched directories are necessary when running builds on the same project.
 *
 * To allow deleting the root project directories, we need to stop watching a root project directory if there are no more snapshots in the VFS inside,
 * since watched directories can't be deleted on Windows.
 *
 * The root project directories are discovered as included builds are encountered at the start of a build, and then they are removed when the build finishes.
 *
 * This is the lifecycle for the watched root project directories:
 * - During a build, there will be various calls to {@link #updateRootProjectDirectories(Collection)},
 *   each call augmenting the collection. The watchers will be updated accordingly.
 * - When updating the watches, we watch root project directories or old root project directories instead of
 *   directories inside them.
 * - At the end of the build
 *   - stop watching the root project directories with nothing to watch inside
 *   - remember the current watched root project directories as old root directories for the next build
 *   - remove all non-watched root project directories from the old root directories.
 */
public class HierarchicalFileWatcherUpdater implements FileWatcherUpdater {
    private static final Logger LOGGER = LoggerFactory.getLogger(HierarchicalFileWatcherUpdater.class);

    private final Multimap<String, Path> trackedDirectoriesForSnapshot = HashMultimap.create();

    private final Set<Path> watchedHierarchies = new HashSet<>();

    private final Set<RootProjectDirectory> knownRootProjectDirectoriesFromCurrentBuild = new HashSet<>();
    private final Set<RootProjectDirectory> watchedRootProjectDirectoriesFromPreviousBuild = new HashSet<>();

    private final FileWatcher watcher;

    public HierarchicalFileWatcherUpdater(FileWatcher watcher) {
        this.watcher = watcher;
    }

    @Override
    public void changed(Collection<CompleteFileSystemLocationSnapshot> removedSnapshots, Collection<CompleteFileSystemLocationSnapshot> addedSnapshots) {
        removedSnapshots.forEach(snapshot -> {
            trackedDirectoriesForSnapshot.removeAll(snapshot.getAbsolutePath());
        });
        addedSnapshots.forEach(snapshot -> {
            ImmutableList<Path> directoriesToWatch = WatchRootUtil.getDirectoriesToWatch(snapshot);
            trackedDirectoriesForSnapshot.putAll(snapshot.getAbsolutePath(), directoriesToWatch);
        });
        updateWatchedDirectories();
    }

    @Override
    public void buildFinished() {
        watchedRootProjectDirectoriesFromPreviousBuild.addAll(knownRootProjectDirectoriesFromCurrentBuild);
        knownRootProjectDirectoriesFromCurrentBuild.clear();
        updateWatchedDirectories();
        watchedRootProjectDirectoriesFromPreviousBuild.removeIf(
            rootProjectDirectory -> !watchedHierarchies.contains(rootProjectDirectory.path)
        );
    }

    @Override
    public void updateRootProjectDirectories(Collection<File> updatedRootProjectDirectories) {
        Set<Path> rootPaths = updatedRootProjectDirectories.stream()
            .map(File::toPath)
            .map(Path::toAbsolutePath)
            .collect(Collectors.toSet());
        Set<Path> newRootProjectDirectories = WatchRootUtil.resolveRootsToWatch(rootPaths);
        LOGGER.info("Now considering {} as root directories to watch", newRootProjectDirectories);

        knownRootProjectDirectoriesFromCurrentBuild.clear();
        newRootProjectDirectories.stream()
            .map(RootProjectDirectory::new)
            .forEach(knownRootProjectDirectoriesFromCurrentBuild::add);
        watchedRootProjectDirectoriesFromPreviousBuild.removeAll(knownRootProjectDirectoriesFromCurrentBuild);

        updateWatchedDirectories();
    }

    private void updateWatchedDirectories() {
        Set<Path> directoriesToWatch = new HashSet<>();
        trackedDirectoriesForSnapshot.values().forEach(shouldWatchDirectory -> {
            String shouldWatchDirectoryPathString = shouldWatchDirectory.toString();
            if (maybeWatchRootProjectDirectory(directoriesToWatch, shouldWatchDirectoryPathString, knownRootProjectDirectoriesFromCurrentBuild)) {
                return;
            }
            if (maybeWatchRootProjectDirectory(directoriesToWatch, shouldWatchDirectoryPathString, watchedRootProjectDirectoriesFromPreviousBuild)) {
                return;
            }
            directoriesToWatch.add(shouldWatchDirectory);
        });

        updateWatchedDirectories(WatchRootUtil.resolveRootsToWatch(directoriesToWatch));
    }

    private static boolean maybeWatchRootProjectDirectory(Set<Path> directoriesToWatch, String shouldWatchDirectoryPathString, Set<RootProjectDirectory> rootProjectDirectories) {
        for (RootProjectDirectory rootProjectDirectory : rootProjectDirectories) {
            if (rootProjectDirectory.contains(shouldWatchDirectoryPathString)) {
                directoriesToWatch.add(rootProjectDirectory.path);
                return true;
            }
        }
        return false;
    }

    private void updateWatchedDirectories(Set<Path> newWatchRoots) {
        Set<Path> watchRootsToRemove = new HashSet<>(watchedHierarchies);
        if (newWatchRoots.isEmpty()) {
            LOGGER.info("Not watching anything anymore");
        }
        watchRootsToRemove.removeAll(newWatchRoots);
        newWatchRoots.removeAll(watchedHierarchies);
        if (newWatchRoots.isEmpty() && watchRootsToRemove.isEmpty()) {
            return;
        }
        if (!watchRootsToRemove.isEmpty()) {
            watcher.stopWatching(watchRootsToRemove.stream()
                .map(Path::toFile)
                .collect(Collectors.toList())
            );
            watchedHierarchies.removeAll(watchRootsToRemove);
        }
        if (!newWatchRoots.isEmpty()) {
            watcher.startWatching(newWatchRoots.stream()
                .map(Path::toFile)
                .collect(Collectors.toList())
            );
            watchedHierarchies.addAll(newWatchRoots);
        }
        LOGGER.info("Watching {} directory hierarchies to track changes", watchedHierarchies.size());
    }

    private static class RootProjectDirectory {
        private final Path path;
        private final String prefix;

        public RootProjectDirectory(Path path) {
            this.path = path;
            this.prefix = path.toString() + File.separator;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            RootProjectDirectory that = (RootProjectDirectory) o;

            return prefix.equals(that.prefix);
        }

        @Override
        public int hashCode() {
            return prefix.hashCode();
        }

        public boolean contains(String shouldWatchDirectoryPathString) {
            return shouldWatchDirectoryPathString.startsWith(prefix);
        }
    }
}
