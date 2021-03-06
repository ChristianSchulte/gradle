/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.api.internal.artifacts.transform;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import org.gradle.api.Action;
import org.gradle.api.internal.artifacts.ivyservice.ArtifactCacheMetaData;
import org.gradle.api.internal.changedetection.state.InMemoryCacheDecoratorFactory;
import org.gradle.cache.CacheBuilder;
import org.gradle.cache.CacheRepository;
import org.gradle.cache.PersistentCache;
import org.gradle.cache.PersistentIndexedCache;
import org.gradle.cache.PersistentIndexedCacheParameters;
import org.gradle.cache.internal.FileLockManager;
import org.gradle.internal.Factory;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.concurrent.Stoppable;
import org.gradle.internal.resource.local.FileStore;
import org.gradle.internal.resource.local.FileStoreAddActionException;
import org.gradle.internal.resource.local.PathKeyFileStore;
import org.gradle.internal.serialize.BaseSerializerFactory;
import org.gradle.internal.serialize.HashCodeSerializer;
import org.gradle.internal.serialize.ListSerializer;
import org.gradle.internal.util.BiFunction;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.gradle.api.internal.artifacts.ivyservice.CacheLayout.TRANSFORMS_META_DATA;
import static org.gradle.api.internal.artifacts.ivyservice.CacheLayout.TRANSFORMS_STORE;
import static org.gradle.cache.internal.filelock.LockOptionsBuilder.mode;

public class DefaultTransformedFileCache implements TransformedFileCache, Stoppable {
    private final PersistentCache cache;
    private final PersistentIndexedCache<HashCode, List<File>> indexedCache;
    private final FileStore<String> fileStore;
    private final Object lock = new Object();
    private final Set<HashCode> transforming = new HashSet<HashCode>();

    public DefaultTransformedFileCache(ArtifactCacheMetaData artifactCacheMetaData, CacheRepository cacheRepository, InMemoryCacheDecoratorFactory cacheDecoratorFactory) {
        File transformsStoreDirectory = artifactCacheMetaData.getTransformsStoreDirectory();
        File filesOutputDirectory = new File(transformsStoreDirectory, TRANSFORMS_STORE.getKey());
        fileStore = new PathKeyFileStore(filesOutputDirectory);
        cache = cacheRepository
                .cache(transformsStoreDirectory)
                .withCrossVersionCache(CacheBuilder.LockTarget.DefaultTarget)
                .withDisplayName("Artifact transforms cache")
                .withLockOptions(mode(FileLockManager.LockMode.None)) // Lock on demand
                .open();
        String cacheName = TRANSFORMS_META_DATA.getKey() + "/results";
        PersistentIndexedCacheParameters<HashCode, List<File>> cacheParameters = new PersistentIndexedCacheParameters<HashCode, List<File>>(cacheName, new HashCodeSerializer(), new ListSerializer<File>(BaseSerializerFactory.FILE_SERIALIZER))
                .cacheDecorator(cacheDecoratorFactory.decorator(1000, true));
        indexedCache = cache.createCache(cacheParameters);
    }

    @Override
    public void stop() {
        cache.close();
    }

    @Override
    public List<File> getResult(final File inputFile, final HashCode inputsHash, final BiFunction<List<File>, File, File> transformer) {
        // Apply locking so that only this process is writing to the file store and only a single thread is running this particular transform
        transforming(inputsHash);
        try {
            return cache.withFileLock(new Factory<List<File>>() {
                @Override
                public List<File> create() {
                    List<File> files = indexedCache.get(inputsHash);
                    if (files != null) {
                        boolean allExist = true;
                        for (File file : files) {
                            if (!file.exists()) {
                                allExist = false;
                                break;
                            }
                        }
                        if (allExist) {
                            return files;
                        }
                        // Else, recreate outputs
                    }

                    // File store takes care of cleaning up on failure/crash
                    String key = inputFile.getName() + "/" + inputsHash;
                    TransformAction action = new TransformAction(transformer, inputFile);
                    try {
                        fileStore.add(key, action);
                    } catch (FileStoreAddActionException e) {
                        throw UncheckedException.throwAsUncheckedException(e.getCause());
                    }

                    indexedCache.put(inputsHash, action.result);
                    return action.result;
                }
            });
        } finally {
            notTransforming(inputsHash);
        }
    }

    private void transforming(HashCode inputsHash) {
        synchronized (lock) {
            while (!transforming.add(inputsHash)) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    throw UncheckedException.throwAsUncheckedException(e);
                }
            }
        }
    }

    private void notTransforming(HashCode inputsHash) {
        synchronized (lock) {
            transforming.remove(inputsHash);
            lock.notifyAll();
        }
    }

    private static class TransformAction implements Action<File> {
        private final BiFunction<List<File>, File, File> transformer;
        private final File inputFile;
        private ImmutableList<File> result;

        TransformAction(BiFunction<List<File>, File, File> transformer, File inputFile) {
            this.transformer = transformer;
            this.inputFile = inputFile;
        }

        @Override
        public void execute(File outputDir) {
            outputDir.mkdirs();
            result = ImmutableList.copyOf(transformer.apply(inputFile, outputDir));
        }
    }
}
