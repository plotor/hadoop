package org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer;

import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.service.CompositeService;
import org.apache.hadoop.thirdparty.com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.hadoop.util.concurrent.HadoopScheduledThreadPoolExecutor;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.event.Dispatcher;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.server.nodemanager.ContainerExecutor;
import org.apache.hadoop.yarn.server.nodemanager.Context;
import org.apache.hadoop.yarn.server.nodemanager.DeletionService;
import org.apache.hadoop.yarn.server.nodemanager.LocalDirsHandlerService;
import org.apache.hadoop.yarn.server.nodemanager.api.LocalizationProtocol;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer.event.LocalizationEvent;
import org.apache.hadoop.yarn.server.nodemanager.metrics.NodeManagerMetrics;
import org.apache.hadoop.yarn.server.nodemanager.recovery.NMStateStoreService;
import org.apache.hadoop.yarn.server.nodemanager.recovery.NMStateStoreService.RecoveredLocalizationState;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.concurrent.ScheduledExecutorService;

public abstract class AbstractResourceLocalizationService
    extends CompositeService
    implements EventHandler<LocalizationEvent>, LocalizationProtocol {

  public static final String NM_PRIVATE_DIR = "nmPrivate";
  public static final FsPermission NM_PRIVATE_PERM = new FsPermission((short) 0700);

  protected static final FsPermission PUBLIC_FILECACHE_FOLDER_PERMS =
      new FsPermission((short) 0755);

  protected final ContainerExecutor exec;
  protected final Dispatcher dispatcher;
  protected final DeletionService delService;
  protected final LocalDirsHandlerService dirsHandler;
  protected final Context nmContext;
  protected final NodeManagerMetrics metrics;

  protected final ScheduledExecutorService cacheCleanup;
  protected final NMStateStoreService stateStore;

  public AbstractResourceLocalizationService(String name,
                                             ContainerExecutor exec,
                                             Dispatcher dispatcher,
                                             DeletionService delService,
                                             LocalDirsHandlerService dirsHandler,
                                             Context nmContext,
                                             NodeManagerMetrics metrics) {
    super(name);
    this.exec = exec;
    this.dispatcher = dispatcher;
    this.delService = delService;
    this.dirsHandler = dirsHandler;

    this.cacheCleanup = new HadoopScheduledThreadPoolExecutor(
        1, new ThreadFactoryBuilder()
        .setNameFormat("ResourceLocalizationService Cache Cleanup")
        .build());
    this.stateStore = nmContext.getNMStateStore();
    this.nmContext = nmContext;
    this.metrics = metrics;
  }

  public abstract LocalizedResource getLocalizedResource(LocalResourceRequest req,
                                                         String user,
                                                         ApplicationId appId);

  public abstract void recoverLocalizedResources(RecoveredLocalizationState state)
      throws URISyntaxException, IOException;

}
