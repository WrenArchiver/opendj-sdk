/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at
 * trunk/opends/resource/legal-notices/OpenDS.LICENSE
 * or https://OpenDS.dev.java.net/OpenDS.LICENSE.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at
 * trunk/opends/resource/legal-notices/OpenDS.LICENSE.  If applicable,
 * add the following below this CDDL HEADER, with the fields enclosed
 * by brackets "[]" replaced with your own identifying information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2008-2009 Sun Microsystems, Inc.
 */

package org.opends.server.backends.jeb.importLDIF;


import static org.opends.messages.JebMessages.*;
import static org.opends.server.loggers.ErrorLogger.*;
import static org.opends.server.util.DynamicConstants.*;
import static org.opends.server.util.ServerConstants.*;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.opends.server.util.StaticUtils.getFileForPath;
import org.opends.messages.Message;
import org.opends.messages.Category;
import org.opends.messages.Severity;
import org.opends.server.admin.std.server.LocalDBBackendCfg;
import org.opends.server.backends.jeb.*;
import org.opends.server.config.ConfigException;
import org.opends.server.core.DirectoryServer;
import org.opends.server.types.*;
import org.opends.server.util.*;
import com.sleepycat.je.*;


/**
 * Performs a LDIF import.
 */
public class Importer
{
  private final int DRAIN_TO = 3;
  private final int TIMER_INTERVAL = 10000;
  private final int MB =  (1024 * 1024);
  private final int LDIF_READER_BUF_SIZE = 2 * MB;
  private final int MIN_IMPORT_MEM_REQUIRED = 16 * MB;
  private final int MAX_BUFFER_SIZE = 48 * MB;
  private final int MIN_BUFFER_SIZE = 1024 * 100;
  private final int MIN_READ_AHEAD_CACHE_SIZE = 4096;
  private final int MAX_DB_CACHE_SIZE = 128 * MB;
  private final int MIN_DB_CACHE_SIZE = 16 * MB;
  private final int MAX_DB_LOG_BUF_BYTES = 100 * MB;
  private final int MEM_PCT_PHASE_1 = 45;
  private final int MEM_PCT_PHASE_2 = 50;

  private final String DIRECT_PROPERTY = "import.directphase2";
  private static AttributeType dnType;
  private static IndexBuffer.DNComparator dnComparator
          = new IndexBuffer.DNComparator();
  private static final IndexBuffer.IndexComparator indexComparator =
          new IndexBuffer.IndexComparator();

  private final AtomicInteger bufferCount = new AtomicInteger(0);
  private final File tempDir;
  private final int indexCount, threadCount;
  private final boolean skipDNValidation;
  private final LDIFImportConfig config;
  private final LocalDBBackendCfg dbCfg;
  private final ByteBuffer directBuffer;
  private RootContainer rootContainer;
  private LDIFReader reader;
  private int bufferSize, indexBufferCount;
  private int migratedCount;
  private long dbCacheSize = 0, dbLogBufSize = 0;

  //The executor service used for the sort tasks.
  private ExecutorService sortService;

  //The executor service used for the index processing tasks.
  private ExecutorService indexProcessService;

  //Queue of free index buffers -- used to re-cycle index buffers;
  private final BlockingQueue<IndexBuffer> freeBufQue =
          new LinkedBlockingQueue<IndexBuffer>();

  //Map of index keys to index buffers.  Used to allocate sorted
  //index buffers to a index writer thread.
  private final
  Map<IndexKey, BlockingQueue<IndexBuffer>> indexKeyQueMap =
          new ConcurrentHashMap<IndexKey, BlockingQueue<IndexBuffer>>();

  //Map of DB containers to index managers. Used to start phase 2.
  private final List<IndexManager> indexMgrList =
          new LinkedList<IndexManager>();

  //Futures used to indicate when the index file writers are done flushing
  //their work queues and have exited. End of phase one.
  private final List<Future<?>> indexWriterFutures;

  //List of index file writer tasks. Used to signal stopIndexWriterTasks to the
  //index file writer tasks when the LDIF file has been done.
  private final List<IndexFileWriterTask> indexWriterList;


  //Map of DNs to Suffix objects.
  private final Map<DN, Suffix> dnSuffixMap = new LinkedHashMap<DN, Suffix>();


  private final ConcurrentHashMap<Integer, DatabaseContainer> idContainerMap =
                            new ConcurrentHashMap<Integer, DatabaseContainer>();

  private final ConcurrentHashMap<Integer, EntryContainer> idECMap =
          new ConcurrentHashMap<Integer, EntryContainer>();

  private final Object synObj = new Object();

  static
  {
    if ((dnType = DirectoryServer.getAttributeType("dn")) == null)
    {
      dnType = DirectoryServer.getDefaultAttributeType("dn");
    }
  }

  /**
   * Create a new import job with the specified ldif import config.
   *
   * @param config The LDIF import config.
   * @param dbCfg The local DB backend config.
   * @throws IOException  If a problem occurs while opening the LDIF file for
   *                      reading.
   * @throws  InitializationException If a problem occurs initializationing.
   */
  public Importer(LDIFImportConfig config, LocalDBBackendCfg dbCfg )
          throws IOException, InitializationException
  {
    this.config = config;
    this.dbCfg = dbCfg;
    if(config.getThreadCount() == -1)
    {
      threadCount = Runtime.getRuntime().availableProcessors() * 2;
    }
    else
    {
      threadCount = config.getThreadCount();
      if(threadCount <= 0)
      {
        Message msg = ERR_IMPORT_LDIF_INVALID_THREAD_COUNT.get(threadCount);
        throw new InitializationException(msg);
      }
    }
    indexCount = dbCfg.listLocalDBIndexes().length + 2;
    indexWriterList = new ArrayList<IndexFileWriterTask>(indexCount);
    indexWriterFutures = new CopyOnWriteArrayList<Future<?>>();
    File parentDir;
    if(config.getTmpDirectory() == null)
    {
      parentDir = getFileForPath("import-tmp");
    }
    else
    {
       parentDir = getFileForPath(config.getTmpDirectory());
    }

    tempDir = new File(parentDir, dbCfg.getBackendId());
    if(!tempDir.exists() && !tempDir.mkdirs())
    {
      Message msg = ERR_JEB_IMPORT_CREATE_TMPDIR_ERROR.get(
              String.valueOf(tempDir));
      throw new IOException(msg.toString());
    }
    if (tempDir.listFiles() != null)
    {
      for (File f : tempDir.listFiles())
      {
        f.delete();
      }
    }
    skipDNValidation = config.getSkipDNValidation();
    String propString = System.getProperty(DIRECT_PROPERTY);
    if(propString != null)
    {
      int directSize = Integer.valueOf(propString);
      directBuffer = ByteBuffer.allocateDirect(directSize);
    }
    else
    {
     directBuffer = null;
    }
  }

  private void getBufferSizes(long availMem, int buffers)
  {
    long mem = availMem - (MAX_DB_CACHE_SIZE + MAX_DB_LOG_BUF_BYTES);
    bufferSize = (int) (mem/buffers);
    if(bufferSize >= MIN_BUFFER_SIZE)
    {
      dbCacheSize =  MAX_DB_CACHE_SIZE;
      dbLogBufSize = MAX_DB_LOG_BUF_BYTES;
      if(bufferSize > MAX_BUFFER_SIZE)
      {
        bufferSize = MAX_BUFFER_SIZE;
      }
    }
    else
    {
      mem = availMem - MIN_DB_CACHE_SIZE - (MIN_DB_CACHE_SIZE * 7) / 100;
      bufferSize = (int) (mem/buffers);
      dbCacheSize =  MIN_DB_CACHE_SIZE;
      if(bufferSize < MIN_BUFFER_SIZE)
      {
        Message msg =
               NOTE_JEB_IMPORT_LDIF_BUFF_SIZE_LESS_DEFAULT.get(MIN_BUFFER_SIZE);
        logError(msg);
        bufferSize = MIN_BUFFER_SIZE;
      }
      else
      {
        long constrainedMem = mem - (buffers * MIN_BUFFER_SIZE);
        bufferSize = (int) ((buffers * MIN_BUFFER_SIZE) +
                            (constrainedMem * 50/100));
        bufferSize /= buffers;
        dbCacheSize = MIN_DB_CACHE_SIZE + (constrainedMem * 50/100);
      }
    }
  }


  /**
   * Return the suffix instance in the specified map that matches the specified
   * DN.
   *
   * @param dn The DN to search for.
   * @param map The map to search.
   * @return The suffix instance that matches the DN, or null if no match is
   *         found.
   */
  public static Suffix getMatchSuffix(DN dn, Map<DN, Suffix> map)
  {
    Suffix suffix = null;
    DN nodeDN = dn;

    while (suffix == null && nodeDN != null) {
      suffix = map.get(nodeDN);
      if (suffix == null)
      {
        nodeDN = nodeDN.getParentDNInSuffix();
      }
    }
    return suffix;
  }

  /**
   * Calculate buffer sizes and initialize JEB properties based on memory.
   *
   * @param envConfig The environment config to use in the calculations.
   *
   * @throws InitializationException If a problem occurs during calculation.
   */
  public void init(EnvironmentConfig envConfig)
          throws InitializationException
  {
    Message msg;
    Runtime runtime = Runtime.getRuntime();
    long freeMemory = runtime.freeMemory();
    long maxMemory = runtime.maxMemory();
    long totMemory = runtime.totalMemory();
    long totFreeMemory = (freeMemory + (maxMemory - totMemory));
    long availMemImport = (totFreeMemory * MEM_PCT_PHASE_1) / 100;
    int phaseOneBuffers = 2 * (indexCount * threadCount);
    msg = NOTE_JEB_IMPORT_LDIF_TOT_MEM_BUF.get(availMemImport, phaseOneBuffers);
    logError(msg);
    if (availMemImport < MIN_IMPORT_MEM_REQUIRED)
    {
      msg = ERR_IMPORT_LDIF_LACK_MEM.get(16);
      throw new InitializationException(msg);
    }
    getBufferSizes(availMemImport, phaseOneBuffers);
    envConfig.setConfigParam("je.maxMemory", Long.toString(dbCacheSize));
    msg = NOTE_JEB_IMPORT_LDIF_DB_MEM_BUF_INFO.get(dbCacheSize, bufferSize);
    logError(msg);
    if(dbLogBufSize != 0)
    {
      envConfig.setConfigParam("je.log.totalBufferBytes",
              Long.toString(dbLogBufSize));
      msg = NOTE_JEB_IMPORT_LDIF_LOG_BYTES.get(dbLogBufSize);
      logError(msg);
    }
    return;
  }


  private void initIndexBuffers(int threadCount)
  {
    indexBufferCount = 2 * (indexCount * threadCount);
    for(int i = 0; i < indexBufferCount; i++)
    {
      IndexBuffer b = IndexBuffer.createIndexBuffer(bufferSize);
      freeBufQue.add(b);
    }
  }



  private void initSuffixes() throws DatabaseException, JebException,
           ConfigException, InitializationException
  {
    for(EntryContainer ec : rootContainer.getEntryContainers())
    {
      Suffix suffix = getSuffix(ec);
      if(suffix != null)
      {
        dnSuffixMap.put(ec.getBaseDN(), suffix);
      }
    }
  }


  private Suffix getSuffix(EntryContainer entryContainer)
     throws DatabaseException, JebException, ConfigException,
            InitializationException {
   DN baseDN = entryContainer.getBaseDN();
   EntryContainer srcEntryContainer = null;
   List<DN> includeBranches = new ArrayList<DN>();
   List<DN> excludeBranches = new ArrayList<DN>();

   if(!config.appendToExistingData() &&
       !config.clearBackend())
   {
     for(DN dn : config.getExcludeBranches())
     {
       if(baseDN.equals(dn))
       {
         // This entire base DN was explicitly excluded. Skip.
         return null;
       }
       if(baseDN.isAncestorOf(dn))
       {
         excludeBranches.add(dn);
       }
     }

     if(!config.getIncludeBranches().isEmpty())
     {
       for(DN dn : config.getIncludeBranches())
       {
         if(baseDN.isAncestorOf(dn))
         {
           includeBranches.add(dn);
         }
       }

       if(includeBranches.isEmpty())
       {
         // There are no branches in the explicitly defined include list under
         // this base DN. Skip this base DN alltogether.

         return null;
       }

       // Remove any overlapping include branches.
       Iterator<DN> includeBranchIterator = includeBranches.iterator();
       while(includeBranchIterator.hasNext())
       {
         DN includeDN = includeBranchIterator.next();
         boolean keep = true;
         for(DN dn : includeBranches)
         {
           if(!dn.equals(includeDN) && dn.isAncestorOf(includeDN))
           {
             keep = false;
             break;
           }
         }
         if(!keep)
         {
           includeBranchIterator.remove();
         }
       }

       // Remvoe any exclude branches that are not are not under a include
       // branch since they will be migrated as part of the existing entries
       // outside of the include branches anyways.
       Iterator<DN> excludeBranchIterator = excludeBranches.iterator();
       while(excludeBranchIterator.hasNext())
       {
         DN excludeDN = excludeBranchIterator.next();
         boolean keep = false;
         for(DN includeDN : includeBranches)
         {
           if(includeDN.isAncestorOf(excludeDN))
           {
             keep = true;
             break;
           }
         }
         if(!keep)
         {
           excludeBranchIterator.remove();
         }
       }

       if(includeBranches.size() == 1 && excludeBranches.size() == 0 &&
           includeBranches.get(0).equals(baseDN))
       {
         // This entire base DN is explicitly included in the import with
         // no exclude branches that we need to migrate. Just clear the entry
         // container.
         entryContainer.lock();
         entryContainer.clear();
         entryContainer.unlock();
       }
       else
       {
         // Create a temp entry container
         srcEntryContainer = entryContainer;
         entryContainer =
             rootContainer.openEntryContainer(baseDN,
                                              baseDN.toNormalizedString() +
                                                  "_importTmp");
       }
     }
   }
   return Suffix.createSuffixContext(entryContainer, srcEntryContainer,
                                     includeBranches, excludeBranches);
 }



  /**
   * Import a ldif using the specified root container.
   *
   * @param rootContainer The root container to use during the import.
   *
   * @return A LDIF result.
   * @throws ConfigException If the import failed because of an configuration
   *                         error.
   * @throws IOException If the import failed because of an IO error.
   * @throws InitializationException If the import failed because of an
   *               initialization error.
   * @throws JebException If the import failed due to a database error.
   * @throws InterruptedException If the import failed due to an interrupted
   *                              error.
   * @throws ExecutionException If the import failed due to an execution error.
   * @throws DatabaseException If the import failed due to a database error.
   */
  public LDIFImportResult
  processImport(RootContainer rootContainer) throws ConfigException,
          InitializationException, IOException, JebException, DatabaseException,
          InterruptedException, ExecutionException
  {
    try {
    this.rootContainer = rootContainer;
    this.reader = new LDIFReader(config, rootContainer, LDIF_READER_BUF_SIZE);
    Message message =
            NOTE_JEB_IMPORT_STARTING.get(DirectoryServer.getVersionString(),
                    BUILD_ID, REVISION_NUMBER);
    logError(message);
    message = NOTE_JEB_IMPORT_THREAD_COUNT.get(threadCount);
    logError(message);
    RuntimeInformation.logInfo();
    initSuffixes();
    long startTime = System.currentTimeMillis();
    processPhaseOne();
    processPhaseTwo();
    setIndexesTrusted();
    switchContainers();
    tempDir.delete();
    long finishTime = System.currentTimeMillis();
    long importTime = (finishTime - startTime);
    float rate = 0;
    if (importTime > 0)
      rate = 1000f * reader.getEntriesRead() / importTime;
    message = NOTE_JEB_IMPORT_FINAL_STATUS.get(reader.getEntriesRead(),
            reader.getEntriesRead(), reader.getEntriesIgnored(), reader
             .getEntriesRejected(), migratedCount, importTime / 1000, rate);
    logError(message);
    }
    finally
    {
      reader.close();
    }
    return new LDIFImportResult(reader.getEntriesRead(), reader
            .getEntriesRejected(), reader.getEntriesIgnored());
  }


  private void switchContainers() throws DatabaseException, JebException {

     for(Suffix suffix : dnSuffixMap.values()) {
       DN baseDN = suffix.getBaseDN();
       EntryContainer srcEntryContainer =
               suffix.getSrcEntryContainer();
       if(srcEntryContainer != null) {
         EntryContainer unregEC =
                 rootContainer.unregisterEntryContainer(baseDN);
         //Make sure the unregistered EC for the base DN is the same as
         //the one in the import context.
         if(unregEC != srcEntryContainer) {
           rootContainer.registerEntryContainer(baseDN, unregEC);
           continue;
         }
         srcEntryContainer.lock();
         srcEntryContainer.close();
         srcEntryContainer.delete();
         srcEntryContainer.unlock();
         EntryContainer newEC = suffix.getEntryContainer();
         newEC.lock();
         newEC.setDatabasePrefix(baseDN.toNormalizedString());
         newEC.unlock();
         rootContainer.registerEntryContainer(baseDN, newEC);
       }
     }
   }



  private void setIndexesTrusted() throws JebException
  {
    try {
      for(Suffix s : dnSuffixMap.values()) {
        s.setIndexesTrusted();
      }
    }
    catch (DatabaseException ex)
    {
      Message msg = NOTE_JEB_IMPORT_LDIF_TRUSTED_FAILED.get(ex.getMessage());
      throw new JebException(msg);
    }
  }


  private void processPhaseOne() throws InterruptedException, ExecutionException
  {
    initIndexBuffers(threadCount);
    FirstPhaseProgressTask progressTask = new FirstPhaseProgressTask();
    Timer timer = new Timer();
    timer.scheduleAtFixedRate(progressTask, TIMER_INTERVAL, TIMER_INTERVAL);
    indexProcessService = Executors.newFixedThreadPool(2 * indexCount);
    sortService = Executors.newFixedThreadPool(threadCount);
    ExecutorService execService = Executors.newFixedThreadPool(threadCount);
    List<Callable<Void>> tasks = new ArrayList<Callable<Void>>(threadCount);

    tasks.add(new MigrateExistingTask());
    List<Future<Void>> results = execService.invokeAll(tasks);
    for (Future<Void> result : results)
      assert result.isDone();
    tasks.clear();
    results.clear();

    if (config.appendToExistingData() &&
            config.replaceExistingEntries())
    {
     for (int i = 0; i < threadCount; i++)
      {
        tasks.add(new AppendReplaceTask());
      }
    }
    else
    {
      for (int i = 0; i < threadCount; i++)
      {
        tasks.add(new ImportTask());
      }
    }
    results = execService.invokeAll(tasks);
    for (Future<Void> result : results)
      assert result.isDone();


    tasks.clear();
    results.clear();
    tasks.add(new MigrateExcludedTask());
    results = execService.invokeAll(tasks);
    for (Future<Void> result : results)
      assert result.isDone();


    stopIndexWriterTasks();
    for (Future<?> result : indexWriterFutures)
    {
      result.get();
    }
    execService.shutdown();
    freeBufQue.clear();
    sortService.shutdown();
    timer.cancel();
  }



  private void processPhaseTwo() throws InterruptedException
  {
    SecondPhaseProgressTask progress2Task =
            new SecondPhaseProgressTask(indexMgrList);
    Timer timer2 = new Timer();
    timer2.scheduleAtFixedRate(progress2Task, TIMER_INTERVAL, TIMER_INTERVAL);
    processIndexFiles();
    timer2.cancel();
  }



  private void processIndexFiles() throws InterruptedException
  {
    List<Callable<Void>> tasks = new ArrayList<Callable<Void>>(indexCount);
    if(bufferCount.get() == 0)
    {
      return;
    }
    int cacheSize =  cacheSizeFromFreeMemory();
    int p = 0;
    int offSet = 0;
    if(directBuffer != null)
    {
      cacheSize = cacheSizeFromDirectMemory();
    }
    for(IndexManager idxMgr : indexMgrList)
    {
      if(directBuffer != null)
      {
        int cacheSizes = cacheSize * idxMgr.getBufferList().size();
        offSet += cacheSizes;
        directBuffer.limit(offSet);
        directBuffer.position(p);
        ByteBuffer b = directBuffer.slice();
        tasks.add(new IndexWriteDBTask(idxMgr, b, cacheSize));
        p += cacheSizes;
      }
      else
      {
        tasks.add(new IndexWriteDBTask(idxMgr, null, cacheSize));
      }
    }
    List<Future<Void>> results = indexProcessService.invokeAll(tasks);
    for (Future<Void> result : results)
      assert result.isDone();
    indexProcessService.shutdown();
  }


  private int cacheSizeFromDirectMemory()
  {
    int cap = directBuffer.capacity();
    int cacheSize = cap/bufferCount.get();
    if(cacheSize > bufferSize)
    {
      cacheSize = bufferSize;
    }
    Message msg =
       NOTE_JEB_IMPORT_LDIF_DIRECT_MEM_REPORT.get(bufferCount.get(), cacheSize);
    logError(msg);
    return cacheSize;
  }

  private int cacheSizeFromFreeMemory()
  {
    Runtime runtime = Runtime.getRuntime();
    long freeMemory = runtime.freeMemory();
    long maxMemory = runtime.maxMemory();
    long totMemory = runtime.totalMemory();
    long totFreeMemory = (freeMemory + (maxMemory - totMemory));
    long availMemory = (totFreeMemory * MEM_PCT_PHASE_2) / 100;
    int avgBufSize = (int)(availMemory / bufferCount.get());
    int cacheSize = Math.max(MIN_READ_AHEAD_CACHE_SIZE, avgBufSize);
    if(cacheSize > bufferSize)
    {
      cacheSize = bufferSize;
    }
    Message msg =
     NOTE_JEB_IMPORT_LDIF_INDIRECT_MEM_REPORT.get(bufferCount.get(), cacheSize);
    logError(msg);
    return cacheSize;
  }


  private void stopIndexWriterTasks()
  {
    IndexBuffer idxBuffer = IndexBuffer.createIndexBuffer(0);
    for(IndexFileWriterTask task : indexWriterList)
    {
      task.que.add(idxBuffer);
    }
  }


  /**
   * Task used to migrate excluded branch.
   */
  private final class MigrateExcludedTask extends ImportTask
  {
    private final Set<byte[]> insertKeySet = new HashSet<byte[]>();

  private final
  Map<IndexKey, IndexBuffer> indexBufferMap =
          new HashMap<IndexKey, IndexBuffer>();

    public Void call() throws Exception
    {
      for(Suffix suffix : dnSuffixMap.values()) {
        EntryContainer srcEntryContainer = suffix.getSrcEntryContainer();
        if(srcEntryContainer != null &&
                !suffix.getExcludeBranches().isEmpty()) {
          DatabaseEntry key = new DatabaseEntry();
          DatabaseEntry data = new DatabaseEntry();
          LockMode lockMode = LockMode.DEFAULT;
          OperationStatus status;
          Message message = NOTE_JEB_IMPORT_MIGRATION_START.get(
                  "excluded", String.valueOf(suffix.getBaseDN()));
          logError(message);
          Cursor cursor =
                  srcEntryContainer.getDN2ID().openCursor(null,
                          CursorConfig.READ_COMMITTED);
          Comparator<byte[]> dn2idComparator =
                  srcEntryContainer.getDN2ID().getComparator();
          try {
            for(DN excludedDN : suffix.getExcludeBranches()) {
              byte[] bytes =
                      StaticUtils.getBytes(excludedDN.toNormalizedString());
              key.setData(bytes);
              status = cursor.getSearchKeyRange(key, data, lockMode);
              if(status == OperationStatus.SUCCESS &&
                      Arrays.equals(key.getData(), bytes)) {
                // This is the base entry for a branch that was excluded in the
                // import so we must migrate all entries in this branch over to
                // the new entry container.
                byte[] end =
                    StaticUtils.getBytes("," + excludedDN.toNormalizedString());
                end[0] = (byte) (end[0] + 1);

                while(status == OperationStatus.SUCCESS &&
                        dn2idComparator.compare(key.getData(), end) < 0 &&
                        !config.isCancelled()) {
                  EntryID id = new EntryID(data);
                  Entry entry = srcEntryContainer.getID2Entry().get(null,
                          id, LockMode.DEFAULT);
                  processEntry(entry, rootContainer.getNextEntryID(),
                          suffix);
                  migratedCount++;
                  status = cursor.getNext(key, data, lockMode);
                }
              }
            }
          }
          finally
          {
            cursor.close();
            flushIndexBuffers();
            closeCursors();
          }
        }
      }
      return null;
    }
  }


  /**
   * Task to migrate existing entries.
   */
  private final class MigrateExistingTask extends ImportTask
  {

  private final
  Map<IndexKey, IndexBuffer> indexBufferMap =
          new HashMap<IndexKey, IndexBuffer>();
    private final Set<byte[]> insertKeySet = new HashSet<byte[]>();

    public Void call() throws Exception
    {
        for(Suffix suffix : dnSuffixMap.values()) {
          EntryContainer srcEntryContainer = suffix.getSrcEntryContainer();
          if(srcEntryContainer != null &&
              !suffix.getIncludeBranches().isEmpty()) {
            DatabaseEntry key = new DatabaseEntry();
            DatabaseEntry data = new DatabaseEntry();
            LockMode lockMode = LockMode.DEFAULT;
            OperationStatus status;
            Message message = NOTE_JEB_IMPORT_MIGRATION_START.get(
                "existing", String.valueOf(suffix.getBaseDN()));
            logError(message);
            Cursor cursor =
                srcEntryContainer.getDN2ID().openCursor(null,
                                                  null);
            try {
              status = cursor.getFirst(key, data, lockMode);
              while(status == OperationStatus.SUCCESS &&
                    !config.isCancelled()) {
                DN dn = DN.decode(ByteString.wrap(key.getData()));
                if(!suffix.getIncludeBranches().contains(dn)) {
                  EntryID id = new EntryID(data);
                  Entry entry =
                      srcEntryContainer.getID2Entry().get(null,
                          id, LockMode.DEFAULT);
                  processEntry(entry, rootContainer.getNextEntryID(),suffix);
                  migratedCount++;
                  status = cursor.getNext(key, data, lockMode);
                }  else {
                  // This is the base entry for a branch that will be included
                  // in the import so we don't want to copy the branch to the
                  //  new entry container.

                  /**
                   * Advance the cursor to next entry at the same level in the
                   *  DIT
                   * skipping all the entries in this branch.
                   * Set the next starting value to a value of equal length but
                   * slightly greater than the previous DN. Since keys are
                   * compared in reverse order we must set the first byte
                   * (the comma).
                   * No possibility of overflow here.
                   */
                  byte[] begin =
                      StaticUtils.getBytes("," + dn.toNormalizedString());
                  begin[0] = (byte) (begin[0] + 1);
                  key.setData(begin);
                  status = cursor.getSearchKeyRange(key, data, lockMode);
                }
              }
            } finally {
              cursor.close();
              flushIndexBuffers();
              closeCursors();
            }
          }
        }
      return null;
    }

  }

  /**
   * Task to handle append/replace combination.
   */
  private  class AppendReplaceTask extends ImportTask
  {

    private final
    Map<IndexKey, IndexBuffer> indexBufferMap =
                                           new HashMap<IndexKey, IndexBuffer>();
    private final Set<byte[]> insertKeySet = new HashSet<byte[]>();
    private final Set<byte[]> deleteKeySet = new HashSet<byte[]>();
    private final EntryInformation entryInfo = new EntryInformation();
    private Entry oldEntry;
    private EntryID entryID;

    /**
     * {@inheritDoc}
     */
    public Void call() throws Exception
    {
      while (true)
      {
        if (config.isCancelled())
        {
          IndexBuffer idxBuffer = IndexBuffer.createIndexBuffer(0);
          freeBufQue.add(idxBuffer);
          return null;
        }
        oldEntry = null;
        Entry entry = reader.readEntry(dnSuffixMap, entryInfo);
        if (entry == null)
        {
          break;
        }
        entryID = entryInfo.getEntryID();
        Suffix suffix = entryInfo.getSuffix();
        processEntry(entry, suffix);
      }
      flushIndexBuffers();
      closeCursors();
      return null;
    }


    void processEntry(Entry entry, Suffix suffix)
            throws DatabaseException, ConfigException, DirectoryException,
            JebException

    {
      DN entryDN = entry.getDN();
      DN2ID dn2id = suffix.getDN2ID();
      EntryID oldID = dn2id.get(null, entryDN, LockMode.DEFAULT);
      if(oldID != null)
      {
        oldEntry = suffix.getID2Entry().get(null, oldID, LockMode.DEFAULT);
      }
      if(oldEntry == null)
      {
        if(!skipDNValidation)
        {
          if(!processParent(entryDN, entryID, entry, suffix))
          {
            suffix.removePending(entryDN);
            return;
          }
          if(!suffix.getDN2ID().insert(null, entryDN, entryID))
          {
            suffix.removePending(entryDN);
            Message msg = WARN_JEB_IMPORT_ENTRY_EXISTS.get();
            reader.rejectEntry(entry, msg);
            return;
          }
          suffix.removePending(entryDN);
          processID2SC(entryID, entry, suffix);
        }
        else
        {
          processDN2ID(suffix, entryDN, entryID);
          suffix.removePending(entryDN);
        }
      }
      else
      {
        suffix.removePending(entryDN);
        entryID = oldID;
      }
      suffix.getID2Entry().put(null, entryID, entry);
      if(oldEntry == null)
      {
        processIndexes(suffix, entry, entryID);
      }
      else
      {
        processAllIndexes(suffix, entry, entryID);
      }
    }

    void
    processAllIndexes(Suffix ctx, Entry entry, EntryID entryID) throws
            DatabaseException, DirectoryException, JebException, ConfigException
    {
      Transaction txn = null;
      Map<AttributeType, AttributeIndex> attrMap = ctx.getAttrIndexMap();
      for(Map.Entry<AttributeType, AttributeIndex> mapEntry :
              attrMap.entrySet()) {
        AttributeType attrType = mapEntry.getKey();
          AttributeIndex attributeIndex = mapEntry.getValue();
          Index index;
          if((index=attributeIndex.getEqualityIndex()) != null) {
            indexAttr(index, entry, entryID,
                      new IndexKey(attrType,IndexType.EQUALITY));
          }
          if((index=attributeIndex.getPresenceIndex()) != null) {
            indexAttr(index, entry, entryID,
                      new IndexKey(attrType, IndexType.PRESENCE));
          }
          if((index=attributeIndex.getSubstringIndex()) != null) {
            int subLen = ((SubstringIndexer)index.indexer).getSubStringLen();
            indexAttr(index, entry, entryID,
                      new IndexKey(attrType, IndexType.SUBSTRING, subLen));
          }
          if((index=attributeIndex.getOrderingIndex()) != null) {
            indexAttr(index, entry, entryID,
                      new IndexKey(attrType, IndexType.ORDERING));
          }
          if((index=attributeIndex.getApproximateIndex()) != null) {
            indexAttr(index, entry, entryID,
                       new IndexKey(attrType,IndexType.APPROXIMATE));
          }
          for(VLVIndex vlvIdx : ctx.getEntryContainer().getVLVIndexes()) {
            vlvIdx.addEntry(txn, entryID, entry);
          }
          Map<String,Collection<Index>> extensibleMap =
                  attributeIndex.getExtensibleIndexes();
          if(!extensibleMap.isEmpty()) {
            Collection<Index> subIndexes =
                    attributeIndex.getExtensibleIndexes().get(
                            EXTENSIBLE_INDEXER_ID_SUBSTRING);
            if(subIndexes != null) {
              for(Index subIndex: subIndexes) {
                indexAttr(subIndex, entry, entryID,
                          new IndexKey(attrType, IndexType.EX_SUBSTRING));
              }
            }
            Collection<Index> sharedIndexes =
                    attributeIndex.getExtensibleIndexes().get(
                            EXTENSIBLE_INDEXER_ID_SHARED);
            if(sharedIndexes !=null) {
              for(Index sharedIndex:sharedIndexes) {
                indexAttr(sharedIndex, entry, entryID,
                          new IndexKey(attrType, IndexType.EX_SHARED));
              }
            }
          }
      }
    }



    void indexAttr(Index index, Entry entry, EntryID entryID,
                   IndexKey indexKey) throws DatabaseException,
            ConfigException
    {

      if(oldEntry != null)
      {
        deleteKeySet.clear();
        index.indexer.indexEntry(oldEntry, deleteKeySet);
        for(byte[] delKey : deleteKeySet)
        {
          processKey(index, delKey, entryID, indexComparator, indexKey, false);
        }
      }
      insertKeySet.clear();
      index.indexer.indexEntry(entry, insertKeySet);
      for(byte[] key : insertKeySet)
      {
        processKey(index, key, entryID, indexComparator, indexKey, true);
      }
    }
  }



  /**
   * This task processes the LDIF file during phase 1.
   */
  private  class ImportTask implements Callable<Void>
  {

    private final
    Map<IndexKey, IndexBuffer> indexBufferMap =
          new HashMap<IndexKey, IndexBuffer>();
    private final Set<byte[]> insertKeySet = new HashSet<byte[]>();
    private final EntryInformation entryInfo = new EntryInformation();

    /**
     * {@inheritDoc}
     */
    public Void call() throws Exception
    {
      while (true)
      {
        if (config.isCancelled())
        {
          IndexBuffer idxBuffer = IndexBuffer.createIndexBuffer(0);
          freeBufQue.add(idxBuffer);
          return null;
        }
        Entry entry = reader.readEntry(dnSuffixMap, entryInfo);

        if (entry == null)
        {
          break;
        }
        EntryID entryID = entryInfo.getEntryID();
        Suffix suffix = entryInfo.getSuffix();
        processEntry(entry, entryID, suffix);
      }
      flushIndexBuffers();
      closeCursors();
      return null;
    }


    void closeCursors() throws DatabaseException
    {
      if(!skipDNValidation)
      {
        for(Suffix suffix : dnSuffixMap.values())
        {
          suffix.getEntryContainer().getID2Children().closeCursor();
          suffix.getEntryContainer().getID2Subtree().closeCursor();
        }
      }
    }


    void processEntry(Entry entry, EntryID entryID, Suffix suffix)
            throws DatabaseException, ConfigException, DirectoryException,
            JebException

    {
      DN entryDN = entry.getDN();
      if(!skipDNValidation)
      {
        if(!processParent(entryDN, entryID, entry, suffix))
        {
          suffix.removePending(entryDN);
          return;
        }
        if(!suffix.getDN2ID().insert(null, entryDN, entryID))
        {
          suffix.removePending(entryDN);
          Message msg = WARN_JEB_IMPORT_ENTRY_EXISTS.get();
          reader.rejectEntry(entry, msg);
          return;
        }
        suffix.removePending(entryDN);
        processID2SC(entryID, entry, suffix);
      }
      else
      {
        processDN2ID(suffix, entryDN, entryID);
        suffix.removePending(entryDN);
      }
      suffix.getID2Entry().put(null, entryID, entry);
      processIndexes(suffix, entry, entryID);
      return;
    }

    boolean processParent(DN entryDN, EntryID entryID, Entry entry,
                                  Suffix suffix) throws DatabaseException
    {
      EntryID parentID = null;
      DN parentDN =
              suffix.getEntryContainer().getParentWithinBase(entryDN);
      DN2ID dn2id = suffix.getDN2ID();
      if(dn2id.get(null, entryDN, LockMode.DEFAULT) != null)
      {
        Message msg = WARN_JEB_IMPORT_ENTRY_EXISTS.get();
        reader.rejectEntry(entry, msg);
        return false;
      }

      if (parentDN != null) {
        parentID = suffix.getParentID(parentDN);
        if (parentID == null) {
          dn2id.remove(null, entryDN);
          Message msg =
                      ERR_JEB_IMPORT_PARENT_NOT_FOUND.get(parentDN.toString());
           reader.rejectEntry(entry, msg);
          return false;
        }
      }
      ArrayList<EntryID> IDs;
      if (parentDN != null && suffix.getParentDN() != null &&
              parentDN.equals(suffix.getParentDN())) {
        IDs = new ArrayList<EntryID>(suffix.getIDs());
        IDs.set(0, entryID);
      }
      else
      {
        EntryID nodeID;
        IDs = new ArrayList<EntryID>(entryDN.getNumComponents());
        IDs.add(entryID);
        if (parentID != null)
        {
          IDs.add(parentID);
          EntryContainer ec = suffix.getEntryContainer();
          for (DN dn = ec.getParentWithinBase(parentDN); dn != null;
               dn = ec.getParentWithinBase(dn)) {
            if((nodeID =  getAncestorID(dn2id, dn)) == null) {
              return false;
            } else {
              IDs.add(nodeID);
            }
          }
        }
      }
      suffix.setParentDN(parentDN);
      suffix.setIDs(IDs);
      entry.setAttachment(IDs);
      return true;
    }

    void processID2SC(EntryID entryID, Entry entry, Suffix suffix)
            throws DatabaseException
    {
      Set<byte[]> childKeySet = new HashSet<byte[]>();
      Set<byte[]> subtreeKeySet = new HashSet<byte[]>();
      Index id2children = suffix.getEntryContainer().getID2Children();
      Index id2subtree = suffix.getEntryContainer().getID2Subtree();
      id2children.indexer.indexEntry(entry, childKeySet);
      id2subtree.indexer.indexEntry(entry, subtreeKeySet);

      DatabaseEntry dbKey = new DatabaseEntry();
      DatabaseEntry dbVal = new DatabaseEntry();
      ImportIDSet idSet = new ImportIDSet(1, id2children.getIndexEntryLimit(),
                                          id2children.getMaintainCount());
      idSet.addEntryID(entryID);
      id2children.insert(idSet, childKeySet, dbKey, dbVal);

      DatabaseEntry dbSubKey = new DatabaseEntry();
      DatabaseEntry dbSubVal = new DatabaseEntry();
      ImportIDSet idSubSet = new ImportIDSet(1, id2subtree.getIndexEntryLimit(),
                                             id2subtree.getMaintainCount());
      idSubSet.addEntryID(entryID);
      id2subtree.insert(idSubSet, subtreeKeySet, dbSubKey, dbSubVal);
    }

    EntryID getAncestorID(DN2ID dn2id, DN dn)
            throws DatabaseException
    {
      int i=0;
      EntryID nodeID = dn2id.get(null, dn, LockMode.DEFAULT);
      if(nodeID == null) {
        while((nodeID = dn2id.get(null, dn, LockMode.DEFAULT)) == null) {
          try {
            Thread.sleep(50);
            if(i == 3) {
              return null;
            }
            i++;
          } catch (Exception e) {
            return null;
          }
        }
      }
      return nodeID;
    }



    void
    processIndexes(Suffix ctx, Entry entry, EntryID entryID) throws
            DatabaseException, DirectoryException, JebException, ConfigException
    {
      Transaction txn = null;
      Map<AttributeType, AttributeIndex> attrMap = ctx.getAttrIndexMap();
      for(Map.Entry<AttributeType, AttributeIndex> mapEntry :
              attrMap.entrySet()) {
        AttributeType attrType = mapEntry.getKey();
        if(entry.hasAttribute(attrType)) {
          AttributeIndex attributeIndex = mapEntry.getValue();
          Index index;
          if((index=attributeIndex.getEqualityIndex()) != null) {
            indexAttr(index, entry, entryID,
                      new IndexKey(attrType,IndexType.EQUALITY));
          }
          if((index=attributeIndex.getPresenceIndex()) != null) {
            indexAttr(index, entry, entryID,
                      new IndexKey(attrType, IndexType.PRESENCE));
          }
          if((index=attributeIndex.getSubstringIndex()) != null) {
            int subLen = ((SubstringIndexer)index.indexer).getSubStringLen();
            indexAttr(index, entry, entryID,
                      new IndexKey(attrType, IndexType.SUBSTRING, subLen));
          }
          if((index=attributeIndex.getOrderingIndex()) != null) {
            indexAttr(index, entry, entryID,
                      new IndexKey(attrType, IndexType.ORDERING));
          }
          if((index=attributeIndex.getApproximateIndex()) != null) {
            indexAttr(index, entry, entryID,
                       new IndexKey(attrType,IndexType.APPROXIMATE));
          }
          for(VLVIndex vlvIdx : ctx.getEntryContainer().getVLVIndexes()) {
            vlvIdx.addEntry(txn, entryID, entry);
          }
          Map<String,Collection<Index>> extensibleMap =
                  attributeIndex.getExtensibleIndexes();
          if(!extensibleMap.isEmpty()) {
            Collection<Index> subIndexes =
                    attributeIndex.getExtensibleIndexes().get(
                            EXTENSIBLE_INDEXER_ID_SUBSTRING);
            if(subIndexes != null) {
              for(Index subIndex: subIndexes) {
                indexAttr(subIndex, entry, entryID,
                          new IndexKey(attrType, IndexType.EX_SUBSTRING));
              }
            }
            Collection<Index> sharedIndexes =
                    attributeIndex.getExtensibleIndexes().get(
                            EXTENSIBLE_INDEXER_ID_SHARED);
            if(sharedIndexes !=null) {
              for(Index sharedIndex:sharedIndexes) {
                indexAttr(sharedIndex, entry, entryID,
                          new IndexKey(attrType, IndexType.EX_SHARED));
              }
            }
          }
        }
      }
    }



   void indexAttr(Index index, Entry entry, EntryID entryID,
                           IndexKey indexKey) throws DatabaseException,
            ConfigException
    {
      insertKeySet.clear();
      index.indexer.indexEntry(entry, insertKeySet);
      for(byte[] key : insertKeySet)
      {
        processKey(index, key, entryID, indexComparator, indexKey, true);
      }
    }


    void flushIndexBuffers() throws InterruptedException,
                 ExecutionException
    {
       Set<Map.Entry<IndexKey, IndexBuffer>> set = indexBufferMap.entrySet();
       for(Map.Entry<IndexKey, IndexBuffer> e : set)
        {
          IndexKey indexKey = e.getKey();
          IndexBuffer indexBuffer = e.getValue();
          IndexType indexType = indexKey.getIndexType();
          if(indexType.equals(IndexType.DN))
          {
            indexBuffer.setComparator(dnComparator);
          }
          else
          {
            indexBuffer.setComparator(indexComparator);
          }
          indexBuffer.setIndexKey(indexKey);
          Future<Void> future = sortService.submit(new SortTask(indexBuffer));
          future.get();
        }
    }


    int
    processKey(DatabaseContainer container, byte[] key, EntryID entryID,
         IndexBuffer.ComparatorBuffer<byte[]> comparator, IndexKey indexKey,
         boolean insert)
         throws ConfigException
    {
      IndexBuffer indexBuffer;
      if(!indexBufferMap.containsKey(indexKey))
      {
        indexBuffer = getNewIndexBuffer();
        indexBufferMap.put(indexKey, indexBuffer);
      }
      else
      {
        indexBuffer = indexBufferMap.get(indexKey);
      }
      if(!indexBuffer.isSpaceAvailable(key))
      {
        indexBuffer.setComparator(comparator);
        indexBuffer.setIndexKey(indexKey);
        sortService.submit(new SortTask(indexBuffer));
        indexBuffer = getNewIndexBuffer();
        indexBufferMap.remove(indexKey);
        indexBufferMap.put(indexKey, indexBuffer);
      }
      int id = System.identityHashCode(container);
      idContainerMap.putIfAbsent(id, container);
      indexBuffer.add(key, entryID, id, insert);
      return id;
    }


    IndexBuffer getNewIndexBuffer() throws ConfigException
    {
      IndexBuffer indexBuffer = freeBufQue.poll();
      if(indexBuffer.isPoison())
      {
        Message msg = Message.raw(Category.JEB, Severity.SEVERE_ERROR,
                "Abort import - MPD");
        throw new ConfigException(msg);
      }
      return indexBuffer;
    }


    void processDN2ID(Suffix suffix, DN dn, EntryID entryID)
            throws ConfigException
    {
      DatabaseContainer dn2id = suffix.getDN2ID();
      byte[] dnBytes = StaticUtils.getBytes(dn.toNormalizedString());
      int id = processKey(dn2id, dnBytes, entryID, dnComparator,
                 new IndexKey(dnType, IndexType.DN), true);
      idECMap.putIfAbsent(id, suffix.getEntryContainer());
    }
  }


  /**
   * The task reads the temporary index files and writes their results to the
   * index database.
   */
  private final class IndexWriteDBTask implements Callable<Void>
  {
    private final IndexManager indexMgr;
    private final DatabaseEntry dbKey, dbValue;
    private final int cacheSize;
    private ByteBuffer directBuffer = null;
    private final Map<Integer, DNState> dnStateMap =
            new HashMap<Integer, DNState>();
    private final Map<Integer, Index> indexMap = new HashMap<Integer, Index>();

    public IndexWriteDBTask(IndexManager indexMgr, ByteBuffer b, int cacheSize)
    {
      this.indexMgr = indexMgr;
      directBuffer = b;
      this.dbKey = new DatabaseEntry();
      this.dbValue = new DatabaseEntry();
      this.cacheSize = cacheSize;
    }

    private SortedSet<Buffer> initBuffers() throws IOException
    {
      int p = 0;
      int offSet = cacheSize;
      SortedSet<Buffer> bufferSet = new TreeSet<Buffer>();
      for(Buffer b : indexMgr.getBufferList())
      {
        if(directBuffer != null)
        {
          directBuffer.position(p);
          directBuffer.limit(offSet);
          ByteBuffer slice = directBuffer.slice();
          b.init(indexMgr, slice, cacheSize);
          p += cacheSize;
          offSet += cacheSize;
        }
        else
        {
          b.init(indexMgr, null, cacheSize);
        }
        bufferSet.add(b);
      }
      return bufferSet;
    }

    public Void call() throws Exception
    {
      byte[] cKey = null;
      ImportIDSet cInsertIDSet = null, cDeleteIDSet = null;
      Integer cIndexID = null;

      indexMgr.init();
      SortedSet<Buffer> bufferSet = initBuffers();
      while(!bufferSet.isEmpty())
      {
        Buffer b;
        b = bufferSet.first();
        bufferSet.remove(b);
        if(cKey == null)
        {
          cIndexID =  b.getIndexID();
          cKey = b.getKey();
          cInsertIDSet = b.getInsertIDSet();
          cDeleteIDSet = b.getDeleteIDSet();
          cInsertIDSet.setKey(cKey);
          cDeleteIDSet.setKey(cKey);
        }
        else
        {
          if(b.compare(cKey, cIndexID) != 0)
          {
            addToDB(cInsertIDSet, cDeleteIDSet, cIndexID);
            indexMgr.incrKeyCount();
            cIndexID =  b.getIndexID();
            cKey = b.getKey();
            cInsertIDSet = b.getInsertIDSet();
            cDeleteIDSet = b.getDeleteIDSet();
            cInsertIDSet.setKey(cKey);
            cDeleteIDSet.setKey(cKey);
          }
          else
          {
            cInsertIDSet.merge(b.getInsertIDSet());
            cDeleteIDSet.merge(b.getDeleteIDSet());
          }
        }
        if(b.hasMoreData())
        {
          b.getNextRecord();
          bufferSet.add(b);
        }
      }
      if(cKey != null)
      {
        addToDB(cInsertIDSet, cDeleteIDSet, cIndexID);
      }
      cleanUP();
      return null;
    }


    private void cleanUP() throws DatabaseException, DirectoryException,
            IOException
    {
      if(indexMgr.isDN2ID() && skipDNValidation)
      {
        for(DNState dnState : dnStateMap.values())
        {
          dnState.flush();
        }
        Message msg = NOTE_JEB_IMPORT_LDIF_DN_CLOSE.get(indexMgr.getDNCount());
        logError(msg);
      }
      else
      {
        for(Index index : indexMap.values())
        {
          index.closeCursor();
        }
        Message msg = NOTE_JEB_IMPORT_LDIF_INDEX_CLOSE.get(indexMgr.getName());
        logError(msg);
      }
      indexMgr.setDone();
      indexMgr.close();
      indexMgr.deleteIndexFile();
    }

    private void addToDB(ImportIDSet insRec, ImportIDSet delRec, int indexID)
            throws InterruptedException, DatabaseException, DirectoryException
    {
      if(!indexMgr.isDN2ID())
      {
        Index index;
        if((delRec.size() > 0) || (!delRec.isDefined()))
        {
          dbKey.setData(delRec.getKey());
          index =  (Index)idContainerMap.get(indexID);
          index.delete(dbKey, delRec, dbValue);
          if(!indexMap.containsKey(indexID))
          {
            indexMap.put(indexID, index);
          }
        }


        if((insRec.size() > 0) || (!insRec.isDefined()))
        {
          dbKey.setData(insRec.getKey());
          index =  (Index)idContainerMap.get(indexID);
          index.insert(dbKey, insRec, dbValue);
          if(!indexMap.containsKey(indexID))
          {
            indexMap.put(indexID, index);
          }
        }
      }
      else if(skipDNValidation)
      {
        addDN2ID(insRec, indexID);
      }
    }

    private void addDN2ID(ImportIDSet record, Integer indexID)
            throws DatabaseException, DirectoryException
    {
      DNState dnState;
      if(!dnStateMap.containsKey(indexID))
      {
        dnState = new DNState(idECMap.get(indexID));
        dnStateMap.put(indexID, dnState);
      }
      else
      {
        dnState = dnStateMap.get(indexID);
      }

      if(!dnState.checkParent(record))
      {
        return;
      }
      dnState.writeToDB();
    }


    /**
     * This class is used to by a index DB merge thread performing DN processing
     * to keep track of the state of individual DN2ID index processing.
     */
    class DNState
    {
      //DN related stuff per suffix
      private final DatabaseEntry dbKey1, dbValue1;
      private final TreeMap<DN, EntryID> parentIDMap =
                    new TreeMap<DN, EntryID>();
      private DN parentDN, lastDN;
      private EntryID parentID, lastID, entryID;
      private final EntryContainer entryContainer;
      private final Map<byte[], ImportIDSet> id2childTree;
      private final Map<byte[], ImportIDSet> id2subtreeTree;
      private final Index childIndex, subIndex;
      private final DN2ID dn2id;

      DNState(EntryContainer entryContainer)
      {
        this.entryContainer = entryContainer;
        dn2id = entryContainer.getDN2ID();
        childIndex = entryContainer.getID2Children();
        subIndex = entryContainer.getID2Subtree();
        Comparator<byte[]> childComparator = childIndex.getComparator();
        Comparator<byte[]> subComparator =  subIndex.getComparator();
        id2childTree = new TreeMap<byte[], ImportIDSet>(childComparator);
        id2subtreeTree =  new TreeMap<byte[], ImportIDSet>(subComparator);
        this.dbKey1 = new DatabaseEntry();
        this.dbValue1 = new DatabaseEntry();
      }


      private boolean checkParent(ImportIDSet record) throws DirectoryException
      {
        dbKey1.setData(record.getKey());
        byte[] v = record.toDatabase();
        long v1 = JebFormat.entryIDFromDatabase(v);
        dbValue1.setData(v);
        DN dn = DN.decode(ByteString.wrap(dbKey1.getData()));


        entryID = new EntryID(v1);
        if(parentIDMap.isEmpty())
        {
          parentIDMap.put(dn, entryID);
          return true;
        }
        else if(lastDN != null && lastDN.isAncestorOf(dn))
        {
          parentIDMap.put(lastDN, lastID);
          parentDN = lastDN;
          parentID = lastID;
          lastDN = dn;
          lastID = entryID;
          return true;
        }
        else if(parentIDMap.lastKey().isAncestorOf(dn))
        {
          parentDN = parentIDMap.lastKey();
          parentID = parentIDMap.get(parentDN);
          lastDN = dn;
          lastID = entryID;
          return true;
        }
        else
        {
          DN pDN = entryContainer.getParentWithinBase(dn);
          if(parentIDMap.containsKey(pDN)) {
            DN lastKey = parentIDMap.lastKey();
            Map<DN, EntryID> subMap = parentIDMap.subMap(pDN, lastKey);
            for(Map.Entry<DN, EntryID> e : subMap.entrySet())
            {
              subMap.remove(e.getKey());
            }
            parentDN = pDN;
            parentID = parentIDMap.get(pDN);
            lastDN = dn;
            lastID = entryID;
          }
          else
          {
            Message msg = NOTE_JEB_IMPORT_LDIF_DN_NO_PARENT.get(dn.toString());
            Entry e = new Entry(dn, null, null, null);
            reader.rejectEntry(e, msg);
            return false;
          }
        }
        return true;
      }


    private void id2child(EntryID childID)
    {
      ImportIDSet idSet;
      if(!id2childTree.containsKey(parentID.getDatabaseEntry().getData()))
      {
        idSet = new ImportIDSet(1,childIndex.getIndexEntryLimit(),
                                childIndex.getMaintainCount());
        id2childTree.put(parentID.getDatabaseEntry().getData(), idSet);
      }
      else
      {
        idSet = id2childTree.get(parentID.getDatabaseEntry().getData());
      }
      idSet.addEntryID(childID);
    }


      private void id2Subtree(EntryID childID) throws DatabaseException
      {
        ImportIDSet idSet;
        if(!id2subtreeTree.containsKey(parentID.getDatabaseEntry().getData()))
        {
          idSet = new ImportIDSet(1, subIndex.getIndexEntryLimit(),
                                  subIndex.getMaintainCount());
          id2subtreeTree.put(parentID.getDatabaseEntry().getData(), idSet);
        }
        else
        {
          idSet = id2subtreeTree.get(parentID.getDatabaseEntry().getData());
        }
        idSet.addEntryID(childID);
        for (DN dn = entryContainer.getParentWithinBase(parentDN); dn != null;
             dn = entryContainer.getParentWithinBase(dn))
        {
          EntryID nodeID = parentIDMap.get(dn);
          if(!id2subtreeTree.containsKey(nodeID.getDatabaseEntry().getData()))
          {
            idSet = new ImportIDSet(1, subIndex.getIndexEntryLimit(),
                                    subIndex.getMaintainCount());
            id2subtreeTree.put(nodeID.getDatabaseEntry().getData(), idSet);
          }
          else
          {
            idSet = id2subtreeTree.get(nodeID.getDatabaseEntry().getData());
          }
          idSet.addEntryID(childID);
        }
      }


     public void writeToDB() throws DatabaseException
     {
      dn2id.putRaw(null, dbKey1, dbValue1);
      indexMgr.addTotDNCount(1);
      if(parentDN != null)
      {
        id2child(entryID);
        id2Subtree(entryID);
      }
     }


      public void flush() throws DatabaseException, DirectoryException
      {
        Set<Map.Entry<byte[], ImportIDSet>> id2childSet =
                id2childTree.entrySet();
        for(Map.Entry<byte[], ImportIDSet> e : id2childSet)
        {
          byte[] key = e.getKey();
          ImportIDSet idSet = e.getValue();
          dbKey1.setData(key);
          childIndex.insert(dbKey1, idSet, dbValue1);
        }
        childIndex.closeCursor();
        //Do subtree.
        Set<Map.Entry<byte[], ImportIDSet>> subtreeSet =
                id2subtreeTree.entrySet();
        for(Map.Entry<byte[], ImportIDSet> e : subtreeSet)
        {
          byte[] key = e.getKey();
          ImportIDSet idSet = e.getValue();
          dbKey1.setData(key);
          subIndex.insert(dbKey1, idSet, dbValue1);
        }
        subIndex.closeCursor();
      }
    }
  }


  /**
   * This task writes the temporary index files using the sorted buffers read
   * from a blocking queue.
   */
  private final class IndexFileWriterTask implements Runnable
  {
    private final IndexManager indexMgr;
    private final BlockingQueue<IndexBuffer> que;
    private final ByteArrayOutputStream insetByteStream =
            new ByteArrayOutputStream(2 * bufferSize);
    private final ByteArrayOutputStream deleteByteStream =
            new ByteArrayOutputStream(2 * bufferSize);
    private final DataOutputStream dataStream;
    private long bufCount = 0;
    private final File file;
    private final SortedSet<IndexBuffer> indexSortedSet;
    private boolean poisonSeen = false;

    public IndexFileWriterTask(BlockingQueue<IndexBuffer> que,
                            IndexManager indexMgr) throws FileNotFoundException
    {
      this.que = que;
      file = indexMgr.getFile();
      this.indexMgr = indexMgr;
      BufferedOutputStream bufferedStream =
                   new BufferedOutputStream(new FileOutputStream(file), 2 * MB);
      dataStream = new DataOutputStream(bufferedStream);
      indexSortedSet = new TreeSet<IndexBuffer>();
    }


    public void run()
    {
      long offset = 0;
      List<IndexBuffer> l = new LinkedList<IndexBuffer>();
      try {
        while(true)
        {
          IndexBuffer indexBuffer = que.poll();
          if(indexBuffer != null)
          {
            long beginOffset = offset;
            long bufLen;
            /*
            if(!que.isEmpty())
            {
              que.drainTo(l, DRAIN_TO);
              l.add(indexBuffer);
              bufLen = writeIndexBuffers(l);
              for(IndexBuffer id : l)
              {
                id.reset();
              }
              freeBufQue.addAll(l);
              l.clear();
            }
            else
            {
            */
              if(indexBuffer.isPoison())
              {
                break;
              }
              bufLen = writeIndexBuffer(indexBuffer);
              indexBuffer.reset();
              freeBufQue.add(indexBuffer);
     //       }
            offset += bufLen;
            indexMgr.addBuffer(new Buffer(beginOffset, offset, bufCount));
            bufCount++;
            bufferCount.incrementAndGet();
            if(poisonSeen)
            {
              break;
            }
          }
        }
        dataStream.close();
        indexMgr.setFileLength();
      }
      catch (IOException e) {
        Message msg =
                ERR_JEB_IMPORT_LDIF_INDEX_FILEWRITER_ERR.get(file.getName(),
                        e.getMessage());
        logError(msg);
      }
    }


    private long writeIndexBuffer(IndexBuffer indexBuffer) throws IOException
    {
      int numKeys = indexBuffer.getNumberKeys();
      indexBuffer.setPos(-1);
      long bufLen = 0;
      insetByteStream.reset();
      deleteByteStream.reset();
      for(int i = 0; i < numKeys; i++)
      {
        if(indexBuffer.getPos() == -1)
        {
          indexBuffer.setPos(i);
          if(indexBuffer.isInsert(i))
          {
             insetByteStream.write(indexBuffer.getIDBytes(i));
          }
          else
          {
             deleteByteStream.write(indexBuffer.getIDBytes(i));
          }
          continue;
        }
        if(!indexBuffer.compare(i))
        {
          bufLen += indexBuffer.writeRecord(insetByteStream, deleteByteStream,
                                            dataStream);
          indexBuffer.setPos(i);
          insetByteStream.reset();
          deleteByteStream.reset();
        }
        if(indexBuffer.isInsert(i))
        {
          insetByteStream.write(indexBuffer.getIDBytes(i));
        }
        else
        {
          deleteByteStream.write(indexBuffer.getIDBytes(i));
        }
      }
      if(indexBuffer.getPos() != -1)
      {
        bufLen += indexBuffer.writeRecord(insetByteStream, deleteByteStream,
                                          dataStream);
      }
      return bufLen;
    }


    private long writeIndexBuffers(List<IndexBuffer> buffers)
            throws IOException
    {
      long id = 0;
      long bufLen = 0;
      insetByteStream.reset();
      deleteByteStream.reset();
      for(IndexBuffer b : buffers)
      {
        if(b.isPoison())
        {
          poisonSeen = true;
        }
        else
        {
          b.setPos(0);
          b.setID(id++);
          indexSortedSet.add(b);
        }
      }
      byte[] saveKey = null;
      int saveIndexID = 0;
      while(!indexSortedSet.isEmpty())
      {
        IndexBuffer b = indexSortedSet.first();
        indexSortedSet.remove(b);
        if(saveKey == null)
        {
          saveKey =  b.getKeyBytes();
          saveIndexID = b.getIndexID();
          if(b.isInsert(b.getPos()))
          {
            insetByteStream.write(b.getIDBytes(b.getPos()));
          }
          else
          {
              deleteByteStream.write(b.getIDBytes(b.getPos()));
          }
        }
        else
        {
          if(!b.compare(saveKey, saveIndexID))
          {
            bufLen += IndexBuffer.writeRecord(saveKey, saveIndexID,
                                 insetByteStream, deleteByteStream, dataStream);
            insetByteStream.reset();
            deleteByteStream.reset();
            saveKey = b.getKeyBytes();
            saveIndexID =  b.getIndexID();
            if(b.isInsert(b.getPos()))
            {
              insetByteStream.write(b.getIDBytes(b.getPos()));
            }
            else
            {
              deleteByteStream.write(b.getIDBytes(b.getPos()));
            }
          }
          else
          {
            if(b.isInsert(b.getPos()))
            {
              insetByteStream.write(b.getIDBytes(b.getPos()));
            }
            else
            {
              deleteByteStream.write(b.getIDBytes(b.getPos()));
            }
          }
        }
        if(b.hasMoreData())
        {
          b.getNextRecord();
          indexSortedSet.add(b);
        }
      }
      if(saveKey != null)
      {
        bufLen += IndexBuffer.writeRecord(saveKey, saveIndexID,
                              insetByteStream, deleteByteStream, dataStream);
      }
      return bufLen;
    }
  }

  /**
   * This task main function is to sort the index buffers given to it from
   * the import tasks reading the LDIF file. It will also create a index
   * file writer task and corresponding queue if needed. The sorted index
   * buffers are put on the index file writer queues for writing to a temporary
   * file.
   */
  private final class SortTask implements Callable<Void>
  {

    private final IndexBuffer indexBuffer;

    public SortTask(IndexBuffer indexBuffer)
    {
      this.indexBuffer = indexBuffer;
    }

    /**
     * {@inheritDoc}
     */
    public Void call() throws Exception
    {
      if (config.isCancelled())
      {
        return null;
      }
      /*
      if(!indexBuffer.getIndexKey().getName().equals("mail.SUBSTRING"))
      {
        freeBufQue.add(indexBuffer);
        return null;
      }
      */
      indexBuffer.sort();
      if(indexKeyQueMap.containsKey(indexBuffer.getIndexKey())) {
        BlockingQueue<IndexBuffer> q =
                indexKeyQueMap.get(indexBuffer.getIndexKey());
        q.add(indexBuffer);
      }
      else
      {
        createIndexWriterTask(indexBuffer.getIndexKey());
        BlockingQueue<IndexBuffer> q =
                                 indexKeyQueMap.get(indexBuffer.getIndexKey());
        q.add(indexBuffer);
      }
      return null;
    }

    private void createIndexWriterTask(IndexKey indexKey)
            throws FileNotFoundException
    {
      boolean dn2id = false;
      synchronized(synObj)
      {
        if(indexKeyQueMap.containsKey(indexKey))
        {
          return;
        }
        if(indexKey.getIndexType().equals(IndexType.DN))
        {
          dn2id = true;
        }
        IndexManager indexMgr = new IndexManager(indexKey.getName(), dn2id);
        indexMgrList.add(indexMgr);
        BlockingQueue<IndexBuffer> newQue =
                new ArrayBlockingQueue<IndexBuffer>(indexBufferCount);
        IndexFileWriterTask indexWriter =
                new IndexFileWriterTask(newQue, indexMgr);
        indexWriterList.add(indexWriter);
        indexWriterFutures.add(indexProcessService.submit(indexWriter));
        indexKeyQueMap.put(indexKey, newQue);
      }
    }
  }

  /**
   * The buffer class is used to process a buffer from the temporary index files
   * during phase 2 processing.
   */
  private final class Buffer implements Comparable<Buffer>
  {
    private IndexManager indexMgr;
    private final long begin, end, id;
    private long offset;
    private ByteBuffer cache;
    private int keyLen, idLen, limit;
    private byte[] key;
    private ImportIDSet insertIDSet, deleteIDSet;
    private Integer indexID = null;
    private boolean doCount;
    private Comparator<byte[]> comparator;


    public Buffer(long begin, long end, long id)
    {
      this.begin = begin;
      this.end = end;
      this.offset = 0;
      this.id = id;
    }


    private void init(IndexManager indexMgr, ByteBuffer b,
                      long cacheSize) throws IOException
    {
      this.indexMgr = indexMgr;
      if(b == null)
      {
        cache = ByteBuffer.allocate((int)cacheSize);
      }
      else
      {
        cache = b;
      }
      loadCache();
      cache.flip();
    }


    private void loadCache() throws IOException
    {
      FileChannel fileChannel = indexMgr.getChannel();
      fileChannel.position(begin + offset);
      long leftToRead =  end - (begin + offset);
      long bytesToRead;
      if(leftToRead < cache.remaining())
      {
        int pos = cache.position();
        cache.limit((int) (pos + leftToRead));
        bytesToRead = (int)leftToRead;
      }
      else
      {
        bytesToRead = Math.min((end - offset),cache.remaining());
      }
      int bytesRead = 0;
      while(bytesRead < bytesToRead)
      {
        bytesRead += fileChannel.read(cache);
      }
      offset += bytesRead;
      indexMgr.addBytesRead(bytesRead);
    }

    public boolean hasMoreData() throws IOException
    {
      boolean ret = ((begin + offset) >= end) ? true: false;
      if(cache.remaining() == 0 && ret)
      {
        return false;
      }
      else
      {
        return true;
      }
    }

    public byte[] getKey()
    {
      return key;
    }

    public ImportIDSet getInsertIDSet()
    {
      return insertIDSet;
    }

    public ImportIDSet getDeleteIDSet()
    {
      return deleteIDSet;
    }

    public long getBufID()
    {
      return id;
    }

    public Integer getIndexID()
    {
      if(indexID == null)
      {
        try {
          getNextRecord();
        } catch(IOException ex) {
          System.out.println("MPD need some error message");
        }
      }
      return indexID;
    }

    public void getNextRecord()  throws IOException
    {
      getNextIndexID();
      getContainerParams();
      getNextKey();
      getNextIDSet(true);  //get insert ids
      getNextIDSet(false); //get delete ids
    }

    private void getContainerParams()
    {
      limit = 1;
      doCount = false;
      if(!indexMgr.isDN2ID())
      {
        Index index = (Index) idContainerMap.get(indexID);
        limit = index.getIndexEntryLimit();
        doCount = index.getMaintainCount();
        comparator = index.getComparator();
      }
      else
      {
        DN2ID dn2id = (DN2ID) idContainerMap.get(indexID);
        comparator = dn2id.getComparator();
      }
    }

    private int getInt()  throws IOException
    {
      ensureData(4);
      return cache.getInt();
    }

    private long getLong()  throws IOException
    {
      ensureData(8);
      return cache.getLong();
    }

    private void getBytes(byte[] b) throws IOException
    {
      ensureData(b.length);
      cache.get(b);
    }

    private void getNextIndexID() throws IOException, BufferUnderflowException
     {
       indexID = new Integer(getInt());
     }

    private void getNextKey() throws IOException, BufferUnderflowException
    {
      keyLen = getInt();
      key = new byte[keyLen];
      getBytes(key);
    }

    private void getNextIDSet(boolean insert)
            throws IOException, BufferUnderflowException
    {
      idLen = getInt();
      int idCount = idLen/8;

      if(insert)
      {
         insertIDSet = new ImportIDSet(idCount, limit, doCount);
      }
      else
      {
          deleteIDSet = new ImportIDSet(idCount, limit, doCount);
      }
      for(int i = 0; i < idCount; i++)
      {
        long l = getLong();
        if(insert)
        {
          insertIDSet.addEntryID(l);
        }
        else
        {
          deleteIDSet.addEntryID(l);
        }
      }
    }


    private void ensureData(int len) throws IOException
    {
      if(cache.remaining() == 0)
      {
        cache.clear();
        loadCache();
        cache.flip();
      }
      else if(cache.remaining() < len)
      {
        cache.compact();
        loadCache();
        cache.flip();
      }
    }


    private int compare(byte[] cKey, Integer cIndexID)
    {

      int rc;
      if(key == null)
      {
        getIndexID();
      }
      if(comparator.compare(key, cKey) != 0) {
        rc = 1;
      }
      else
      {
        rc = (indexID.intValue() == cIndexID.intValue()) ? 0 : 1;
      }
      return rc;
    }



    public int compareTo(Buffer o) {
      //used in remove.
      if(this.equals(o))
      {
        return 0;
      }
      if(key == null) {
        getIndexID();
      }
      if(o.getKey() == null)
      {
        o.getIndexID();
      }
      int rc = comparator.compare(key, o.getKey());
      if(rc == 0)
      {
        if(indexID.intValue() == o.getIndexID().intValue())
        {
          if(insertIDSet.isDefined())
          {
            rc = -1;
          }
          else if(o.getInsertIDSet().isDefined())
          {
            rc = 1;
          }
          else if(insertIDSet.size() == o.getInsertIDSet().size())
          {
            rc = id > o.getBufID() ? 1 : -1;
          }
          else
          {
            rc = insertIDSet.size() - o.getInsertIDSet().size();
          }
        }
        else if(indexID.intValue() > o.getIndexID().intValue())
        {
          rc = 1;
        }
        else
        {
          rc = -1;
        }
      }
      return rc;
    }
  }

  /**
   * The index manager class is used to carry information about index processing
   * from phase 1 to phase 2.
   */
  private final class IndexManager
  {
    private final File file;
    private RandomAccessFile raf = null;
    private final List<Buffer> bufferList = new LinkedList<Buffer>();
    private long fileLength, bytesRead = 0;
    private boolean done = false;
    private long totalDNS;
    private AtomicInteger keyCount = new AtomicInteger(0);
    private final String name;
    private final boolean dn2id;

    public IndexManager(String name, boolean dn2id)
    {
      file = new File(tempDir, name);
      this.name = name;
      this.dn2id = dn2id;
    }

    public void init() throws FileNotFoundException
    {
      raf = new RandomAccessFile(file, "r");
    }

    public FileChannel getChannel()
    {
      return raf.getChannel();
    }

    public void addBuffer(Buffer o)
    {
      this.bufferList.add(o);
    }

    public List<Buffer> getBufferList()
    {
      return bufferList;
    }

    public File getFile()
    {
      return file;
    }

    public void deleteIndexFile()
    {
       file.delete();
    }

    public void close() throws IOException
    {
        raf.close();
    }

    public void setFileLength()
    {
      this.fileLength = file.length();
    }

    public void addBytesRead(int bytesRead)
    {
      this.bytesRead += bytesRead;
    }

    public void setDone()
    {
      this.done = true;
    }

    public void addTotDNCount(int delta)
    {
      this.totalDNS += delta;
    }


    public long getDNCount()
    {
      return totalDNS;
    }

    public boolean isDN2ID()
    {
      return dn2id;
    }

    public void printStats(long deltaTime)
    {
      if(!done)
      {
        float rate = 1000f * keyCount.getAndSet(0) / deltaTime;
        Message msg = NOTE_JEB_IMPORT_LDIF_PHASE_TWO_REPORT.get(name,
                       (fileLength - bytesRead), rate);
        logError(msg);
      }
    }

    public void incrKeyCount()
    {
      keyCount.incrementAndGet();
    }

    public String getName()
    {
      return name;
    }
  }

  /**
   * This class reports progress of the import job at fixed intervals.
   */
  private final class FirstPhaseProgressTask extends TimerTask
  {
    /**
     * The number of entries that had been read at the time of the
     * previous progress report.
     */
    private long previousCount = 0;

    /**
     * The time in milliseconds of the previous progress report.
     */
    private long previousTime;

    /**
     * The environment statistics at the time of the previous report.
     */
    private EnvironmentStats prevEnvStats;

    /**
     * The number of bytes in a megabyte. Note that 1024*1024 bytes may
     * eventually become known as a mebibyte(MiB).
     */
    public static final int bytesPerMegabyte = 1024 * 1024;

    // Determines if the ldif is being read.
    private boolean ldifRead = false;

    // Determines if eviction has been detected.
    private boolean evicting = false;

    // Entry count when eviction was detected.
    private long evictionEntryCount = 0;

    // Suspend output.
    private boolean pause = false;



    /**
     * Create a new import progress task.
     */
    public FirstPhaseProgressTask()
    {
      previousTime = System.currentTimeMillis();
      try
      {
        prevEnvStats =
                rootContainer.getEnvironmentStats(new StatsConfig());
      }
      catch (DatabaseException e)
      {
        throw new RuntimeException(e);
      }
    }



    /**
     * The action to be performed by this timer task.
     */
    @Override
    public void run()
    {
      long latestCount = reader.getEntriesRead() + 0;
      long deltaCount = (latestCount - previousCount);
      long latestTime = System.currentTimeMillis();
      long deltaTime = latestTime - previousTime;
      Message message;
      if (deltaTime == 0)
      {
        return;
      }
      if (pause)
      {
        return;
      }
      if (!ldifRead)
      {
        long numRead = reader.getEntriesRead();
        long numIgnored = reader.getEntriesIgnored();
        long numRejected = reader.getEntriesRejected();
        float rate = 1000f * deltaCount / deltaTime;
        message =
                NOTE_JEB_IMPORT_PROGRESS_REPORT.get(numRead, numIgnored,
                        numRejected, 0, rate);
        logError(message);
      }
      try
      {
        Runtime runtime = Runtime.getRuntime();
        long freeMemory = runtime.freeMemory() / bytesPerMegabyte;
        EnvironmentStats envStats =
                rootContainer.getEnvironmentStats(new StatsConfig());
        long nCacheMiss =
                envStats.getNCacheMiss() - prevEnvStats.getNCacheMiss();

        float cacheMissRate = 0;
        if (deltaCount > 0)
        {
          cacheMissRate = nCacheMiss / (float) deltaCount;
        }
        message =
                NOTE_JEB_IMPORT_CACHE_AND_MEMORY_REPORT.get(freeMemory,
                        cacheMissRate);
        logError(message);
        long evictPasses = envStats.getNEvictPasses();
        long evictNodes = envStats.getNNodesExplicitlyEvicted();
        long evictBinsStrip = envStats.getNBINsStripped();
        long cleanerRuns = envStats.getNCleanerRuns();
        long cleanerDeletions = envStats.getNCleanerDeletions();
        long cleanerEntriesRead = envStats.getNCleanerEntriesRead();
        long cleanerINCleaned = envStats.getNINsCleaned();
        long checkPoints = envStats.getNCheckpoints();
        if (evictPasses != 0)
        {
          if (!evicting)
          {
            evicting = true;
            if (!ldifRead)
            {
              evictionEntryCount = reader.getEntriesRead();
              message =
                      NOTE_JEB_IMPORT_LDIF_EVICTION_DETECTED
                              .get(evictionEntryCount);
              logError(message);
            }
          }
          message =
                  NOTE_JEB_IMPORT_LDIF_EVICTION_DETECTED_STATS.get(
                          evictPasses, evictNodes, evictBinsStrip);
          logError(message);
        }
        if (cleanerRuns != 0)
        {
          message =
                  NOTE_JEB_IMPORT_LDIF_CLEANER_STATS.get(cleanerRuns,
                          cleanerDeletions, cleanerEntriesRead,
                          cleanerINCleaned);
          logError(message);
        }
        if (checkPoints > 1)
        {
          message =
                  NOTE_JEB_IMPORT_LDIF_BUFFER_CHECKPOINTS.get(checkPoints);
          logError(message);
        }
        prevEnvStats = envStats;
      }
      catch (DatabaseException e)
      {
        // Unlikely to happen and not critical.
      }
      previousCount = latestCount;
      previousTime = latestTime;
    }
  }



  /**
   * This class reports progress of the import job at fixed intervals.
   */
  private final class SecondPhaseProgressTask extends TimerTask
  {
    /**
     * The number of entries that had been read at the time of the
     * previous progress report.
     */
    private long previousCount = 0;

    /**
     * The time in milliseconds of the previous progress report.
     */
    private long previousTime;

    /**
     * The environment statistics at the time of the previous report.
     */
    private EnvironmentStats prevEnvStats;

    /**
     * The number of bytes in a megabyte. Note that 1024*1024 bytes may
     * eventually become known as a mebibyte(MiB).
     */
    public static final int bytesPerMegabyte = 1024 * 1024;

    // Determines if eviction has been detected.
    private boolean evicting = false;

    // Suspend output.
    private boolean pause = false;

    private final List<IndexManager> indexMgrList;


    /**
     * Create a new import progress task.
     * @param indexMgrList List of index managers.
     */
    public SecondPhaseProgressTask (List<IndexManager> indexMgrList)
    {
      previousTime = System.currentTimeMillis();
      this.indexMgrList = indexMgrList;
      try
      {
        prevEnvStats =
                rootContainer.getEnvironmentStats(new StatsConfig());
      }
      catch (DatabaseException e)
      {
        throw new RuntimeException(e);
      }
    }


    /**
     * The action to be performed by this timer task.
     */
    @Override
    public void run()
    {
      long latestCount = reader.getEntriesRead() + 0;
      long deltaCount = (latestCount - previousCount);
      long latestTime = System.currentTimeMillis();
      long deltaTime = latestTime - previousTime;
      Message message;
      if (deltaTime == 0)
      {
        return;
      }
      if (pause)
      {
        return;
      }

      try
      {
        Runtime runtime = Runtime.getRuntime();
        long freeMemory = runtime.freeMemory() / bytesPerMegabyte;
        EnvironmentStats envStats =
                rootContainer.getEnvironmentStats(new StatsConfig());
        long nCacheMiss =
                envStats.getNCacheMiss() - prevEnvStats.getNCacheMiss();

        float cacheMissRate = 0;
        if (deltaCount > 0)
        {
          cacheMissRate = nCacheMiss / (float) deltaCount;
        }
        message =
                NOTE_JEB_IMPORT_CACHE_AND_MEMORY_REPORT.get(freeMemory,
                        cacheMissRate);
        logError(message);
        long evictPasses = envStats.getNEvictPasses();
        long evictNodes = envStats.getNNodesExplicitlyEvicted();
        long evictBinsStrip = envStats.getNBINsStripped();
        long cleanerRuns = envStats.getNCleanerRuns();
        long cleanerDeletions = envStats.getNCleanerDeletions();
        long cleanerEntriesRead = envStats.getNCleanerEntriesRead();
        long cleanerINCleaned = envStats.getNINsCleaned();
        long checkPoints = envStats.getNCheckpoints();
        if (evictPasses != 0)
        {
          if (!evicting)
          {
            evicting = true;
          }
          message =
                  NOTE_JEB_IMPORT_LDIF_EVICTION_DETECTED_STATS.get(
                          evictPasses, evictNodes, evictBinsStrip);
          logError(message);
        }
        if (cleanerRuns != 0)
        {
          message =
                  NOTE_JEB_IMPORT_LDIF_CLEANER_STATS.get(cleanerRuns,
                          cleanerDeletions, cleanerEntriesRead,
                          cleanerINCleaned);
          logError(message);
        }
        if (checkPoints > 1)
        {
          message =
                  NOTE_JEB_IMPORT_LDIF_BUFFER_CHECKPOINTS.get(checkPoints);
          logError(message);
        }
        prevEnvStats = envStats;
      }
      catch (DatabaseException e)
      {
        // Unlikely to happen and not critical.
      }
      previousCount = latestCount;
      previousTime = latestTime;

      for(IndexManager indexMgr : indexMgrList)
      {
        indexMgr.printStats(deltaTime);
      }
    }
  }


  /**
   * A class to hold information about the entry determined by the LDIF reader.
   *
   */
  public class EntryInformation
  {
    private EntryID entryID;
    private Suffix suffix;


    /**
     * Return the suffix associated with the entry.
     *
     * @return Entry's suffix instance;
     */
    public Suffix getSuffix()
    {
      return suffix;
    }

    /**
     * Set the suffix instance associated with the entry.
     *
     * @param suffix The suffix associated with the entry.
     */
    public void setSuffix(Suffix suffix)
    {
      this.suffix = suffix;
    }

    /**
     * Set the entry's ID.
     *
     * @param entryID The entry ID to set the entry ID to.
     */
    public void setEntryID(EntryID entryID)
    {
      this.entryID = entryID;
    }

    /**
     * Return the entry ID associated with the entry.
     *
     * @return The entry ID associated with the entry.
     */
    public EntryID getEntryID()
    {
      return entryID;
    }
  }

  /**
   * This class defines the individual index type available.
   *
   */
  public enum IndexType {
    /**
     * The DN index type.
     **/
    DN,

    /**
     * The equality index type.
     **/
    EQUALITY,

    /**
     * The presence index type.
     **/
    PRESENCE,

    /**
     * The substring index type.
     **/
    SUBSTRING,

    /**
     * The ordering index type.
     **/
    ORDERING,

    /**
     * The approximate index type.
     **/
    APPROXIMATE,

    /**
     * The extensible substring  index type.
     **/
    EX_SUBSTRING,

    /**
     * The extensible shared index type.
     **/
    EX_SHARED;
  }


  /**
   * This class is used as and index key for several hash maps that need to
   * process multiple suffix index elements into a single que or map based on
   * both attribute type and index type (ie., cn.equality, sn.equality,...).
   *
   * It tries to perform some optimization if the index is a substring index.
   */
  public class IndexKey {

    private final AttributeType type;
    private final IndexType indexType;
    private byte[] keyBytes = null;

    /**
     * Create index key instance using the specified attribute type, index type
     * and substring length. Used only for substring indexes.
     *
     * @param type The attribute type.
     * @param indexType The index type.
     * @param subLen The substring length.
     */
    IndexKey(AttributeType type, IndexType indexType, int subLen)
    {
      this(type, indexType);
      keyBytes = new byte[subLen];
    }

   /**
     * Create index key instance using the specified attribute type, index type.
     *
     * @param type The attribute type.
     * @param indexType The index type.
     */
    IndexKey(AttributeType type, IndexType indexType)
    {
      this.type = type;
      this.indexType = indexType;
    }

    /**
     * An equals method that uses both the attribute type and the index type.
     *
     * @param obj the object to compare.
     * @return <CODE>true</CODE> if the objects are equal.
     */
    public boolean equals(Object obj)
    {
      IndexKey oKey = (IndexKey) obj;
      boolean rc = false;
      if(type.equals(oKey.getType()) && indexType.equals(oKey.getIndexType()))
      {
        rc = true;
      }
      return rc;
    }

    /**
     * An hashcode method that adds the hashcodes of the attribute type and
     * index type and returns that value.
     *
     * @return The combined hash values.
     */
    public int hashCode()
    {
      return type.hashCode() + indexType.hashCode();
    }

    /**
     * Return the attribute type.
     *
     * @return The attribute type.
     */
    public AttributeType getType()
    {
      return type;
    }

    /**
     * Return the index type.
     * @return The index type.
     */
    public IndexType getIndexType()
    {
      return indexType;
    }

    /**
     * Return the index key name, which is the attribute type primary name,
     * a period, and the index type name. Used for building file names and
     * output.
     *
     * @return  The index key name.
     */
    public String getName()
    {
      return type.getPrimaryName() + "." +
             StaticUtils.toLowerCase(indexType.name());
    }

    /**
     * Returns a preallocated byte array having substring len size if the
     * index key is a substring index and the desired size is equal to substring
     * len size. This is a performance hack for substring indexes only.
     *
     * @param size The size of byte array desired.
     * @return Either a preallocated byte array, or a freshly created one using
     *         the size parameter.
     */
    public byte[] getKeyBytes(int size)
    {
      if(keyBytes != null)
      {
        if(size == keyBytes.length)
        {
          return this.keyBytes;
        }
        else
        {
          return new byte[size];
        }
      }
      else
      {
        return new byte[size];
      }
    }
  }
}
