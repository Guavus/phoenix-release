/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.hbase.index.builder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.Stoppable;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.regionserver.MiniBatchOperationInProgress;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.phoenix.coprocessor.BaseScannerRegionObserver.ReplayWrite;
import org.apache.phoenix.hbase.index.Indexer;
import org.apache.phoenix.hbase.index.covered.IndexMetaData;
import org.apache.phoenix.hbase.index.parallel.QuickFailingTaskRunner;
import org.apache.phoenix.hbase.index.parallel.Task;
import org.apache.phoenix.hbase.index.parallel.TaskBatch;
import org.apache.phoenix.hbase.index.parallel.ThreadPoolBuilder;

import com.google.common.util.concurrent.MoreExecutors;

/**
 * Manage the building of index updates from primary table updates.
 * <p>
 * Internally, parallelizes updates through a thread-pool to a delegate index builder. Underlying
 * {@link IndexBuilder} <b>must be thread safe</b> for each index update.
 */
public class IndexBuildManager implements Stoppable {

  private static final Log LOG = LogFactory.getLog(IndexBuildManager.class);
  private final IndexBuilder delegate;
  private QuickFailingTaskRunner pool;
  private boolean stopped;

  /**
   * Set the number of threads with which we can concurrently build index updates. Unused threads
   * will be released, but setting the number of threads too high could cause frequent swapping and
   * resource contention on the server - <i>tune with care</i>. However, if you are spending a lot
   * of time building index updates, it could be worthwhile to spend the time to tune this parameter
   * as it could lead to dramatic increases in speed.
   */
  public static final String NUM_CONCURRENT_INDEX_BUILDER_THREADS_CONF_KEY = "index.builder.threads.max";
  /** Default to a single thread. This is the safest course of action, but the slowest as well */
  private static final int DEFAULT_CONCURRENT_INDEX_BUILDER_THREADS = 10;
  /**
   * Amount of time to keep idle threads in the pool. After this time (seconds) we expire the
   * threads and will re-create them as needed, up to the configured max
   */
  private static final String INDEX_BUILDER_KEEP_ALIVE_TIME_CONF_KEY =
      "index.builder.threads.keepalivetime";

  /**
   * @param env environment in which <tt>this</tt> is running. Used to setup the
   *          {@link IndexBuilder} and executor
   * @throws IOException if an {@link IndexBuilder} cannot be correctly steup
   */
  public IndexBuildManager(RegionCoprocessorEnvironment env) throws IOException {
    // Prevent deadlock by using single thread for all reads so that we know
    // we can get the ReentrantRWLock. See PHOENIX-2671 for more details.
    this(getIndexBuilder(env), new QuickFailingTaskRunner(MoreExecutors.newDirectExecutorService()));
  }
  
  private static IndexBuilder getIndexBuilder(RegionCoprocessorEnvironment e) throws IOException {
    Configuration conf = e.getConfiguration();
    Class<? extends IndexBuilder> builderClass =
        conf.getClass(Indexer.INDEX_BUILDER_CONF_KEY, null, IndexBuilder.class);
    try {
      IndexBuilder builder = builderClass.newInstance();
      builder.setup(e);
      return builder;
    } catch (InstantiationException e1) {
      throw new IOException("Couldn't instantiate index builder:" + builderClass
          + ", disabling indexing on table " + e.getRegion().getTableDesc().getNameAsString());
    } catch (IllegalAccessException e1) {
      throw new IOException("Couldn't instantiate index builder:" + builderClass
          + ", disabling indexing on table " + e.getRegion().getTableDesc().getNameAsString());
    }
  }

  private static ThreadPoolBuilder getPoolBuilder(RegionCoprocessorEnvironment env) {
    String serverName = env.getRegionServerServices().getServerName().getServerName();
    return new ThreadPoolBuilder(serverName + "-index-builder", env.getConfiguration()).
        setCoreTimeout(INDEX_BUILDER_KEEP_ALIVE_TIME_CONF_KEY).
        setMaxThread(NUM_CONCURRENT_INDEX_BUILDER_THREADS_CONF_KEY,
          DEFAULT_CONCURRENT_INDEX_BUILDER_THREADS);
  }

  public IndexBuildManager(IndexBuilder builder, QuickFailingTaskRunner pool) {
    this.delegate = builder;
    this.pool = pool;
  }


  public Collection<Pair<Mutation, byte[]>> getIndexUpdate(
      MiniBatchOperationInProgress<Mutation> miniBatchOp,
      Collection<? extends Mutation> mutations) throws Throwable {
    // notify the delegate that we have started processing a batch
    final IndexMetaData indexMetaData = this.delegate.getIndexMetaData(miniBatchOp);
    this.delegate.batchStarted(miniBatchOp, indexMetaData);

    // This used to be multi-threaded but was removed as it was unsafe.
    ArrayList<Pair<Mutation, byte[]>> results = new ArrayList<>(mutations.size());
    for (final Mutation m : mutations) {
      results.addAll(delegate.getIndexUpdate(m, indexMetaData));
    }
    return results;
  }

  public Collection<Pair<Mutation, byte[]>> getIndexUpdateForFilteredRows(
      Collection<KeyValue> filtered, IndexMetaData indexMetaData) throws IOException {
    // this is run async, so we can take our time here
    return delegate.getIndexUpdateForFilteredRows(filtered, indexMetaData);
  }

  public void batchCompleted(MiniBatchOperationInProgress<Mutation> miniBatchOp) {
    delegate.batchCompleted(miniBatchOp);
  }

  public void batchStarted(MiniBatchOperationInProgress<Mutation> miniBatchOp, IndexMetaData indexMetaData)
      throws IOException {
    delegate.batchStarted(miniBatchOp, indexMetaData);
  }

  public boolean isEnabled(Mutation m) throws IOException {
    return delegate.isEnabled(m);
  }

  @Override
  public void stop(String why) {
    if (stopped) {
      return;
    }
    this.stopped = true;
    this.delegate.stop(why);
    this.pool.stop(why);
  }

  @Override
  public boolean isStopped() {
    return this.stopped;
  }

  public IndexBuilder getBuilderForTesting() {
    return this.delegate;
  }

  public ReplayWrite getReplayWrite(Mutation m) throws IOException {
      return this.delegate.getReplayWrite(m);
  }
}