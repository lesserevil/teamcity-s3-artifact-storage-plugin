/*
 * Copyright 2000-2020 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.buildServer.artifacts.s3.cleanup;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.DeleteObjectsRequest;
import com.amazonaws.services.s3.model.MultiObjectDeleteException;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import jetbrains.buildServer.artifacts.ArtifactListData;
import jetbrains.buildServer.artifacts.ServerArtifactStorageSettingsProvider;
import jetbrains.buildServer.artifacts.s3.S3Constants;
import jetbrains.buildServer.artifacts.s3.S3Util;
import jetbrains.buildServer.artifacts.s3.util.ParamUtil;
import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.serverSide.SFinishedBuild;
import jetbrains.buildServer.serverSide.ServerPaths;
import jetbrains.buildServer.serverSide.TeamCityProperties;
import jetbrains.buildServer.serverSide.artifacts.ServerArtifactHelper;
import jetbrains.buildServer.serverSide.cleanup.*;
import jetbrains.buildServer.serverSide.executors.ExecutorServices;
import jetbrains.buildServer.serverSide.impl.LogUtil;
import jetbrains.buildServer.serverSide.impl.cleanup.ArtifactPathsEvaluator;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.util.positioning.PositionAware;
import jetbrains.buildServer.util.positioning.PositionConstraint;
import org.jetbrains.annotations.NotNull;

import static jetbrains.buildServer.util.NamedThreadFactory.executeWithNewThreadName;
import static jetbrains.buildServer.util.Util.doUnderContextClassLoader;

public class S3CleanupExtension implements CleanupExtension, PositionAware {

  @NotNull
  private final ServerArtifactStorageSettingsProvider mySettingsProvider;
  @NotNull
  private final ServerArtifactHelper myHelper;
  @NotNull
  private final ServerPaths myServerPaths;
  @NotNull
  private final ExecutorService myExecutorService;

  public S3CleanupExtension(
    @NotNull final ServerArtifactHelper helper,
    @NotNull final ServerArtifactStorageSettingsProvider settingsProvider,
    @NotNull final ServerPaths serverPaths,
    @NotNull final ExecutorServices executorServices) {
    myHelper = helper;
    mySettingsProvider = settingsProvider;
    myServerPaths = serverPaths;
    myExecutorService = executorServices.getLowPriorityExecutorService();
  }

  @Override
  public void cleanupBuildsData(@NotNull BuildCleanupContext cleanupContext) { 
    for (SFinishedBuild build : cleanupContext.getBuilds()) {

      try {
        final ArtifactListData artifactsInfo = myHelper.getArtifactList(build);
        if (artifactsInfo == null) {
          continue;
        }
        final String pathPrefix = S3Util.getPathPrefix(artifactsInfo);
        if (pathPrefix == null) {
          continue;
        }

        List<String> pathsToDelete = ArtifactPathsEvaluator.getPathsToDelete((BuildCleanupContextEx)cleanupContext, build, artifactsInfo);
        if (pathsToDelete.isEmpty()) {
          continue;
        }

        doClean(cleanupContext.getErrorReporter(), build, pathPrefix, pathsToDelete);
      } catch (Throwable e) {
        Loggers.CLEANUP.debug(e);
        cleanupContext.getErrorReporter().buildCleanupError(build.getBuildId(), "Failed to remove S3 artifacts: " + e.getMessage());
      }
    }
  }

  private void doClean(@NotNull ErrorReporter errorReporter, @NotNull SFinishedBuild build, @NotNull String pathPrefix, @NotNull List<String> pathsToDelete) throws IOException {
    final Map<String, String> params = S3Util.validateParameters(mySettingsProvider.getStorageSettings(build));
    final String bucketName = S3Util.getBucketName(params);
    S3Util.withS3ClientShuttingDownImmediately(ParamUtil.putSslValues(myServerPaths, params), client -> {
      final String suffix = " from S3 bucket [" + bucketName + "]" + " from path [" + pathPrefix + "]";

      final AtomicInteger succeededNum = new AtomicInteger();
      final AtomicInteger errorNum = new AtomicInteger();

      final int batchSize = TeamCityProperties.getInteger(S3Constants.S3_CLEANUP_BATCH_SIZE, 1000);
      final List<List<String>> partitions = Lists.partition(pathsToDelete, batchSize);
      final AtomicInteger currentChunk = new AtomicInteger();
      partitions.forEach(part -> {
        try {
          final Future<Integer> submit = myExecutorService.submit(
            () -> deleteChunk(pathPrefix, bucketName, client, part, () -> progressMessage(build, pathsToDelete, succeededNum, currentChunk, partitions.size(), part.size())));
          succeededNum.addAndGet(submit.get());
        } catch (MultiObjectDeleteException e) {
          succeededNum.addAndGet(e.getDeletedObjects().size());

          final List<MultiObjectDeleteException.DeleteError> errors = e.getErrors();
          errors.forEach(error -> {
            final String key = error.getKey();
            if (key.startsWith(pathPrefix)) {
              Loggers.CLEANUP.debug("Failed to remove " + key + " from S3 bucket " + bucketName + ": " + error.getMessage());
              pathsToDelete.remove(key.substring(pathPrefix.length()));
            }
          });
          errorNum.addAndGet(errors.size());
        } catch (ExecutionException | InterruptedException e) {
          Loggers.CLEANUP.error("Got an exception while processing chunk " + part, e);
          errorNum.addAndGet(part.size());
        }
      });

      if (errorNum.get() > 0) {
        String errorMessage = "Failed to remove [" + errorNum + "] S3 " + StringUtil.pluralize("object", errorNum.get()) + suffix;
        errorReporter.buildCleanupError(build.getBuildId(), errorMessage);
      }

      Loggers.CLEANUP.info("Removed [" + succeededNum + "] S3 " + StringUtil.pluralize("object", succeededNum.get()) + suffix);

      myHelper.removeFromArtifactList(build, pathsToDelete);

      return null;
    });
  }

  @NotNull
  private String progressMessage(@NotNull final SFinishedBuild build,
                                 @NotNull final List<String> pathsToDelete,
                                 @NotNull final AtomicInteger succeededNum,
                                 @NotNull final AtomicInteger currentChunkNumber,
                                 final int size,
                                 final int chunkSize) {
    return "Cleaning artifacts of Build " + LogUtil.describe(build) + ": " +
           "S3Client deleting chunk #" + currentChunkNumber.incrementAndGet() + "/" + size + " of " + chunkSize + "/" + (pathsToDelete.size() - succeededNum.get()) + " objects";
  }

  @NotNull
  private Integer deleteChunk(@NotNull final String pathPrefix,
                              @NotNull final String bucketName,
                              @NotNull final AmazonS3 client,
                              @NotNull final List<String> part,
                              @NotNull final Supplier<String> info) throws Exception {
    final List<DeleteObjectsRequest.KeyVersion> objectKeys = part.stream().map(path -> new DeleteObjectsRequest.KeyVersion(pathPrefix + path)).collect(Collectors.toList());
    final DeleteObjectsRequest deleteObjectsRequest = new DeleteObjectsRequest(bucketName).withKeys(objectKeys);
    return executeWithNewThreadName(info.get(),
                                    () -> doUnderContextClassLoader(S3Util.class.getClassLoader(), () -> client.deleteObjects(deleteObjectsRequest).getDeletedObjects().size()));
  }

  @NotNull
  @Override
  public PositionConstraint getConstraint() {
    return PositionConstraint.first();
  }

  @NotNull
  @Override
  public String getOrderId() {
    return S3Constants.S3_STORAGE_TYPE;
  }
}
